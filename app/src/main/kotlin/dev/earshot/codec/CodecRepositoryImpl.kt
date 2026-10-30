package dev.earshot.codec

import android.bluetooth.BluetoothA2dp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import dev.earshot.bt.DeviceFinder
import dev.earshot.core.CODEC_SWITCH_UNSUPPORTED_MESSAGE
import dev.earshot.core.CodecInfo
import dev.earshot.core.CodecOption
import dev.earshot.core.CodecRepository
import dev.earshot.core.CodecSelection
import dev.earshot.core.CodecState
import dev.earshot.core.ConnectionProfile
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.PrivilegeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Codec panel backend. Read paths, most to least capable, each falling through to the next:
 *  1. the privileged route's user service ([PrivilegedCodecAccess]: Shizuku's `CodecUserService`,
 *     shell uid, GitHub build only): `IBluetoothA2dp.getCodecStatus`, returned as
 *     `BluetoothCodecStatus.toString()`;
 *  2. `dumpsys bluetooth_manager` through the same route, parsed by [DumpsysCodecParser];
 *  3. Android 13+, experimental: `BluetoothA2dp.getCodecStatus` on our own proxy for a headset
 *     linked through the universal remote ([AssociationCodecPath]; the stack accepts a Companion
 *     Device Manager association instead of BLUETOOTH_PRIVILEGED);
 *  4. before Android 12 only, `BluetoothA2dp.getCodecStatus` on our own proxy;
 *  5. public APIs: A2DP connection state, phone-side codec list (Android 15+), `AudioDeviceInfo`.
 * The write path is the user service, or the companion link when path 3 answered and Shizuku isn't
 * ready; otherwise [selectCodec] throws and the UI points at [openDeveloperCodecSettings]. No
 * hidden-API bypass runs in this (the app's) process: the reflective calls are on SDK/system-API
 * methods that Android's hidden-API policy allows.
 *
 * The Google Play build has no privileged route ([PrivilegedCodecAccess.None], B8 in
 * docs/research/play-readiness.md): paths 1 and 2 are skipped, the privilege stays UNAVAILABLE and
 * no message names Shizuku; reading and the companion-link switch work as in the GitHub build.
 */
class CodecRepositoryImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The headset Earshot shows, so the panel reads that one when a car or speaker is connected too. */
    private val preferredAddress: () -> String? = { null },
) : CodecRepository {

    // canSwitch follows the privileged route (Shizuku running + permission), re-evaluated on every refresh.
    private val _state = MutableStateFlow(CodecState(canSwitch = false))
    override val state: StateFlow<CodecState> = _state

    private val proxies = A2dpProxyHolder(context)

    /** Shizuku in the GitHub build, none in the Google Play build (each flavour's PrivilegedCodecRoute). */
    private val privileged: PrivilegedCodecAccess = PrivilegedCodecRoute.create(context)
    private val companionLink = AssociationCodecPath(context)
    private val refreshMutex = Mutex()

    /** The last refresh read the shown headset through its companion link (path 3), so switching can use it too. */
    @Volatile private var linkActive = false
    private var debounceJob: Job? = null

    /** The late re-read after a broadcast; debounced on its own so a burst of broadcasts costs one. */
    private var lateJob: Job? = null

    /** A play/stop broadcast was skipped while no screen showed the codec; refresh when one does. */
    @Volatile private var staleWhileUnwatched = false
    private val watchJob: Job

    /** Numeric codec ids the stack reported last time, keyed by normalised name; needed to build configs. */
    @Volatile private var codecTypeIds: Map<String, Int> = emptyMap()

    /** elapsedRealtime of the last failed service read; 0 when the last one worked. */
    @Volatile private var serviceReadFailedAt = 0L

    /** Phone-side codec names learned on the last refresh (service list, dumpsys table, public API). */
    @Volatile private var lastExtraPhoneCodecs: List<String> = emptyList()

    /**
     * The codec Android last broadcast per device address (upper case). Since Android 13 the A2DP
     * service sends `CODEC_CONFIG_CHANGED` with the full `BluetoothCodecStatus` to any app holding
     * BLUETOOTH_CONNECT, so the current codec is readable without Shizuku, from the next change on
     * (the broadcast is not sticky). Its `toString()` is the same text the legacy reflective call
     * returned, so [CodecStatusText] parses it.
     */
    private val broadcastStatus = java.util.concurrent.ConcurrentHashMap<String, ParsedA2dpDevice>()

    private val codecConfigReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            val address = DeviceFinder.deviceAddressFrom(intent) ?: return
            val status = runCatching { intent.getParcelableExtra(EXTRA_CODEC_STATUS, android.bluetooth.BluetoothCodecStatus::class.java) }.getOrNull()
                ?: return
            val parsed = CodecStatusText.parse(status.toString(), address) ?: return
            broadcastStatus[address.uppercase()] = parsed
            Log.i(TAG, "codec from broadcast for $address: ${parsed.current?.codecName ?: "?"}")
            scheduleRefresh(DEBOUNCE_MS)
        }
    }

    private val a2dpReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // PLAYING_STATE_CHANGED fires on every play, pause and notification sound, and the
            // receiver lives as long as the process; with Shizuku each refresh may run dumpsys.
            // Only the screens read this state, so while none collects it the change just marks
            // the state stale (CODEC_CONFIG_CHANGED still records real codec changes meanwhile).
            if (intent.action == BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED && _state.subscriptionCount.value == 0) {
                staleWhileUnwatched = true
                return
            }
            // Codec negotiation finishes a moment after the connection broadcast, and some stacks
            // renegotiate when streaming starts (LDAC ABR, Samsung Scalable), so refresh twice.
            scheduleRefresh(DEBOUNCE_MS)
            scheduleRefresh(LATE_REFRESH_MS, late = true)
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            a2dpReceiver,
            IntentFilter().apply {
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED, // sent by the Bluetooth app, not by us
        )
        ContextCompat.registerReceiver(
            context, codecConfigReceiver, IntentFilter(ACTION_CODEC_CONFIG_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        // Shizuku hands us its binder asynchronously; re-evaluate privilege whenever that changes.
        privileged.watch(
            onAppeared = {
                serviceReadFailedAt = 0L // Shizuku (re)started: worth trying the service straight away
                scheduleRefresh(0)
            },
            onGone = { scheduleRefresh(0) },
        )
        scheduleRefresh(0)
        watchJob = scope.launch {
            _state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collect { watched ->
                if (watched && staleWhileUnwatched) {
                    staleWhileUnwatched = false
                    scheduleRefresh(0)
                }
            }
        }
    }

    /** Stops the codec service process and drops listeners. The repository is unusable afterwards. */
    fun close() {
        debounceJob?.cancel()
        lateJob?.cancel()
        watchJob.cancel()
        privileged.close()
        runCatching { context.unregisterReceiver(a2dpReceiver) }
    }

    override suspend fun refresh() {
        refreshMutex.withLock { withContext(Dispatchers.IO) { doRefresh() } }
    }

    override suspend fun requestPrivilege() {
        if (!privileged.included) throw HeadphoneCommandException(NO_ROUTE_MESSAGE)
        when (privileged.state()) {
            PrivilegeState.UNAVAILABLE -> throw HeadphoneCommandException("Install Shizuku from the Play Store to switch codecs without root.")
            PrivilegeState.SHIZUKU_NOT_RUNNING -> throw HeadphoneCommandException("Shizuku is installed but not running. Open the Shizuku app and start it (Wireless debugging).")
            else -> Unit
        }
        val granted = privileged.request()
        val privilege = privileged.state()
        _state.update { it.copy(privilege = privilege, canSwitch = privilege == PrivilegeState.GRANTED) }
        if (!granted) throw HeadphoneCommandException("Shizuku permission was not granted.")
        serviceReadFailedAt = 0L
        refresh()
    }

    override suspend fun selectCodec(selection: CodecSelection) {
        val privilege = privileged.state()
        if (privilege != PrivilegeState.GRANTED && linkActive) {
            selectViaCompanionLink(selection)
            return
        }
        if (privilege != PrivilegeState.GRANTED) {
            _state.update { it.copy(privilege = privilege, canSwitch = false, viaCompanionLink = false) }
            throw HeadphoneCommandException(
                when {
                    !privileged.included -> NO_ROUTE_MESSAGE
                    privilege == PrivilegeState.UNAVAILABLE -> "Switching codecs needs Shizuku. Install it, or use Developer options → Bluetooth audio codec."
                    else -> CODEC_SWITCH_UNSUPPORTED_MESSAGE
                },
            )
        }
        val name = CodecNames.normalize(selection.codecName)
        val option = _state.value.options.firstOrNull { it.codecName == name }
        if (option != null && !option.isSelectable) {
            throw HeadphoneCommandException("$name isn't supported by both the phone and the headphones right now.")
        }
        val request = CodecRequest.from(selection.copy(codecName = name), codecTypeIds)

        val (address, status) = withContext(Dispatchers.IO) {
            val a2dp = proxies.get() ?: throw HeadphoneCommandException("Bluetooth is off.")
            val device = proxies.pickSink(proxies.connectedDevices(a2dp), preferredAddress())
                ?: throw HeadphoneCommandException("The headphones aren't connected for audio (A2DP).")
            Log.i(TAG, "requesting $request through the codec service")
            val result = try {
                privileged.select(device.address, request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "codec switch through the user service failed", t)
                _state.update { it.copy(lastError = "Codec switch failed: ${A2dpProxyHolder.rootCause(t)}") }
                throw HeadphoneCommandException(
                    "Android refused the codec switch through Shizuku (${t.message ?: t.javaClass.simpleName}). " +
                        "Use Developer options → Bluetooth audio codec instead.",
                    t,
                )
            }
            serviceReadFailedAt = 0L
            device.address to result
        }

        // The service already polled the stack for ~2.5 s; its last status is the answer.
        CodecStatusText.parse(status, address)?.let { applyParsed(it, extraPhoneCodecs = null, error = null) }
        awaitCodec(name, polls = LATE_VERIFY_POLLS)
    }

    /**
     * The experimental path: `setCodecConfigPreference` on our own proxy, allowed by the headset's
     * companion link ([AssociationCodecPath]). A refusal turns the path off for the process and says
     * what still works.
     */
    private suspend fun selectViaCompanionLink(selection: CodecSelection) {
        val name = CodecNames.normalize(selection.codecName)
        val option = _state.value.options.firstOrNull { it.codecName == name }
        if (option != null && !option.isSelectable) {
            throw HeadphoneCommandException("$name isn't supported by both the phone and the headphones right now.")
        }
        val request = CodecRequest.from(selection.copy(codecName = name), codecTypeIds)
        val accepted = withContext(Dispatchers.IO) {
            val a2dp = proxies.get() ?: throw HeadphoneCommandException("Bluetooth is off.")
            val device = proxies.pickSink(proxies.connectedDevices(a2dp), preferredAddress())
                ?: throw HeadphoneCommandException("The headphones aren't connected for audio (A2DP).")
            Log.i(TAG, "requesting $request through the companion link")
            try {
                companionLink.available(device.address) && companionLink.select(a2dp, device, request)
            } catch (e: IllegalArgumentException) {
                throw HeadphoneCommandException("Earshot doesn't know this phone's id for $name.", e)
            }
        }
        if (!accepted) {
            linkActive = false
            _state.update { it.copy(canSwitch = it.privilege == PrivilegeState.GRANTED, viaCompanionLink = false) }
            throw HeadphoneCommandException(
                if (privileged.included) {
                    "Android refused the codec switch through the headset's link. Start Shizuku, or use Developer options → Bluetooth audio codec."
                } else {
                    "Android refused the codec switch through the headset's link. Use Developer options → Bluetooth audio codec instead."
                },
            )
        }
        awaitCodec(name, polls = LATE_VERIFY_POLLS + 1)
    }

    /** Some sinks take a moment to renegotiate; look a few times before giving up. */
    private suspend fun awaitCodec(name: String, polls: Int) {
        if (_state.value.current?.codecName == name) {
            Log.i(TAG, "now streaming $name")
            return
        }
        repeat(polls) {
            delay(VERIFY_INTERVAL_MS)
            refresh()
            if (_state.value.current?.codecName == name) return
        }
        throw HeadphoneCommandException(
            "The phone accepted the request but is still using ${_state.value.current?.codecName ?: "the previous codec"}. " +
                "The headphones may not allow $name at these settings; try another sample rate or Developer options.",
        )
    }

    override suspend fun applyProfile(profile: ConnectionProfile) {
        if (!_state.value.canSwitch && privileged.state() != PrivilegeState.GRANTED) {
            throw HeadphoneCommandException(if (privileged.included) CODEC_SWITCH_UNSUPPORTED_MESSAGE else NO_ROUTE_MESSAGE)
        }
        if (_state.value.options.none { it.isSelectable }) refresh()
        val selection = CodecProfiles.pick(profile, _state.value.options)
            ?: throw HeadphoneCommandException(
                if (_state.value.privilege == PrivilegeState.GRANTED || !privileged.included) {
                    "No switchable codec found yet; make sure the headphones are connected and try again."
                } else {
                    "Profiles need Shizuku to read which codecs the headphones accept."
                },
            )
        selectCodec(selection)
    }

    override fun openDeveloperCodecSettings() {
        // The Developer options page honours the Settings highlight extra; this key is the codec
        // preference in AOSP's development_settings.xml (Samsung keeps the AOSP page for this).
        val dev = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_FRAGMENT_ARG_KEY, CODEC_PREF_KEY)
            putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, CODEC_PREF_KEY) })
        }
        val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (intent in listOf(dev, fallback)) {
            if (intent.resolveActivity(context.packageManager) == null) continue
            try {
                context.startActivity(intent)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "could not open ${intent.action}: $t")
            }
        }
    }

    // ---- refresh ---------------------------------------------------------------------------

    /**
     * Refresh after [delayMs]. The quick refresh and the [late] re-read each keep only their newest
     * request: a new one cancels the pending one, so a burst of broadcasts costs one of each.
     */
    private fun scheduleRefresh(delayMs: Long, late: Boolean = false) {
        if (late) lateJob?.cancel() else debounceJob?.cancel()
        val job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "refresh failed: $t")
            }
        }
        if (late) lateJob = job else debounceJob = job
    }

    private suspend fun doRefresh() {
        val privilege = privileged.state()
        val ready = privilege == PrivilegeState.GRANTED
        val a2dp = proxies.get()
        val device = a2dp?.let { proxies.pickSink(proxies.connectedDevices(it), preferredAddress()) }
        val phoneCodecs = a2dp?.let { proxies.phoneCodecNames(it) } ?: emptyList()

        if (a2dp == null || device == null) {
            linkActive = false
            _state.update {
                it.copy(
                    a2dpConnected = false,
                    current = null,
                    options = CodecProfiles.toOptions(emptyList(), emptyList(), phoneCodecs),
                    privilege = privilege,
                    canSwitch = ready,
                    viaCompanionLink = false,
                )
            }
            return
        }

        var error: String? = null
        // Phone-side codec names from places other than the per-device status: the service's
        // getSupportedCodecTypes (15+) and One UI's dumpsys codecConfigPriorities table.
        var extraPhoneCodecs: List<String> = phoneCodecs
        // Set once below, so a codec tap during a refresh never sees the link as gone in between.
        var readViaLink = false
        val parsed: ParsedA2dpDevice? = run {
            if (ready) {
                readViaService(device.address)?.let { (status, names) ->
                    extraPhoneCodecs = extraPhoneCodecs + names
                    return@run status
                }
                val dump = privileged.exec("dumpsys bluetooth_manager")
                dump.getOrNull()?.let { text ->
                    val parsedDump = DumpsysCodecParser.parse(text)
                    extraPhoneCodecs = extraPhoneCodecs + parsedDump.phoneCodecNames
                    parsedDump.forDevice(device.address)?.let {
                        // error stays null, so a stale lastError from an earlier failure is cleared below.
                        Log.i(TAG, "codec read via dumpsys: ${it.current?.codecName ?: "no current codec"}")
                        return@run it
                    }
                    error = "Shizuku works, but the codec section wasn't found in dumpsys output."
                    Log.w(TAG, "dumpsys bluetooth_manager ran (${text.length} chars) but has no codec section for the headphones")
                }
                dump.exceptionOrNull()?.let { error = "Shizuku shell failed: ${it.message}" }
            }
            if (companionLink.available(device.address)) {
                companionLink.readStatus(a2dp, device)?.let { CodecStatusText.parse(it, device.address) }?.let {
                    Log.i(TAG, "codec read through the companion link: ${it.current?.codecName ?: "no current codec"}")
                    readViaLink = true
                    return@run it
                }
            }
            proxies.codecStatusLegacy(a2dp, device)?.let { CodecStatusText.parse(it.toString(), device.address) }
                ?: broadcastStatus[device.address.uppercase()]
        }
        linkActive = readViaLink

        if (parsed != null) {
            applyParsed(parsed, extraPhoneCodecs, error, privilege)
        } else {
            lastExtraPhoneCodecs = extraPhoneCodecs
            _state.update {
                it.copy(
                    a2dpConnected = true,
                    current = publicFallbackInfo(),
                    options = CodecProfiles.toOptions(emptyList(), emptyList(), extraPhoneCodecs),
                    privilege = privilege,
                    canSwitch = ready,
                    viaCompanionLink = false,
                    lastError = error,
                )
            }
        }
    }

    /** Status string + phone codec names from the user service, or null (logged) when it can't answer. */
    private suspend fun readViaService(address: String): Pair<ParsedA2dpDevice, List<String>>? = try {
        // After a failure (bind timeout, refused call) give dumpsys the next refreshes rather than
        // making each one wait for the service again; a codec switch still tries the service.
        val sinceFailure = SystemClock.elapsedRealtime() - serviceReadFailedAt
        if (serviceReadFailedAt != 0L && sinceFailure < SERVICE_READ_BACKOFF_MS) return null
        val (status, names) = privileged.readStatus(address)
        val parsed = CodecStatusText.parse(status, address)
        if (parsed == null) {
            Log.w(TAG, "codec service status didn't parse (${status.length} chars): ${status.take(200)}")
            null
        } else {
            Log.i(TAG, "codec read via user service: ${parsed.current?.codecName ?: "no current codec"}, ${parsed.selectable.size} selectable")
            serviceReadFailedAt = 0L
            parsed to names
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w(TAG, "codec service read failed, trying dumpsys: ${t.message ?: t}")
        serviceReadFailedAt = SystemClock.elapsedRealtime()
        null
    }

    /**
     * Publishes a parsed status. [extraPhoneCodecs] null (after a switch) reuses the names from the
     * last refresh; [privilege] null keeps the current one.
     */
    private fun applyParsed(parsed: ParsedA2dpDevice, extraPhoneCodecs: List<String>?, error: String?, privilege: PrivilegeState? = null) {
        codecTypeIds = codecTypeIds + (parsed.selectable + parsed.local + listOfNotNull(parsed.current))
            .mapNotNull { c -> c.codecType?.let { c.codecName to it } }
            .toMap()
        val extras = extraPhoneCodecs?.also { lastExtraPhoneCodecs = it } ?: lastExtraPhoneCodecs
        val current = parsed.current?.let(CodecProfiles::toCodecInfo) ?: publicFallbackInfo()
        val options: List<CodecOption> = CodecProfiles.toOptions(parsed.selectable, parsed.local, extras)
        _state.update {
            val p = privilege ?: it.privilege
            it.copy(
                a2dpConnected = true,
                current = current,
                // A post-switch status without capability lists must not wipe the options we had.
                options = if (extraPhoneCodecs == null && parsed.selectable.isEmpty()) it.options else options,
                privilege = p,
                canSwitch = p == PrivilegeState.GRANTED || linkActive,
                viaCompanionLink = p != PrivilegeState.GRANTED && linkActive,
                lastError = error,
            )
        }
    }

    /**
     * Without any privileged read the codec itself is unknowable from an app on Android 12+; the
     * audio framework still tells us the negotiated sample rate / channel layout when it reports a
     * single value, which is better than an empty card.
     */
    private fun publicFallbackInfo(): CodecInfo? {
        val dev = proxies.a2dpOutputDevice() ?: return null
        val rate = dev.sampleRates.singleOrNull()
        val channels = dev.channelCounts.singleOrNull()?.let { if (it == 1) "Mono" else "Stereo" }
        if (rate == null && channels == null) return null
        return CodecInfo(CodecNames.UNKNOWN, sampleRateHz = rate, channelMode = channels)
    }

    companion object {
        /** `BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED` / `BluetoothCodecStatus.EXTRA_CODEC_STATUS`. */
        private const val ACTION_CODEC_CONFIG_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
        private const val EXTRA_CODEC_STATUS = "android.bluetooth.extra.CODEC_STATUS"

        private const val TAG = "Earshot/Codec"

        /**
         * The Google Play build's answer when nothing can switch: it has no Shizuku (B8), so only
         * the companion link (Android 13+) or Developer options are left.
         */
        private const val NO_ROUTE_MESSAGE =
            "This version of Earshot switches codecs through a linked headset (Tools → Controls → Universal remote, Android 13 or later). " +
                "Otherwise use Developer options → Bluetooth audio codec."
        private const val DEBOUNCE_MS = 700L
        private const val LATE_REFRESH_MS = 3_500L
        private const val LATE_VERIFY_POLLS = 2
        private const val SERVICE_READ_BACKOFF_MS = 30_000L
        private const val VERIFY_INTERVAL_MS = 700L
        private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
        private const val CODEC_PREF_KEY = "bluetooth_select_a2dp_codec"
    }
}
