package dev.earshot.service

import dev.earshot.bt.BmapConnection
import dev.earshot.bt.BmapRequester
import dev.earshot.bt.ReplyMode
import dev.earshot.core.ButtonAction
import dev.earshot.core.DeviceInfo
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.core.PairedDevice
import dev.earshot.core.Playback
import dev.earshot.core.PlaybackState
import dev.earshot.core.Sidetone
import dev.earshot.core.TransportControl
import dev.earshot.core.VoicePrompts
import dev.earshot.protocol.BmapError
import dev.earshot.protocol.BmapException
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.qcultra2.AppAddress
import dev.earshot.protocol.qcultra2.AudioControl
import dev.earshot.protocol.qcultra2.AudioSettings
import dev.earshot.protocol.qcultra2.AudioSourceQuery
import dev.earshot.protocol.qcultra2.AudioStatusQuery
import dev.earshot.protocol.qcultra2.AvailableToConnect
import dev.earshot.protocol.qcultra2.AvailableToConnectCode
import dev.earshot.protocol.qcultra2.Battery
import dev.earshot.protocol.qcultra2.BoseModel
import dev.earshot.protocol.qcultra2.ButtonEvent
import dev.earshot.protocol.qcultra2.ButtonEventPush
import dev.earshot.protocol.qcultra2.ButtonId
import dev.earshot.protocol.qcultra2.ProductForm
import dev.earshot.protocol.qcultra2.Buttons
import dev.earshot.protocol.qcultra2.ChargerDetect
import dev.earshot.protocol.qcultra2.Cnc
import dev.earshot.protocol.qcultra2.CncState
import dev.earshot.protocol.qcultra2.CurrentMode
import dev.earshot.protocol.qcultra2.CustomName
import dev.earshot.protocol.qcultra2.DeviceExtendedInfoQuery
import dev.earshot.protocol.qcultra2.DeviceInfoQuery
import dev.earshot.protocol.qcultra2.Eq
import dev.earshot.protocol.qcultra2.EqBandId
import dev.earshot.protocol.qcultra2.FavoritesCodec
import dev.earshot.protocol.qcultra2.Latency
import dev.earshot.protocol.qcultra2.LeAudioCheck
import dev.earshot.protocol.qcultra2.LeAudioControl
import dev.earshot.protocol.qcultra2.Mac
import dev.earshot.protocol.qcultra2.MicMute
import dev.earshot.protocol.qcultra2.ModeConfig
import dev.earshot.protocol.qcultra2.ModeConfigWrite
import dev.earshot.protocol.qcultra2.ModeConfigs
import dev.earshot.protocol.qcultra2.ModeLayout
import dev.earshot.protocol.qcultra2.ModePrompt
import dev.earshot.protocol.qcultra2.ModeReset
import dev.earshot.protocol.qcultra2.ModesCapabilities
import dev.earshot.protocol.qcultra2.Multipoint
import dev.earshot.protocol.qcultra2.Notifications
import dev.earshot.protocol.qcultra2.PairedDeviceList
import dev.earshot.protocol.qcultra2.PairingMode
import dev.earshot.protocol.qcultra2.Power
import dev.earshot.protocol.qcultra2.PrintConsole
import dev.earshot.protocol.qcultra2.ProductInfo
import dev.earshot.protocol.qcultra2.QcUltra2
import dev.earshot.protocol.qcultra2.Replies
import dev.earshot.protocol.qcultra2.Routing
import dev.earshot.protocol.qcultra2.StandbyTimer
import dev.earshot.protocol.qcultra2.Toggle
import dev.earshot.protocol.qcultra2.TouchControls
import dev.earshot.protocol.qcultra2.Volume
import dev.earshot.protocol.qcultra2.OperationMode as OperationModeCodec
import dev.earshot.protocol.qcultra2.Sidetone as SidetoneCodec
import dev.earshot.protocol.qcultra2.VoicePrompts as VoicePromptsCodec
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * [HeadphoneProtocol] for the QC Ultra Headphones (2nd Gen) and its relatives (see [BoseModel]):
 * turns repository commands into BMAP requests and the replies back into [HeadphoneState].
 *
 * Other models: the model is read from `[0.3]` on connect. The differences are small and data
 * driven: `[31.6]` record length (detected per frame), which slots are built in or editable,
 * reads known to be absent (skipped), and — where `[31.10]` does not exist (QC45, QC Headphones)
 * — noise changes are written into the active custom mode instead.
 *
 * Only the QC Ultra 2 is verified on our hardware, so every catalogue fact is a first guess that
 * the connection corrects (users of the QC Ultra 1st Gen reported it "not working right" since
 * 0.2.0 and nobody here has one): `[31.10]` refused or silent → slot fallback for noise; the
 * `[31.6]` write follows the STATUS length actually seen; block 31 refused as a whole → no
 * modes; functions that stay silent while `[0.1]` answers → skipped for the connection.
 *
 * Operators as the Bose app 13.3.4 uses them: GET anywhere, SET_GET for every setting, START for
 * actions. Error 5 is "operator not implemented for this function", never a login gate. Byte
 * layouts and wire tables live in `:protocol`; this class only sequences requests and maps state
 * (see `StateMapping.kt`).
 *
 * Live updates: [onConnected] subscribes to the headphones' own pushes with `[9.2]` (as the Bose
 * app does), after which [onUnsolicited] keeps the state current without polling.
 *
 * @param localAddress this phone's Bluetooth address, if the caller knows it, for
 *   [PairedDevice.isThisPhone]. Android has returned the constant `02:00:00:00:00:00` from
 *   `BluetoothAdapter.getAddress()` since 6.0, so the real source is the headphones' `[4.9]`
 *   AppAddress; this is only a fallback when that read fails.
 * @param readTimeoutMs per-request timeout for the GETs in [refresh]; writes use the
 *   connection default.
 * @param clock wall clock for [dev.earshot.core.ButtonPress.atMillis].
 */
class QcUltra2Protocol(
    private val localAddress: () -> String? = { null },
    private val readTimeoutMs: Long = REFRESH_READ_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : HeadphoneProtocol {

    /**
     * What this connection learned: the catalogue entry (QC Ultra 2 until `[0.3]` says otherwise)
     * corrected by `[31.2]`, the `[31.6]` STATUS length, `[31.8]` and `[1.5]`. One connection at a time.
     */
    @Volatile
    private var facts = ConnectionFacts()

    /** The connected model's catalogue entry. */
    private val model: BoseModel get() = facts.model

    /** `[31.2]` was asked on this connection (it does not change while connected). */
    @Volatile
    private var modesCapabilitiesRead = false

    /** `[1.5]` SET_GET was refused on this connection: the level is read-only (or goes through the slots). */
    @Volatile
    private var directCncRefused = false

    /** The last full STATUS per slot, so a rewrite keeps the slot's own prompt and name bytes. */
    private val slotConfigs = ConcurrentHashMap<Int, ModeConfig>()

    /** True once [detectModel] ran on this connection; the product id cannot change mid-session. */
    @Volatile
    private var identified = false

    /** `[0.2]` AllFblocks of this connection; null = unknown (ask everything). */
    @Volatile
    private var presentBlocks: Set<Int>? = null

    /**
     * `[31.10]` turned out to be missing on this connection (FuncNotSupp / FblockNotSupp /
     * OpNotSupp, or silence) although the catalogue expected it. The catalogue entry is a guess
     * on every model but the QC Ultra 2 (the QC Ultra 1st Gen in particular), so the live answer
     * wins and noise edits fall back to rewriting the active custom slot.
     */
    @Volatile
    private var audioSettingsAbsent = false

    /**
     * Listening modes exist on this connection: block 31 is neither missing from `[0.2]` nor
     * refused as a whole. bosectl #44: a unit answers FblockNotSupp to every block-31 request
     * yet still pushes `[31.3]`; there the mode UI is hidden instead of failing on every tap.
     */
    private val modesAvailable: Boolean get() = blockAvailable(AUDIO_MODES_BLOCK)

    /** `[31.10]` can be read and written on this connection. */
    private val liveAudio: Boolean
        get() = model.hasLiveAudioSettings && !audioSettingsAbsent && modesAvailable && !model.isUnsupported

    /**
     * `[1.5]` SET_GET is the way to change the level (docs/bose-models.md §E2): a model catalogued
     * for it (QC Earbuds 2020, NC 700) or any unit without listening modes, as long as `[1.5]` gave
     * a usable step count and the write has not been refused on this connection.
     */
    private val directCncUsable: Boolean
        get() = model.hasNoiseCancelling && !directCncRefused && facts.cnc?.isValid == true &&
            (!modesAvailable || model.directCnc)

    /** How noise edits reach the headphones on this connection (see [NoisePath]). */
    private val noisePath: NoisePath
        get() = when {
            model.isUnsupported -> NoisePath.NONE
            liveAudio -> NoisePath.LIVE
            directCncUsable -> NoisePath.DIRECT
            modesAvailable -> NoisePath.SLOTS
            model.hasNoiseCancelling -> NoisePath.READ_ONLY
            else -> NoisePath.NONE
        }

    /** `[1.5]` is worth reading: the direct path may apply, or it is the only place the level shows. */
    private val wantsCnc: Boolean
        get() = !liveAudio && model.hasNoiseCancelling && !model.isUnsupported &&
            (!modesAvailable || (model.directCnc && !directCncRefused))

    /** What the UI may offer, from the catalogue corrected by what this connection showed. */
    private fun capabilities() = facts.capabilities(noisePath, modesAvailable)

    /** Recognised-but-unsupported products (QC35, the 2024 QC Earbuds) are read, never written. */
    private fun requireDriven() {
        if (model.isUnsupported) throw HeadphoneCommandException(model.supportNote ?: UNSUPPORTED_MESSAGE)
    }

    private fun markAudioSettingsAbsent(why: String) {
        if (!model.hasLiveAudioSettings || audioSettingsAbsent) return
        audioSettingsAbsent = true
        ProtocolLog.i("[31.10] AudioSettings absent on this firmware ($why): noise edits go through the active custom mode")
    }

    /** Errors that mean "this firmware has no `[31.10]`", as opposed to a bad value. */
    private fun BmapError?.meansAbsent() =
        this == BmapError.FUNC_NOT_SUPPORTED || this == BmapError.FBLOCK_NOT_SUPPORTED || this == BmapError.OP_NOT_SUPPORTED

    private fun requireModes() {
        if (!modesAvailable) throw HeadphoneCommandException(MODES_UNAVAILABLE_MESSAGE)
    }

    /**
     * Reads this connection's firmware answered FuncNotSupp to. Later refreshes skip them: on the
     * older models that is a dozen requests per refresh that can never succeed.
     */
    private val refusedReads: MutableSet<Pair<FunctionAddress, BmapOperator>> = ConcurrentHashMap.newKeySet()

    /** Blocks this connection's firmware answered FblockNotSupp to (bosectl #44: all of block 31). */
    private val refusedBlocks: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Reads that stayed silent while `[0.1]` still answered: absent on this firmware (see [TolerantReader]). */
    private val silentReads: MutableSet<Pair<FunctionAddress, BmapOperator>> = ConcurrentHashMap.newKeySet()

    private fun startSession() {
        facts = ConnectionFacts()
        modesCapabilitiesRead = false
        directCncRefused = false
        slotConfigs.clear()
        identified = false
        presentBlocks = null
        refusedReads.clear()
        refusedBlocks.clear()
        silentReads.clear()
        audioSettingsAbsent = false
    }

    // ── Connect ──────────────────────────────────────────────────────────────────────────

    /**
     * Once per connection: read `[0.2]`, enable `[9.2]` pushes for the Bose app's block set
     * limited to it (wolverine: `09 02 02 05 01 80 04 22 b7`), then run the `[4.19]` LE Audio
     * check START the Bose app also runs on connect. Nothing here is fatal: a refused `[9.2]`
     * leaves [HeadphoneState.livePushEnabled] false and the repository polls the battery.
     */
    override suspend fun onConnected(c: BmapRequester, current: HeadphoneState): HeadphoneState {
        startSession()
        val r = TolerantReader(c)
        val known = detectModel(r, current)
        // QC35 and the like: recognised, not driven. No subscription (their [9.2] is untested);
        // the repository polls the battery instead.
        if (model.isUnsupported) return known.copy(livePushEnabled = false)
        var blocks: Set<Int>? = null
        r.get(QcUltra2.ALL_FUNCTION_BLOCKS) { blocks = ProductInfo.parseAllFunctionBlocks(it.payload) }
        presentBlocks = blocks
        val wanted = Notifications.blocksToEnable(blocks)
        var pushing = false
        r.request(BmapPacket.setGet(QcUltra2.NOTIFY_BY_FBLOCK, Notifications.byFblockPayload(wanted))) {
            val echoed = Notifications.parseByFblock(it.payload)
            ProtocolLog.i("[9.2] notifications on for blocks ${echoed ?: wanted} (asked $wanted)")
            pushing = true
        }
        if (!pushing) ProtocolLog.w("[9.2] notification subscribe failed; falling back to a ${BATTERY_POLL_MS / 1000} s battery poll")
        var s = known.copy(livePushEnabled = pushing)
        // [4.19] is START-only (GET answers error 5 on fw 10.12). Untested over RFCOMM: log the answer.
        r.request(BmapPacket.start(QcUltra2.LE_AUDIO_CHECK)) { reply ->
            ProtocolLog.i("[4.19] LE Audio check answered ${reply.operator} ${reply.payload.size} bytes")
            LeAudioCheck.parse(reply.payload)?.let { s = s.copy(lePreference = it.toLePreference()) }
        }
        return s
    }

    /** `[2.2]` only; the repository calls it every 60 s while [HeadphoneState.livePushEnabled] is false. */
    override suspend fun pollBattery(c: BmapRequester, current: HeadphoneState): HeadphoneState {
        var s = current
        TolerantReader(c).get(QcUltra2.BATTERY) { s = s.withBattery(Battery.parse(it.payload)) }
        return s
    }

    /**
     * `[0.3]` product id → [model]; for an id Earshot has no profile for, `[0.15]`
     * OriginalProductName decides; else the QC Ultra 2 defaults stay (with the family taken from
     * the name, so the header still says what it is). docs/bose-models.md §E1.
     */
    private suspend fun detectModel(r: TolerantReader, current: HeadphoneState): HeadphoneState {
        var info = current.info ?: DeviceInfo()
        r.get(QcUltra2.PRODUCT_ID_VARIANT) { info = info.copy(productId = ProductInfo.parseProductIdVariant(it.payload)?.productId) }
        var detected = BoseModel.fromProductId(info.productId)
        if (detected == null) {
            r.get(QcUltra2.PRODUCT_NAME) { info = info.copy(productName = ProductInfo.parseString(it.payload).ifBlank { null }) }
            detected = BoseModel.fromName(info.productName)
        }
        facts = facts.copy(
            model = detected ?: BoseModel.DEFAULT,
            catalogued = detected != null,
            unknownProductName = if (detected == null) info.productName else null,
        )
        identified = true
        ProtocolLog.i("model ${model.name} (product id ${info.productId?.let { "0x%04x".format(it) } ?: "unknown"}, " +
            "${if (detected == null) "not catalogued" else if (model.verified) "verified" else "unverified"} layout, ${model.tier})")
        return current.copy(
            info = info.copy(modelName = detected?.displayName ?: info.modelName, family = facts.family, generation = facts.generation),
            capabilities = capabilities(),
        )
    }

    // ── Refresh ──────────────────────────────────────────────────────────────────────────

    /**
     * Reads everything, one request at a time, tolerating individual failures: a GET that
     * errors or times out leaves its field as it was and is logged under `Earshot/Protocol`.
     * The whole refresh only fails when the link is gone or the headphones stop answering
     * altogether ([MAX_CONSECUTIVE_TIMEOUTS] silent reads in a row).
     *
     * Order: battery `[2.2]`, product id `[0.3]` (once per connection), charger `[2.5]`, mic mute
     * `[2.21]`, operation mode `[2.16]`, firmware `[0.5]`, serial `[0.7]`, product name `[0.15]`,
     * MAC `[0.6]`, BMAP version `[0.1]`, LE Audio support `[0.23]`, custom name `[1.2]`, EQ `[1.7]`,
     * auto-off `[1.4]`, sidetone `[1.11]`, multipoint `[1.10]`, auto play/pause `[1.24]`, auto
     * answer `[1.27]`, voice prompts `[1.3]`, shortcut button `[1.9]`, touch `[1.34]`, LE Audio
     * `[1.37]`, noise `[31.10]`, mode capabilities `[31.2]` (once), current mode `[31.3]`, mode dump
     * `[31.6]` START (fallback `[31.1]` START), favourites `[31.8]`, CNC `[1.5]` (without
     * `[31.10]`), devices (`[4.4]`, `[4.9]`, `[4.5]` per unnamed MAC, `[4.6]` per MAC, `[5.1]`),
     * `[4.18]`, transport `[5.3]`, play state `[5.4]`, volume `[5.5]`, latency `[5.7]`.
     *
     * An unsupported product (QC35, docs/bose-models.md §B) stops after the battery and the
     * product info.
     */
    override suspend fun refresh(c: BmapRequester, current: HeadphoneState): HeadphoneState {
        val r = TolerantReader(c)
        var s = current

        // Status block. [2.3] is AuxCableDetection, not charging: charging is [2.5] ChargerDetect.
        r.get(QcUltra2.BATTERY) { s = s.withBattery(Battery.parse(it.payload)) }
        // Product id once per connection: the model drives everything below.
        if (!identified) s = detectModel(r, s)
        if (model.isUnsupported) return refreshUnsupported(r, s)
        r.get(QcUltra2.CHARGER_DETECT) { s = s.copy(isCharging = ChargerDetect.parse(it.payload)) }
        r.get(QcUltra2.MIC_MUTE) { s = s.copy(micMuted = MicMute.parse(it.payload)) }
        r.get(QcUltra2.OPERATION_MODE) { s = s.copy(operationMode = OperationModeCodec.parse(it.payload)?.toOperationMode()) }

        // Product info
        var info = s.info ?: DeviceInfo()
        r.get(QcUltra2.FIRMWARE_VERSION) { info = info.copy(firmwareVersion = ProductInfo.parseFirmwareVersion(it.payload).ifBlank { null }) }
        r.get(QcUltra2.SERIAL_NUMBER) { info = info.copy(serialNumber = ProductInfo.parseString(it.payload).ifBlank { null }) }
        r.get(QcUltra2.PRODUCT_NAME) { info = info.copy(productName = ProductInfo.parseString(it.payload).ifBlank { null }) }
        r.get(QcUltra2.MAC_ADDRESS) { info = info.copy(bluetoothAddress = ProductInfo.parseMac(it.payload)) }
        r.get(QcUltra2.BMAP_VERSION) { info = info.copy(bmapVersion = ProductInfo.parseBmapVersion(it.payload)) }
        r.get(QcUltra2.LE_AUDIO_SUPPORTED) { s = s.copy(leAudioSupported = ProductInfo.parseLeAudioSupported(it.payload)) }
        r.get(QcUltra2.CUSTOM_NAME) { info = info.copy(customName = CustomName.parse(it.payload)?.ifBlank { null }) }
        s = s.copy(info = info)

        // Settings block
        r.get(QcUltra2.EQ) { s = s.withEq(Eq.parse(it.payload)) }
        // [1.4] auto-off: FuncNotSupp on wolverine fw 10.12, so the setting stays hidden there.
        r.get(QcUltra2.STANDBY_TIMER) { StandbyTimer.parse(it.payload)?.let { m -> s = s.copy(autoOffMinutes = m) } }
        r.get(QcUltra2.SIDETONE) {
            SidetoneCodec.parse(it.payload)?.let { st -> s = s.copy(sidetone = st.toSidetone(), sidetonePersistent = st.persist) }
        }
        r.get(QcUltra2.MULTIPOINT) { s = s.copy(multipointEnabled = Multipoint.parse(it.payload)) }
        r.get(QcUltra2.AUTO_PLAY_PAUSE) { s = s.copy(autoPlayPause = Toggle.parse(it.payload)) }
        r.get(QcUltra2.AUTO_ANSWER) { s = s.copy(autoAnswer = Toggle.parse(it.payload)) }
        r.get(QcUltra2.VOICE_PROMPTS) { s = s.copy(voicePrompts = VoicePromptsCodec.parse(it.payload)?.toVoicePrompts()) }
        if (model.formFactor == ProductForm.HEADPHONES) {
            r.get(QcUltra2.BUTTONS) { s = s.withButtonRecords(listOfNotNull(Buttons.parse(it.payload)?.toButtonAssignment())) }
        } else {
            // Earbuds may answer with one STATUS per bud (right 0x03, left 0x04): collect them all.
            // Headphones keep the single read, so the QC Ultra 2 refresh costs no quiet gap.
            r.multi(BmapPacket.get(QcUltra2.BUTTONS)) { frames ->
                val records = frames.filter { it.matches(QcUltra2.BUTTONS) && it.operator == BmapOperator.STATUS }
                    .mapNotNull { Buttons.parse(it.payload)?.toButtonAssignment() }
                if (records.isNotEmpty()) s = s.withButtonRecords(records)
            }
        }
        r.get(QcUltra2.DISABLE_CAPTOUCH) { s = s.copy(touchControlsEnabled = TouchControls.parseEnabled(it.payload)) }
        r.get(QcUltra2.LE_AUDIO_CONTROL) { s = s.copy(leAudioEnabled = LeAudioControl.parse(it.payload)) }

        // AudioModes block. Every step re-checks modesAvailable: the first FblockNotSupp on block 31
        // ends the block for this connection (one ERROR, not one per function and refresh).
        if (liveAudio) {
            val outcome = r.get(QcUltra2.AUDIO_SETTINGS) { s = s.copy(noise = AudioSettings.parse(it.payload)?.toNoiseSettings()) }
            when {
                // Silence counts only on unverified models (see setNoise); errors count on all.
                outcome == Outcome.SILENT && !model.verified -> markAudioSettingsAbsent("no reply")
                outcome == Outcome.REFUSED && r.lastError.meansAbsent() -> markAudioSettingsAbsent("${r.lastError}")
            }
        }
        // [31.2] once per connection: preset and custom counts and the feature flags, which beat
        // the catalogue (docs/bose-models.md §E3). Older firmware may lack it; the catalogue stays.
        if (modesAvailable && !modesCapabilitiesRead) {
            modesCapabilitiesRead = true
            r.get(QcUltra2.MODES_CAPABILITIES) { reply ->
                ModesCapabilities.parse(reply.payload)?.let { caps ->
                    facts = facts.copy(modes = caps)
                    ProtocolLog.i("[31.2] ${caps.boseModes} built-in + ${caps.userModes} custom modes, $caps")
                }
            }
        }
        if (modesAvailable) r.get(QcUltra2.CURRENT_MODE) { s = s.copy(currentModeIndex = CurrentMode.parse(it.payload)) }
        if (modesAvailable) {
            val configs = readModeConfigs(r)
            var favorites: Set<Int>? = null
            if (modesAvailable) r.get(QcUltra2.FAVORITES) { reply ->
                FavoritesCodec.parse(reply.payload)?.let { f ->
                    favorites = f.indices
                    // Byte 0 is the firmware's own slot total: trust it over the model default.
                    if (f.totalModes in 1..QcUltra2.MODE_SLOT_COUNT) facts = facts.copy(favoritesTotal = f.totalModes)
                }
            }
            s = s.copy(modes = buildModeList(configs, favorites, s.modes, facts))
        }
        // No modes on this firmware (bosectl #44): a pushed [31.3] index may still be in the state;
        // the list stays empty and the UI hides the carousel.
        if (!modesAvailable) s = s.copy(modes = emptyList())
        // Without [31.10]: [1.5] where the direct path may apply or nothing else shows the level.
        if (wantsCnc) r.get(QcUltra2.CNC) { reply -> Cnc.parse(reply.payload)?.takeIf { it.isValid }?.let { facts = facts.copy(cnc = it) } }
        s = when (noisePath) {
            NoisePath.DIRECT, NoisePath.READ_ONLY -> facts.cnc?.toNoiseSettings()?.let { s.copy(noise = it) } ?: s
            // The live settings are the active slot's.
            NoisePath.SLOTS -> s.copy(noise = s.currentMode?.settings ?: s.noise)
            NoisePath.LIVE, NoisePath.NONE -> s
        }
        s = s.copy(capabilities = capabilities())

        // Devices
        s = readPairedDevices(r, s)
        r.get(QcUltra2.AVAILABLE_TO_CONNECT) { reply ->
            s = s.copy(availableToConnect = when (AvailableToConnect.parse(reply.payload)) {
                AvailableToConnectCode.AVAILABLE -> true
                AvailableToConnectCode.NOT_AVAILABLE -> false
                else -> null
            })
        }

        // AudioManagement block
        r.get(QcUltra2.AUDIO_CONTROL) { s = s.copy(supportedTransportControls = transportControlsFrom(AudioControl.parseSupported(it.payload))) }
        r.get(QcUltra2.AUDIO_STATUS) { reply -> AudioStatusQuery.parse(reply.payload)?.toPlayback()?.let { s = s.copy(playback = it) } }
        r.get(QcUltra2.VOLUME) { reply -> Volume.parse(reply.payload)?.let { s = s.withVolume(it) } }
        r.get(QcUltra2.LATENCY) { reply -> Latency.parse(reply.payload)?.let { s = s.withLatency(it) } }
        return s
    }

    /**
     * A recognised but unsupported product (QC35 / QC35 II: Bose Connect era, not in the Bose app's
     * BMAP whitelist, docs/bose-models.md §B): the battery is already read; add the product info
     * for the device card and stop. Nothing else is asked, so firmware that answers oddly to the
     * newer functions cannot trip the connection.
     */
    private suspend fun refreshUnsupported(r: TolerantReader, current: HeadphoneState): HeadphoneState {
        var info = current.info ?: DeviceInfo()
        r.get(QcUltra2.FIRMWARE_VERSION) { info = info.copy(firmwareVersion = ProductInfo.parseFirmwareVersion(it.payload).ifBlank { null }) }
        r.get(QcUltra2.PRODUCT_NAME) { info = info.copy(productName = ProductInfo.parseString(it.payload).ifBlank { null }) }
        r.get(QcUltra2.MAC_ADDRESS) { info = info.copy(bluetoothAddress = ProductInfo.parseMac(it.payload)) }
        return current.copy(info = info, modes = emptyList(), noise = null, capabilities = capabilities())
    }

    /**
     * `[31.6]` START with no payload dumps every slot as 48-byte STATUS frames (the notes:
     * "Returns all mode configs as STATUS messages"); MULTI collects them across reads. `[31.1]`
     * GetAll answers PROCESSING and then streams the same frames among other block-31 state, so
     * it is the fallback when `[31.6]` yields nothing.
     */
    private suspend fun readModeConfigs(r: TolerantReader): Map<Int, ModeConfig> {
        var configs: Map<Int, ModeConfig> = emptyMap()
        // bosectl dumps the older models (and the Ultra Open) with [31.1]; [31.6] START is
        // untested there, so ask in the order known to work for the model.
        val dumps = if (model.dumpsModesWithGetAll) listOf(QcUltra2.MODES_GET_ALL, QcUltra2.MODE_CONFIG)
        else listOf(QcUltra2.MODE_CONFIG, QcUltra2.MODES_GET_ALL)
        for (address in dumps) {
            r.multi(BmapPacket.start(address)) { configs = Replies.collectModeConfigs(it) }
            if (configs.isNotEmpty()) break
            ProtocolLog.w("$address dump returned no mode slots")
        }
        if (configs.size < facts.slotCount) ProtocolLog.w("mode dump covered ${configs.size}/${facts.slotCount} slots")
        configs.values.forEach(::noteLayout)
        return configs
    }

    /**
     * `[4.4]` lists the paired addresses with a connected bitmask; `[4.9]` AppAddress says which
     * one is this phone; `[4.5]` (a GET *with* the MAC as payload) names each one — names already
     * known are reused so a refresh costs one `[4.5]` per *new* device; `[4.6]` gives the
     * Classic/LE profile bits per device; `[5.1]` says which one is streaming right now.
     */
    private suspend fun readPairedDevices(r: TolerantReader, current: HeadphoneState): HeadphoneState {
        val previous = current.pairedDevices
        var list: dev.earshot.protocol.qcultra2.PairedDevices? = null
        r.get(QcUltra2.PAIRED_DEVICES) { list = PairedDeviceList.parse(it.payload) }
        var appAddress: String? = null
        r.get(QcUltra2.APP_ADDRESS) { appAddress = AppAddress.parse(it.payload) }
        val phone = appAddress ?: current.thisPhoneAddress
        val listed = list ?: return current.copy(thisPhoneAddress = phone, pairedDevices = previous.withThisPhone(phone))
        val known = previous.associateBy { it.address.uppercase() }
        val fallbackMine = localAddress()
        // [4.6] with a MAC payload is untested on hardware: stop asking after the first refusal or
        // silence so a firmware that ignores it costs one timeout per refresh, not one per device.
        var extendedInfoWorks = true
        val devices = listed.macs.take(MAX_PAIRED_DEVICES).map { mac ->
            val before = known[mac.uppercase()]
            var name = before?.name
            var isLocal: Boolean? = null
            var isBose = before?.isBoseProduct
            if (name == null) {
                r.request(DeviceInfoQuery.request(mac)) { reply ->
                    DeviceInfoQuery.parse(reply.payload)?.let { di ->
                        name = di.name
                        isLocal = di.isLocalDevice
                        isBose = di.isBoseProduct
                    }
                }
            }
            var profiles = before?.profiles
            if (extendedInfoWorks) {
                extendedInfoWorks = r.request(DeviceExtendedInfoQuery.request(mac)) { reply ->
                    DeviceExtendedInfoQuery.parse(reply.payload)?.let { profiles = it.toDeviceProfiles() }
                } == Outcome.OK
            }
            val isThisPhone = when {
                phone != null -> phone.equals(mac, ignoreCase = true)
                fallbackMine != null -> fallbackMine.equals(mac, ignoreCase = true)
                isLocal != null -> isLocal == true
                else -> before?.isThisPhone ?: false
            }
            PairedDevice(
                address = mac,
                name = name,
                isThisPhone = isThisPhone,
                isConnected = listed.isConnected(mac),
                isBoseProduct = isBose,
                profiles = profiles,
            )
        }
        var active: String? = null
        r.get(QcUltra2.AUDIO_SOURCE) { active = AudioSourceQuery.parse(it.payload)?.mac }
        return current.copy(
            thisPhoneAddress = phone,
            pairedDevices = devices.withActiveSource(active ?: known.values.firstOrNull { it.isActiveSource }?.address),
        )
    }

    // ── Listening modes ──────────────────────────────────────────────────────────────────

    /**
     * `[31.3]` START `[index, announce=0]`. The reply is RESULT, or on some firmware PROCESSING
     * followed by nothing on `[31.3]` (the connection swallows PROCESSING while waiting for the
     * real answer, so that case surfaces as a timeout); then the switch is verified with a GET
     * instead of being reported as a failure. `[31.10]` is re-read afterwards so the noise panel
     * shows the mode's settings.
     */
    override suspend fun setMode(c: BmapRequester, current: HeadphoneState, index: Int): HeadphoneState {
        requireDriven()
        requireModes()
        if (!CurrentMode.isSavedSlot(index)) throw HeadphoneCommandException("There is no listening mode in slot $index")
        val slot = current.modes.firstOrNull { it.index == index }
        if (slot != null && !slot.isConfigured) {
            throw HeadphoneCommandException("Slot $index is empty: save a custom mode there before selecting it")
        }
        val reply = try {
            c.request(BmapPacket.start(QcUltra2.CURRENT_MODE, CurrentMode.startPayload(index, announce = false))).firstOrNull()
        } catch (e: HeadphoneCommandException) {
            if (!c.isOpen) throw e
            ProtocolLog.w("[31.3] START gave no RESULT, verifying with a GET: ${e.message}")
            null
        }
        if (reply != null && reply.isError) throw BmapException.from(reply)
        if (reply == null) {
            val now = parsed(QcUltra2.CURRENT_MODE) { CurrentMode.parse(c.get(QcUltra2.CURRENT_MODE).payload) }
            if (now != index) throw HeadphoneCommandException("The headphones did not switch to ${slot?.name ?: "mode $index"}")
        }
        var s = current.copy(currentModeIndex = index)
        if (noisePath == NoisePath.DIRECT) {
            // The level is [1.5]'s on the direct path; a mode switch changes it on the device.
            val cnc = rereadCnc(c)
            return s.copy(noise = cnc?.toNoiseSettings() ?: slot?.settings ?: s.noise, capabilities = capabilities())
        }
        val noise = if (!liveAudio) null else try {
            AudioSettings.parse(c.get(QcUltra2.AUDIO_SETTINGS).payload)?.toNoiseSettings()
        } catch (e: HeadphoneCommandException) {
            if (!c.isOpen) throw e
            ProtocolLog.w("[31.10] re-read after mode switch failed: ${e.message}")
            null
        } catch (e: BmapException) {
            ProtocolLog.w("[31.10] re-read after mode switch refused: ${e.message}")
            if (e.error.meansAbsent()) markAudioSettingsAbsent("${e.error}")
            null
        }
        s = s.copy(noise = noise ?: slot?.settings ?: s.noise, capabilities = capabilities())
        return s
    }

    /**
     * `[31.6]` SET_GET in the slot layout seen (40 bytes on the QC Ultra 2, 39 on the QC45, 37 on
     * the QC Earbuds 2020, untested there). Only custom slots take it: presets answer Runtime
     * error 8, so they are refused here with a readable message. The device echoes a STATUS; if it
     * parses it updates the slot, otherwise the slot list is re-dumped.
     */
    override suspend fun saveCustomMode(
        c: BmapRequester, current: HeadphoneState, index: Int, name: String, settings: NoiseSettings,
    ): HeadphoneState {
        requireDriven()
        requireModes()
        if (!facts.canEditModes) throw HeadphoneCommandException(MODES_READ_ONLY_MESSAGE)
        val slot = current.modes.firstOrNull { it.index == index }
        if (index in facts.presetSlots || slot?.isPreset == true) {
            val preset = slot?.name?.ifBlank { null } ?: facts.presetNames.getOrNull(index) ?: "slot $index"
            throw HeadphoneCommandException("$preset is a built-in mode and cannot be changed; save to a custom slot instead")
        }
        if (index !in facts.editableSlots && slot?.isEditable != true) throw HeadphoneCommandException("There is no custom slot $index")
        val cleanName = ModeConfigs.truncateName(name.trim())
        if (cleanName.isBlank()) throw HeadphoneCommandException("Give the mode a name")
        val wire = settings.toAudioSettings()
        val base = slotConfigs[index]
        val payload = ModeConfigWrite(
            index = index,
            name = cleanName,
            // Open-ear: no level of their own; keep what the slot holds.
            cncWire = if (model.hasNoiseCancelling) wire.cncWire else base?.cncWire ?: wire.cncWire,
            spatialCode = wire.spatialCode,
            windBlock = wire.windBlock,
            ancEnabled = wire.ancEnabled,
            // The user typed the name, so no prompt: the app would otherwise show the prompt's name (§C2).
            promptBytes = 0 to ModePrompt.NONE.code,
        ).build(facts.writeLayout, editableRange(index))
        return writeSlot(c, current, index, payload)
    }

    /**
     * Only full STATUS frames (they carry the `editable` byte) say which layout the firmware uses
     * (44 → 37-byte write, 45 → 39, 47 → 39, 48 → 40); an echo may be either. A wrong length is
     * refused with ERROR Length, so the observed frames beat the catalogue.
     */
    private fun noteLayout(config: ModeConfig) {
        if (config.editable == null) return
        slotConfigs[config.index] = config
        if (facts.seenLayout != config.layout) facts = facts.copy(seenLayout = config.layout)
    }

    /** The builder's slot check, widened to [index] when the STATUS said the slot is editable. */
    private fun editableRange(index: Int): IntRange =
        if (index in facts.editableSlots) facts.editableSlots else index..index

    /** SET_GET a built `[31.6]` payload and fold the echo (or a re-dump) into the mode list. */
    private suspend fun writeSlot(c: BmapRequester, current: HeadphoneState, index: Int, payload: ByteArray): HeadphoneState {
        if (payload.size == ModeLayout.BASIC.writeSize) ProtocolLog.i("[31.6] 37-byte write to slot $index (untested on hardware)")
        val echo = c.setGet(QcUltra2.MODE_CONFIG, payload)
        val parsedEcho = parsed(QcUltra2.MODE_CONFIG) { ModeConfigs.parse(echo.payload) }
        parsedEcho?.let(::noteLayout)
        if (parsedEcho != null && parsedEcho.index == index) {
            return current.copy(modes = current.modes.ifEmpty { emptyModeList() }.withSlot(parsedEcho, facts))
        }
        ProtocolLog.w("[31.6] echo did not describe slot $index (${echo}); re-reading the slot list")
        val r = TolerantReader(c)
        val configs = readModeConfigs(r)
        return current.copy(modes = buildModeList(configs, null, current.modes, facts))
    }

    private fun emptyModeList() = buildModeList(emptyMap(), null, emptyList(), facts)

    /**
     * `[31.9]` START `[index]` (Bose app `AudioModesResetStartPacket`), then `[31.6]` GET `[index]`
     * to read the slot back (falling back to the full dump). Only custom slots are offered: what
     * the firmware does to a preset is unknown. **Untested on hardware.**
     */
    override suspend fun resetCustomMode(c: BmapRequester, current: HeadphoneState, index: Int): HeadphoneState {
        requireDriven()
        requireModes()
        if (!facts.canEditModes) throw HeadphoneCommandException(MODES_READ_ONLY_MESSAGE)
        val custom = facts.editableSlots
        if (index !in custom) {
            throw HeadphoneCommandException("Only custom slots (${custom.first + 1}-${custom.last + 1}) can be reset")
        }
        ProtocolLog.i("[31.9] reset slot $index (untested on hardware)")
        val reply = c.request(BmapPacket.start(QcUltra2.MODES_RESET, ModeReset.startPayload(index))).firstOrNull()
        if (reply != null && reply.isError) throw BmapException.from(reply)
        val modes = current.modes.ifEmpty { emptyModeList() }
        val slot = try {
            ModeConfigs.parse(Replies.require(c.request(BmapPacket(QcUltra2.MODE_CONFIG.block, QcUltra2.MODE_CONFIG.function,
                BmapOperator.GET, ModeConfigs.getPayload(index)), ReplyMode.SINGLE, readTimeoutMs), QcUltra2.MODE_CONFIG).payload)
        } catch (e: BmapException) {
            ProtocolLog.w("[31.6] GET [$index] refused after reset: ${e.message}")
            null
        } catch (e: HeadphoneCommandException) {
            if (!c.isOpen) throw e
            null
        }
        slot?.let(::noteLayout)
        if (slot != null && slot.index == index) return current.copy(modes = modes.withSlot(slot, facts))
        val configs = readModeConfigs(TolerantReader(c))
        return current.copy(modes = buildModeList(configs, null, modes, facts))
    }

    /**
     * `[31.8]` SET_GET `[totalModes, big-endian bitset]`: two mask bytes for 11 slots, so slots
     * 8-10 are representable (their bits sit in the first mask byte; untested on hardware).
     */
    override suspend fun setFavorites(c: BmapRequester, current: HeadphoneState, indices: Set<Int>): HeadphoneState {
        requireDriven()
        requireModes()
        // [31.2] bit 4: the app hides favourites where the firmware has none.
        if (facts.modes?.favorites == false) throw HeadphoneCommandException(NO_FAVORITES_MESSAGE)
        val bad = indices.filter { !CurrentMode.isSavedSlot(it) }
        if (bad.isNotEmpty()) throw HeadphoneCommandException("No such mode slot: ${bad.joinToString()}")
        val payload = try {
            FavoritesCodec.build(indices, facts.slotCount)
        } catch (e: IllegalArgumentException) {
            throw HeadphoneCommandException("Those slots can't be marked as favourites", e)
        }
        if (indices.any { it > 7 }) ProtocolLog.i("[31.8] favourites include slots 8-10 (two-byte mask, untested on hardware)")
        val echo = c.setGet(QcUltra2.FAVORITES, payload)
        val applied = parsed(QcUltra2.FAVORITES) { FavoritesCodec.parse(echo.payload)?.indices } ?: indices
        val modes = current.modes.ifEmpty { emptyModeList() }
        return current.copy(modes = modes.withFavorites(applied))
    }

    /**
     * `[31.10]` SET_GET 5 bytes `[cnc, autoCnc=0, spatial, wind, anc]`, seeded from a fresh GET so
     * the write starts from what the headphones currently hold. The CNC byte is wire-inverted
     * (UI 10 = most cancelling = wire 0).
     *
     * CNC is only *audible* when anc=1 and wind=0: wind block masks the CNC DSP path, and ANC
     * off ignores it. The value is still stored and echoed, so it is written as asked.
     *
     * After a manual tweak the firmware reports `[31.3]` as 0xFF ("no saved mode") and, with
     * `[9.2]` on, pushes that as an unsolicited STATUS, which [onUnsolicited] picks up. In case it
     * does not, when the applied settings differ from the active slot's saved ones the index
     * is set to [QcUltra2.NO_SAVED_MODE] here as well; otherwise the UI keeps a preset highlighted
     * whose settings are no longer in effect.
     *
     * Without `[31.10]` the change goes through `[1.5]` ([NoisePath.DIRECT]) or the active custom
     * slot ([NoisePath.SLOTS]); see [noisePath].
     */
    override suspend fun setNoise(c: BmapRequester, current: HeadphoneState, settings: NoiseSettings): HeadphoneState {
        requireDriven()
        return when (noisePath) {
            NoisePath.LIVE -> setNoiseLive(c, current, settings)
            else -> setNoiseWithoutLive(c, current, settings)
        }
    }

    /** The paths that do not use `[31.10]`, also taken when `[31.10]` turns out missing mid-command. */
    private suspend fun setNoiseWithoutLive(c: BmapRequester, current: HeadphoneState, settings: NoiseSettings): HeadphoneState =
        when (noisePath) {
            NoisePath.DIRECT -> setNoiseDirect(c, current, settings)
            NoisePath.SLOTS -> setNoiseViaMode(c, current, settings)
            NoisePath.READ_ONLY, NoisePath.NONE, NoisePath.LIVE ->
                throw HeadphoneCommandException(if (modesAvailable || !model.hasNoiseCancelling) MODES_UNAVAILABLE_MESSAGE else NOISE_READ_ONLY_MESSAGE)
        }

    private suspend fun setNoiseLive(c: BmapRequester, current: HeadphoneState, settings: NoiseSettings): HeadphoneState {
        val seed = try {
            AudioSettings.parse(c.get(QcUltra2.AUDIO_SETTINGS).payload)
        } catch (e: HeadphoneCommandException) {
            if (!c.isOpen) throw e
            // Silent [31.10] on an unverified model: older firmware ignores functions it lacks.
            // Treat it as absent and write the slot instead. On a verified model a timeout is
            // only a timeout: seed from the last known state as before, so one slow reply does
            // not push the QC Ultra 2 onto slot writes (which refuse the built-in modes).
            if (!model.verified) {
                markAudioSettingsAbsent("no reply")
                return setNoiseWithoutLive(c, current, settings)
            }
            null
        } catch (e: BmapException) {
            if (e.error.meansAbsent()) {
                markAudioSettingsAbsent("${e.error}")
                return setNoiseWithoutLive(c, current, settings)
            }
            null
        } ?: current.noise?.toAudioSettings()
        val target = settings.toAudioSettings()
        if (!model.hasNoiseCancelling) {
            // Open-ear: only Immersive Audio is the user's; every other byte goes back as read.
            val read = seed ?: throw HeadphoneCommandException("Couldn't read the current audio settings")
            return applyAudioSettings(c, current, read.copy(spatialCode = target.spatialCode))
        }
        // Seed is kept for forward compatibility: if a future firmware adds trailing fields the
        // builder would carry them; today all five bytes are user-controlled and autoCnc is 0.
        val wire = seed?.copy(cncWire = target.cncWire, spatialCode = target.spatialCode,
            windBlock = target.windBlock, ancEnabled = target.ancEnabled, autoCnc = false) ?: target
        return try {
            applyAudioSettings(c, current, wire)
        } catch (e: BmapException) {
            // Readable but not writable on this firmware: the slot write is the other way in.
            if (!e.error.meansAbsent()) throw e
            markAudioSettingsAbsent("SET_GET ${e.error}")
            setNoiseWithoutLive(c, current, settings)
        }
    }

    /**
     * `[1.5]` SET_GET `[step, enabled]` (Bose `SettingsCncSetGetPacket`; bosectl-verified on the QC
     * Earbuds 2020, docs/bose-models.md §A10): the UI level maps onto the unit's own step count,
     * step 0 = most cancelling (§C3). ANC on/off is only sent where `[1.5]` says the user may
     * toggle it (flags bit 1 = 0); otherwise the unit's state goes back unchanged. Wind and spatial
     * are not part of this path.
     *
     * A refusal (FuncNotSupp / OpNotSupp, as wolverine and the QC45 answer) ends the direct path for
     * the connection: with modes the active slot is rewritten instead, without them the level is
     * read-only (the 0.3.1 behaviour) and the command fails with a message saying so.
     */
    private suspend fun setNoiseDirect(c: BmapRequester, current: HeadphoneState, settings: NoiseSettings): HeadphoneState {
        val cnc = facts.cnc?.takeIf { it.isValid } ?: throw HeadphoneCommandException("Noise settings not loaded yet")
        val step = cnc.stepForLevel(settings.cancellingLevel)
        val enabled = if (cnc.userCanToggle) settings.ancEnabled else cnc.enabled
        val written = Cnc.build(step, enabled)
        val echo = try {
            c.setGet(QcUltra2.CNC, written)
        } catch (e: BmapException) {
            if (!e.error.meansAbsent()) throw e
            directCncRefused = true
            ProtocolLog.i("[1.5] SET_GET refused (${e.error}): ${if (modesAvailable) "noise edits go through the active custom mode" else "the noise level is read-only"} on this connection")
            if (modesAvailable) return setNoiseViaMode(c, current, settings)
            throw HeadphoneCommandException(NOISE_READ_ONLY_MESSAGE, e)
        }
        // The reply should be the 3-byte GET form; an echo of the two written bytes says only that it took.
        val applied = parsed(QcUltra2.CNC) { Cnc.parse(echo.payload) }?.takeIf { it.isValid }
            ?: cnc.copy(step = step, flags = (cnc.flags and 0x01.inv()) or (if (enabled) 0x01 else 0))
        facts = facts.copy(cnc = applied)
        return current.copy(noise = applied.toNoiseSettings() ?: settings, capabilities = capabilities())
    }

    /** `[1.5]` GET after a mode switch on the direct path; null when it did not answer. */
    private suspend fun rereadCnc(c: BmapRequester): CncState? = try {
        Cnc.parse(c.get(QcUltra2.CNC).payload)?.takeIf { it.isValid }?.also { facts = facts.copy(cnc = it) }
    } catch (e: HeadphoneCommandException) {
        if (!c.isOpen) throw e
        null
    } catch (e: BmapException) {
        null
    }

    /** `[31.10]` SET_GET [wire] and fold the echo into the state (see [setNoise]). */
    private suspend fun applyAudioSettings(c: BmapRequester, current: HeadphoneState, wire: AudioSettings): HeadphoneState {
        val echo = c.setGet(QcUltra2.AUDIO_SETTINGS, AudioSettings.build(wire))
        val applied = parsed(QcUltra2.AUDIO_SETTINGS) { AudioSettings.parse(echo.payload) }
            ?: throw unexpected(QcUltra2.AUDIO_SETTINGS, "short echo (${echo.payload.size} bytes)")
        val noise = applied.toNoiseSettings()
        // Unknown slot settings (placeholder entry) cannot be compared; leave the index alone then.
        val saved = current.currentMode?.settings
        val modeIndex = if (saved != null && saved != noise) QcUltra2.NO_SAVED_MODE else current.currentModeIndex
        return current.copy(noise = noise, currentModeIndex = modeIndex)
    }

    /**
     * Firmware without `[31.10]` (QC45, QC SE, QC Headphones, QC SC, and any model where it turned
     * out missing at runtime): the live settings are the active slot's, so a change rewrites that
     * slot with the `[31.6]` SET_GET (bosectl's `_update_current_mode_config`) in the layout the
     * slots arrived in. Built-in modes cannot be changed there, so the user has to be on a custom
     * mode. On the QC45 wind block forces the level to 0; the echo shows that. The 40-byte
     * (STANDARD) write carries the ANC toggle; the 39-byte one has no such byte, the 37-byte one
     * (QC Earbuds 2020, untested) only the level. The slot keeps its own prompt and name bytes.
     */
    private suspend fun setNoiseViaMode(c: BmapRequester, current: HeadphoneState, settings: NoiseSettings): HeadphoneState {
        val slot = current.currentMode
        if (slot == null || !slot.isEditable || !slot.isConfigured) {
            throw HeadphoneCommandException(
                "On these headphones noise settings belong to a mode: switch to a custom mode to change them",
            )
        }
        val wire = settings.toAudioSettings()
        val base = slotConfigs[slot.index]
        val spatial = facts.modes?.spatial ?: model.hasSpatialAudio
        val payload = ModeConfigWrite(
            index = slot.index,
            name = ModeConfigs.truncateName(base?.name?.trim()?.ifBlank { null } ?: slot.name),
            cncWire = if (model.hasNoiseCancelling) wire.cncWire else base?.cncWire ?: wire.cncWire,
            spatialCode = if (spatial) wire.spatialCode else base?.spatialCode ?: 0,
            windBlock = wire.windBlock,
            ancEnabled = wire.ancEnabled, // the 39-byte builder has no ANC byte and ignores it
            promptBytes = base?.promptBytes ?: (0 to ModePrompt.NONE.code),
        ).build(facts.writeLayout, editableRange(slot.index))
        val s = writeSlot(c, current, slot.index, payload)
        return s.copy(noise = s.currentMode?.settings ?: settings, capabilities = capabilities())
    }

    // ── Settings block ───────────────────────────────────────────────────────────────────

    /**
     * `[1.7]` SET_GET `[value, band]`, one request per band that differs from [HeadphoneState.eq].
     * Values are clamped to the range the headphones reported ([HeadphoneState.eqRange]; ±10 on
     * every model seen so far).
     */
    override suspend fun setEq(c: BmapRequester, current: HeadphoneState, eq: HeadphoneEq): HeadphoneState {
        requireDriven()
        val before = current.eq
        val range = current.eqRange ?: QcUltra2.EQ_MIN..QcUltra2.EQ_MAX
        val changes = listOf(
            EqBandId.BASS to eq.bass.coerceIn(range), EqBandId.MID to eq.mid.coerceIn(range), EqBandId.TREBLE to eq.treble.coerceIn(range),
        ).filter { (band, value) ->
            when (band) {
                EqBandId.BASS -> before?.bass != value
                EqBandId.MID -> before?.mid != value
                EqBandId.TREBLE -> before?.treble != value
            }
        }
        if (changes.isEmpty()) return current
        var lastEcho: BmapPacket? = null
        for ((band, value) in changes) lastEcho = c.setGet(QcUltra2.EQ, Eq.buildBand(value, band))
        // The SET_GET reply is the full 12-byte GET form; fall back to a GET if it was not.
        val bands = lastEcho?.let { parsed(QcUltra2.EQ) { Eq.parse(it.payload) } }?.takeIf { it.toHeadphoneEq() != null }
            ?: parsed(QcUltra2.EQ) { Eq.parse(c.get(QcUltra2.EQ).payload) }
        if (bands.toHeadphoneEq() == null) {
            ProtocolLog.w("[1.7] echo incomplete; assuming the requested EQ applied")
            return current.copy(eq = HeadphoneEq(eq.bass.coerceIn(range), eq.mid.coerceIn(range), eq.treble.coerceIn(range)))
        }
        return current.withEq(bands)
    }

    /**
     * `[1.4]` StandbyTimer SET_GET: minutes ≤ 255 as one byte, else two bytes little-endian (Bose
     * `SettingsStandbyTimerSetGetPacket`); 0 = never. The Bose app offers [AutoOffOptions.MINUTES].
     * Only offered where the GET answered ([HeadphoneState.autoOffMinutes] non-null). Untested on
     * hardware: wolverine fw 10.12 has no `[1.4]`.
     */
    override suspend fun setAutoOff(c: BmapRequester, current: HeadphoneState, minutes: Int): HeadphoneState {
        requireDriven()
        if (minutes !in 0..StandbyTimer.MAX_MINUTES) throw HeadphoneCommandException("Auto-off must be 0 to ${StandbyTimer.MAX_MINUTES} minutes")
        ProtocolLog.i("[1.4] auto-off -> $minutes min (untested on hardware)")
        val written = StandbyTimer.build(minutes)
        val echo = c.setGet(QcUltra2.STANDBY_TIMER, written)
        // A bare echo of a two-byte write would read as its low byte; only a GET-form reply is parsed.
        val applied = if (echo.payload.contentEquals(written)) minutes else StandbyTimer.parse(echo.payload) ?: minutes
        if (applied != minutes) ProtocolLog.i("[1.4] headphones report $applied min after asking for $minutes")
        return current.copy(autoOffMinutes = applied)
    }

    /** `[1.11]` SET_GET `[persist, level]`; level order on the wire is 0 off, 1 high, 2 medium, 3 low. */
    override suspend fun setSidetone(c: BmapRequester, current: HeadphoneState, level: Sidetone, persistent: Boolean): HeadphoneState {
        requireDriven()
        val echo = c.setGet(QcUltra2.SIDETONE, SidetoneCodec.build(level.toLevelCode(), persistent))
        val state = parsed(QcUltra2.SIDETONE) { SidetoneCodec.parse(echo.payload) }
        return if (state != null) current.copy(sidetone = state.toSidetone(), sidetonePersistent = state.persist)
        else current.copy(sidetone = level, sidetonePersistent = persistent)
    }

    override suspend fun setAutoPlayPause(c: BmapRequester, current: HeadphoneState, enabled: Boolean): HeadphoneState =
        current.copy(autoPlayPause = setToggle(c, QcUltra2.AUTO_PLAY_PAUSE, enabled))

    override suspend fun setAutoAnswer(c: BmapRequester, current: HeadphoneState, enabled: Boolean): HeadphoneState =
        current.copy(autoAnswer = setToggle(c, QcUltra2.AUTO_ANSWER, enabled))

    private suspend fun setToggle(c: BmapRequester, address: FunctionAddress, enabled: Boolean): Boolean {
        requireDriven()
        val echo = c.setGet(address, Toggle.build(enabled))
        return parsed(address) { Toggle.parse(echo.payload) } ?: enabled
    }

    /** `[1.34]` SET_GET; the wire bit equals [enabled] (1 = touch on, verified 2026-10-18; see [TouchControls]). */
    override suspend fun setTouchControls(c: BmapRequester, current: HeadphoneState, enabled: Boolean): HeadphoneState {
        requireDriven()
        val echo = c.setGet(QcUltra2.DISABLE_CAPTOUCH, TouchControls.build(enabled))
        return current.copy(touchControlsEnabled = parsed(QcUltra2.DISABLE_CAPTOUCH) { TouchControls.parseEnabled(echo.payload) } ?: enabled)
    }

    /**
     * `[1.10]` SET_GET `[0|1]`. In bosectl's capture the headphones dropped the RFCOMM link
     * right after this write and every later read failed, so a timeout or "connection lost"
     * here is treated as success: the write went out, the firmware is re-negotiating its links.
     * [dev.earshot.bt.ConnectionManager] reconnects on its own and the refresh that follows
     * reads the real value back. An ERROR reply (e.g. 5 = not supported) still fails the command.
     */
    override suspend fun setMultipoint(c: BmapRequester, current: HeadphoneState, enabled: Boolean): HeadphoneState {
        requireDriven()
        setGetToleratingDrop(c, QcUltra2.MULTIPOINT, Multipoint.build(enabled), "the link renegotiates")
        return current.copy(multipointEnabled = enabled)
    }

    /**
     * `[1.37]` LeAudioControl SET_GET `[0|1]`. The Bose app warns that the headphones restart and
     * power back on after this, so like multipoint a missing reply or a dropped link is success;
     * an ERROR reply fails. The returned state already says [enabled]; the reconnect's refresh
     * reads the real value.
     */
    /**
     * `[1.37]` SET_GET. Offered even when `[0.23]` says "not supported" or `[1.37]` did not answer
     * its GET (the user is warned first): some firmware may still take it. Firmware without the
     * function refuses with FuncNotSupp / FblockNotSupp, which gets a message of its own: an app
     * cannot add LE Audio, only a Bose firmware update can.
     */
    override suspend fun setLeAudioEnabled(c: BmapRequester, current: HeadphoneState, enabled: Boolean): HeadphoneState {
        requireDriven()
        ProtocolLog.i("[1.37] LE Audio -> $enabled (reported support: ${current.leAudioSupported}); the headphones restart now")
        try {
            setGetToleratingDrop(c, QcUltra2.LE_AUDIO_CONTROL, LeAudioControl.build(enabled), "the headphones restart")
        } catch (e: BmapException) {
            // Error 5 (OpNotSupp) is different: the switch exists but this firmware won't let it be
            // changed; it keeps the generic "firmware doesn't support changing this" path.
            if (e.error == BmapError.FUNC_NOT_SUPPORTED || e.error == BmapError.FBLOCK_NOT_SUPPORTED) {
                throw HeadphoneCommandException(LE_AUDIO_ABSENT_MESSAGE, e)
            }
            throw e
        }
        return current.copy(leAudioEnabled = enabled)
    }

    private suspend fun setGetToleratingDrop(c: BmapRequester, address: FunctionAddress, payload: ByteArray, why: String) {
        try {
            val reply = c.request(BmapPacket.setGet(address, payload)).firstOrNull()
            if (reply != null && reply.isError) throw BmapException.from(reply)
        } catch (e: HeadphoneCommandException) {
            ProtocolLog.i("$address no reply after the write (expected: $why): ${e.message}")
        }
    }

    /** `[1.3]` SET_GET one byte `(enabled << 5) | language`. */
    override suspend fun setVoicePrompts(c: BmapRequester, current: HeadphoneState, prompts: VoicePrompts): HeadphoneState {
        requireDriven()
        val payload = try {
            VoicePromptsCodec.build(prompts.enabled, prompts.languageId)
        } catch (e: IllegalArgumentException) {
            throw HeadphoneCommandException("Unknown voice prompt language ${prompts.languageId}", e)
        }
        val echo = c.setGet(QcUltra2.VOICE_PROMPTS, payload)
        val state = parsed(QcUltra2.VOICE_PROMPTS) { VoicePromptsCodec.parse(echo.payload) }
        return current.copy(voicePrompts = state?.toVoicePrompts() ?: prompts)
    }

    /**
     * `[1.9]` SET_GET `[buttonId, eventType, action]`, keeping the button/event the headphones
     * reported (the QC Ultra 2's one remappable control is the Shortcut button 0x80, long
     * press). bosectl says the remap works; Quiet Rebellion says the firmware echoes but ignores
     * it. The echo is checked so the second case is reported, not hidden.
     */
    override suspend fun setShortcutButton(c: BmapRequester, current: HeadphoneState, action: ButtonAction): HeadphoneState =
        setShortcutButton(
            c, current,
            buttonId = current.shortcutButton?.buttonId ?: ButtonId.SHORTCUT.code,
            eventType = current.shortcutButton?.eventType ?: ButtonEvent.LONG_PRESS.code,
            action = action,
        )

    /** One record (earbuds: one per bud), always with the button id and event type the device reported for it. */
    override suspend fun setShortcutButton(
        c: BmapRequester,
        current: HeadphoneState,
        buttonId: Int,
        eventType: Int,
        action: ButtonAction,
    ): HeadphoneState {
        requireDriven()
        if (action == ButtonAction.UNKNOWN) throw HeadphoneCommandException("That button action is not known to the headphones")
        val echo = c.setGet(QcUltra2.BUTTONS, Buttons.build(buttonId, eventType, action.toActionCode()))
        val config = parsed(QcUltra2.BUTTONS) { Buttons.parse(echo.payload) }
            ?: throw unexpected(QcUltra2.BUTTONS, "short echo (${echo.payload.size} bytes)")
        if (config.actionCode != action.toActionCode()) {
            throw HeadphoneCommandException("The headphones ignored the change (that action may be unavailable right now)")
        }
        return current.withButtonRecord(config.toButtonAssignment())
    }

    /**
     * `[1.2]` SET_GET with the bare UTF-8 name (no flag byte). Error 5 means the firmware does not
     * implement the write; the [BmapException] propagates and the repository flags
     * `notSupportedByFirmware`. The GET form is `[flag, name]` while the echo's shape is not
     * captured, so the name is read back with a GET rather than guessed from the echo.
     */
    override suspend fun setDeviceName(c: BmapRequester, current: HeadphoneState, name: String): HeadphoneState {
        requireDriven()
        val payload = try {
            CustomName.build(name)
        } catch (e: IllegalArgumentException) {
            throw HeadphoneCommandException("The name must be 1 to ${QcUltra2.CUSTOM_NAME_MAX_BYTES} characters", e)
        }
        c.setGet(QcUltra2.CUSTOM_NAME, payload)
        val applied = try {
            CustomName.parse(c.get(QcUltra2.CUSTOM_NAME).payload)?.ifBlank { null }
        } catch (e: HeadphoneCommandException) {
            if (!c.isOpen) throw e
            null
        } catch (e: BmapException) {
            null
        } ?: name.trim()
        return current.copy(info = (current.info ?: DeviceInfo()).copy(customName = applied))
    }

    // ── AudioManagement block ────────────────────────────────────────────────────────────

    /** `[5.5]` SET_GET `[level]`; the echo `[max][current]` is applied. Untested on hardware. */
    override suspend fun setVolume(c: BmapRequester, current: HeadphoneState, level: Int): HeadphoneState {
        requireDriven()
        val max = current.volumeMax ?: QcUltra2.VOLUME_MAX_FW_10_12
        if (level !in 0..max) throw HeadphoneCommandException("Volume must be 0 to $max")
        ProtocolLog.i("[5.5] volume -> $level/$max (untested on hardware)")
        val echo = c.setGet(QcUltra2.VOLUME, Volume.build(level, max))
        val v = parsed(QcUltra2.VOLUME) { Volume.parse(echo.payload) }
        return if (v != null) current.withVolume(v) else current.copy(volume = level)
    }

    /**
     * `[5.7]` SET_GET `[s16 BE]`. **Experimental**: the Bose app has the setter and never calls it;
     * the echo (or a re-read) is what the state shows, so a firmware that ignores the write is
     * visible as the value snapping back.
     */
    override suspend fun setLatencyMs(c: BmapRequester, current: HeadphoneState, ms: Int): HeadphoneState {
        requireDriven()
        current.latencyRangeMs?.let { range ->
            if (ms !in range) throw HeadphoneCommandException("Latency must be ${range.first} to ${range.last} ms")
        }
        ProtocolLog.i("[5.7] latency -> $ms ms (experimental, never used by the Bose app)")
        val payload = try {
            Latency.build(ms)
        } catch (e: IllegalArgumentException) {
            throw HeadphoneCommandException("Latency out of range", e)
        }
        val echo = c.setGet(QcUltra2.LATENCY, payload)
        val level = parsed(QcUltra2.LATENCY) { Latency.parse(echo.payload) }
            ?: runCatching { Latency.parse(c.get(QcUltra2.LATENCY).payload) }.getOrNull()
        if (level != null && level.current != ms) ProtocolLog.i("[5.7] headphones report ${level.current} ms after asking for $ms")
        return if (level != null) current.withLatency(level) else current.copy(latencyMs = ms)
    }

    /**
     * `[5.3]` START `[AudioControlValue]` (Bose app `AudioManagementControlStartPacket`). The reply is
     * RESULT or STATUS; an ERROR fails. The play state is set optimistically for play/pause/stop
     * and corrected by the `[5.4]` push. Untested on hardware (bosectl's error 5 was plain SET).
     */
    override suspend fun transport(c: BmapRequester, current: HeadphoneState, control: TransportControl): HeadphoneState {
        requireDriven()
        ProtocolLog.i("[5.3] transport $control (untested on hardware)")
        val reply = c.request(BmapPacket.start(QcUltra2.AUDIO_CONTROL, AudioControl.startPayload(control.toWire()))).firstOrNull()
        if (reply != null && reply.isError) throw BmapException.from(reply)
        val state = when (control) {
            TransportControl.PLAY -> PlaybackState.PLAYING
            TransportControl.PAUSE -> PlaybackState.PAUSED
            TransportControl.STOP -> PlaybackState.STOPPED
            TransportControl.NEXT, TransportControl.PREVIOUS -> null
        }
        return if (state == null) current else current.copy(playback = Playback(state, current.playback?.trackPosition))
    }

    // ── Devices and power ────────────────────────────────────────────────────────────────

    /**
     * `[4.12]` START `[0x82, mac]`. The firmware's routing table is only populated while the
     * target is actually streaming, otherwise the START answers FuncNotSupp (4); like Quiet
     * Rebellion this retries [Routing.RETRY_ATTEMPTS] times at [Routing.RETRY_DELAY_MS] so the
     * user can hit play on the other device meanwhile. On fw 10.12 the `[4.12]` GET answers
     * FuncNotSupp; the START has not been tried there yet.
     */
    override suspend fun switchToDevice(c: BmapRequester, current: HeadphoneState, address: String): HeadphoneState {
        requireDriven()
        if (!Mac.isValid(address)) throw HeadphoneCommandException("Not a Bluetooth address: $address")
        val packet = BmapPacket.start(QcUltra2.ROUTING, Routing.startPayload(address))
        repeat(Routing.RETRY_ATTEMPTS) { attempt ->
            val reply = c.request(packet).firstOrNull()
            when {
                reply == null -> throw unexpected(QcUltra2.ROUTING, "empty reply")
                Routing.isTableEmpty(reply) -> {
                    ProtocolLog.d("[4.12] routing table empty (attempt ${attempt + 1}/${Routing.RETRY_ATTEMPTS})")
                    if (attempt < Routing.RETRY_ATTEMPTS - 1) delay(Routing.RETRY_DELAY_MS)
                }
                reply.isError -> throw BmapException.from(reply)
                else -> {
                    val routed = Routing.parseReply(reply) ?: address
                    return current.copy(pairedDevices = current.pairedDevices.withActiveSource(routed))
                }
            }
        }
        throw HeadphoneCommandException("The headphones can't switch yet. Start playing audio on the other device, then try again.")
    }

    /** `[4.8]` START `[1]`, fire-and-forget: the link usually drops and the manager reconnects. */
    override suspend fun enterPairingMode(c: BmapRequester, current: HeadphoneState): HeadphoneState {
        requireDriven()
        c.send(BmapPacket.start(QcUltra2.PAIRING_MODE, PairingMode.startPayload(true)))
        return current
    }

    /** `[7.4]` START `[0]`, fire-and-forget: the socket closes immediately. */
    override suspend fun powerOff(c: BmapRequester, current: HeadphoneState): HeadphoneState {
        requireDriven()
        c.send(BmapPacket.start(QcUltra2.POWER, Power.startPayload(false)))
        return current
    }

    // ── Lab: Print console [22.x] ────────────────────────────────────────────────────────

    /**
     * One console line → `[22.3]`/`[22.4]`/`[22.5]` SET_GET or `[22.2]` START, collected in MULTI
     * mode so any `[22.x]` output that follows within the quiet gap is shown too. ERROR frames
     * are rendered, not thrown: this is a console. **Experimental.**
     */
    override suspend fun printConsole(c: BmapRequester, command: String): String {
        val packet = try {
            PrintConsole.parseCommand(command)
        } catch (e: IllegalArgumentException) {
            throw HeadphoneCommandException(e.message ?: "Bad console command", e)
        } ?: throw HeadphoneCommandException("Unknown console command. Use bind, unbind, verbosity or output.")
        ProtocolLog.i("Print console: ${packet.address} ${packet.operator}")
        val frames = c.request(packet, ReplyMode.MULTI)
        return frames.joinToString("\n") { f ->
            val body = if (f.isError) "ERROR ${f.error?.name ?: f.errorCode}" else PrintConsole.render(f.payload)
            "${f.address} ${f.operator.name}: $body"
        }
    }

    // ── Pushes ───────────────────────────────────────────────────────────────────────────

    /**
     * Frames nobody asked for. With `[9.2]` on, the headphones push a STATUS whenever a value in
     * a subscribed block changes: battery, charger, mic mute, button events, a mode changed with
     * the button, a slider moved in another app, volume, play state, LE Audio, a `[31.6]` slot
     * rewritten. RESULT frames are only taken from `[31.3]` (index) and `[4.19]` (LE check bits).
     * Parse failures return null (logged by the repository), never throw. Does not suspend.
     */
    override fun onUnsolicited(frame: BmapPacket, current: HeadphoneState): HeadphoneState? {
        val p = frame.payload
        if (frame.operator == BmapOperator.RESULT) {
            return when (frame.address) {
                QcUltra2.CURRENT_MODE -> CurrentMode.parse(p)?.let { current.copy(currentModeIndex = it) }
                QcUltra2.LE_AUDIO_CHECK -> LeAudioCheck.parse(p)?.let { current.copy(lePreference = it.toLePreference()) }
                else -> null
            }
        }
        if (frame.operator != BmapOperator.STATUS) return null
        return try {
            when (frame.address) {
                QcUltra2.BATTERY -> Battery.parse(p).takeIf { it.percent != null || it.readings.isNotEmpty() }?.let { current.withBattery(it) }
                QcUltra2.CHARGER_DETECT -> ChargerDetect.parse(p)?.let { current.copy(isCharging = it) }
                QcUltra2.MIC_MUTE -> MicMute.parse(p)?.let { current.copy(micMuted = it) }
                QcUltra2.OPERATION_MODE -> OperationModeCodec.parse(p)?.let { current.copy(operationMode = it.toOperationMode()) }
                QcUltra2.STATUS_BUTTON -> ButtonEventPush.parse(p)?.let { current.copy(lastButtonPress = it.toButtonPress(clock())) }
                // Pushed even by firmware that refuses every block-31 request (bosectl #44): kept as
                // the index, while the mode list stays empty there.
                QcUltra2.CURRENT_MODE -> CurrentMode.parse(p)?.let { index ->
                    val s = current.copy(currentModeIndex = index)
                    if (noisePath != NoisePath.SLOTS) s else s.copy(noise = s.currentMode?.settings ?: s.noise)
                }
                QcUltra2.AUDIO_SETTINGS -> AudioSettings.parse(p)?.let { current.copy(noise = it.toNoiseSettings()) }
                // [1.5] is the level on the direct (and read-only) path; elsewhere it only mirrors it.
                QcUltra2.CNC -> Cnc.parse(p)?.takeIf { it.isValid }?.let { cnc ->
                    facts = facts.copy(cnc = cnc)
                    val path = noisePath
                    if (path != NoisePath.DIRECT && path != NoisePath.READ_ONLY) null
                    else cnc.toNoiseSettings()?.let { current.copy(noise = it) }
                }
                QcUltra2.STANDBY_TIMER -> StandbyTimer.parse(p)?.let { current.copy(autoOffMinutes = it) }
                QcUltra2.EQ -> Eq.parse(p).takeIf { it.toHeadphoneEq() != null }?.let { current.withEq(it) }
                QcUltra2.SIDETONE -> SidetoneCodec.parse(p)?.let { current.copy(sidetone = it.toSidetone(), sidetonePersistent = it.persist) }
                QcUltra2.AUTO_PLAY_PAUSE -> Toggle.parse(p)?.let { current.copy(autoPlayPause = it) }
                QcUltra2.AUTO_ANSWER -> Toggle.parse(p)?.let { current.copy(autoAnswer = it) }
                QcUltra2.MULTIPOINT -> Multipoint.parse(p)?.let { current.copy(multipointEnabled = it) }
                QcUltra2.VOICE_PROMPTS -> VoicePromptsCodec.parse(p)?.let { current.copy(voicePrompts = it.toVoicePrompts()) }
                QcUltra2.BUTTONS -> Buttons.parse(p)?.let { current.withButtonRecord(it.toButtonAssignment()) }
                QcUltra2.DISABLE_CAPTOUCH -> TouchControls.parseEnabled(p)?.let { current.copy(touchControlsEnabled = it) }
                QcUltra2.LE_AUDIO_CONTROL -> LeAudioControl.parse(p)?.let { current.copy(leAudioEnabled = it) }
                QcUltra2.LE_AUDIO_SUPPORTED -> ProductInfo.parseLeAudioSupported(p)?.let { current.copy(leAudioSupported = it) }
                QcUltra2.LE_AUDIO_CHECK -> LeAudioCheck.parse(p)?.let { current.copy(lePreference = it.toLePreference()) }
                QcUltra2.CUSTOM_NAME -> CustomName.parse(p)?.let { current.copy(info = (current.info ?: DeviceInfo()).copy(customName = it.ifBlank { null })) }
                QcUltra2.MODE_CONFIG -> if (!modesAvailable) null else ModeConfigs.parse(p)?.let { config ->
                    noteLayout(config)
                    val s = current.copy(modes = current.modes.withSlot(config, facts))
                    if (noisePath != NoisePath.SLOTS || config.index != s.currentModeIndex) s
                    else s.copy(noise = s.currentMode?.settings ?: s.noise)
                }
                QcUltra2.FAVORITES -> if (!modesAvailable) null else FavoritesCodec.parse(p)?.let { current.copy(modes = current.modes.withFavorites(it.indices)) }
                QcUltra2.AUDIO_SOURCE -> AudioSourceQuery.parse(p)?.let { current.copy(pairedDevices = current.pairedDevices.withActiveSource(it.mac)) }
                QcUltra2.PAIRED_DEVICES -> PairedDeviceList.parse(p)?.let {
                    current.copy(pairedDevices = current.pairedDevices.withConnectedFrom(it).withThisPhone(current.thisPhoneAddress))
                }
                QcUltra2.AUDIO_STATUS -> AudioStatusQuery.parse(p)?.toPlayback()?.let { current.copy(playback = it) }
                QcUltra2.VOLUME -> Volume.parse(p)?.let { current.withVolume(it) }
                QcUltra2.LATENCY -> Latency.parse(p)?.let { current.withLatency(it) }
                QcUltra2.AVAILABLE_TO_CONNECT -> AvailableToConnect.parse(p)?.let {
                    current.copy(availableToConnect = when (it) {
                        AvailableToConnectCode.AVAILABLE -> true
                        AvailableToConnectCode.NOT_AVAILABLE -> false
                        AvailableToConnectCode.UNKNOWN -> null
                    })
                }
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            ProtocolLog.w("ignoring malformed push $frame: ${e.message}")
            null
        }
    }

    // ── Request helpers ──────────────────────────────────────────────────────────────────

    /** GET [address]; an ERROR frame throws [BmapException]. */
    private suspend fun BmapRequester.get(address: FunctionAddress): BmapPacket =
        Replies.require(request(BmapPacket.get(address), ReplyMode.SINGLE, readTimeoutMs), address)

    /** SET_GET [payload] to [address]; an ERROR frame throws [BmapException]. */
    private suspend fun BmapRequester.setGet(address: FunctionAddress, payload: ByteArray): BmapPacket =
        Replies.require(request(BmapPacket.setGet(address, payload)), address)

    /** Parser `require()` failures become a readable command error instead of an IllegalArgumentException. */
    private inline fun <T> parsed(address: FunctionAddress, block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw HeadphoneCommandException("Unexpected reply from the headphones ($address): ${e.message}", e)
    }

    private fun unexpected(address: FunctionAddress, detail: String) =
        HeadphoneCommandException("Unexpected reply from the headphones ($address): $detail")

    /** How one tolerant read ended. */
    private enum class Outcome {
        OK,
        /** Answered with an ERROR frame; [TolerantReader.lastError] says which. */
        REFUSED,
        /** No reply in time (the link itself still answers, or the reader would have thrown). */
        SILENT,
        BAD_REPLY,
        /** Not asked: known absent, refused or silent earlier on this connection. */
        SKIPPED,
    }

    /**
     * Runs the reads of [refresh]: each one is independent, failures are logged and skipped.
     *
     * After [MAX_CONSECUTIVE_TIMEOUTS] reads in a row with no answer at all it sends the
     * known-good `[0.1]` GET: older firmware stays silent (no ERROR) for functions it lacks, and
     * until 0.3.0 three of those in a row ended the session as "stopped answering" and looped on
     * reconnects. If `[0.1]` answers, the silent reads are recorded as absent for this connection
     * (later refreshes skip them, like [BoseModel.knownAbsent]) and the refresh carries on; only if
     * `[0.1]` is silent too does it give up (throws), so a dead socket still costs a few timeouts,
     * not twenty, and the busy-channel detection keeps working.
     */
    private inner class TolerantReader(private val c: BmapRequester) {
        private var silentInARow = 0
        private val streak = ArrayList<Pair<FunctionAddress, BmapOperator>>()

        /** The error of the last [Outcome.REFUSED] read. */
        var lastError: BmapError? = null
            private set

        suspend fun get(address: FunctionAddress, apply: (BmapPacket) -> Unit): Outcome =
            request(BmapPacket.get(address), apply)

        suspend fun request(packet: BmapPacket, apply: (BmapPacket) -> Unit): Outcome =
            run(packet, ReplyMode.SINGLE) { frames -> apply(Replies.require(frames, packet.address)) }

        suspend fun multi(packet: BmapPacket, apply: (List<BmapPacket>) -> Unit): Outcome =
            run(packet, ReplyMode.MULTI, apply)

        private suspend fun run(packet: BmapPacket, mode: ReplyMode, apply: (List<BmapPacket>) -> Unit): Outcome {
            val address = packet.address
            if (!worthAsking(c, packet)) return Outcome.SKIPPED
            return try {
                apply(c.request(packet, mode, readTimeoutMs))
                answered()
                Outcome.OK
            } catch (e: BmapException) {
                answered()
                lastError = e.error
                noteRefusal(address, packet.operator, e.error)
                ProtocolLog.d("$address refused during refresh: ${e.message}")
                Outcome.REFUSED
            } catch (e: IllegalArgumentException) {
                answered()
                ProtocolLog.w("$address reply did not parse during refresh: ${e.message}")
                Outcome.BAD_REPLY
            } catch (e: HeadphoneCommandException) {
                if (!c.isOpen) throw e
                silentInARow++
                streak += address to packet.operator
                ProtocolLog.w("$address: ${e.message} ($silentInARow silent in a row)")
                if (silentInARow >= MAX_CONSECUTIVE_TIMEOUTS) {
                    if (!linkStillAnswers()) {
                        // Same text as BmapConnection's watchdog: the repository keys on it to close
                        // the session and report the busy channel.
                        throw HeadphoneCommandException(BmapConnection.MESSAGE_STOPPED_ANSWERING, e)
                    }
                    ProtocolLog.w("${streak.joinToString { it.first.toString() }} stayed silent but ${BmapConnection.CONFIRM_ADDRESS} answers: absent on this firmware")
                    silentReads += streak
                    answered()
                }
                Outcome.SILENT
            }
        }

        private fun answered() {
            silentInARow = 0
            streak.clear()
        }

        /** The known-good GET, short timeout. Any frame back (an ERROR too) proves the link alive. */
        private suspend fun linkStillAnswers(): Boolean = try {
            c.request(BmapPacket.get(BmapConnection.CONFIRM_ADDRESS), ReplyMode.SINGLE, CONFIRM_TIMEOUT_MS).isNotEmpty()
        } catch (e: HeadphoneCommandException) {
            if (!c.isOpen) throw e
            false
        }
    }

    /**
     * An ERROR that will not change on this connection: FuncNotSupp (and OpNotSupp, for this
     * operator) is remembered per function, FblockNotSupp for the whole block. bosectl #44: one
     * unit answers FblockNotSupp to every request in block 31, GET included; asking each of its
     * functions again every refresh was pure error spam.
     */
    private fun noteRefusal(address: FunctionAddress, operator: BmapOperator, error: BmapError?) {
        when (error) {
            BmapError.FBLOCK_NOT_SUPPORTED -> if (refusedBlocks.add(address.block)) ProtocolLog.i("block ${address.block} refused (FblockNotSupp): skipped for this connection")
            BmapError.FUNC_NOT_SUPPORTED, BmapError.OP_NOT_SUPPORTED -> refusedReads += address to operator
            else -> Unit
        }
    }

    /**
     * False for a read that cannot succeed on this connection: known absent on the model, in a
     * block `[0.2]` did not list or the firmware refused as a whole, already refused, or silent
     * while the link answered. Each would cost a request, or a 2 s timeout on firmware that stays
     * silent instead of answering.
     */
    private fun worthAsking(c: BmapRequester, packet: BmapPacket): Boolean {
        val address = packet.address
        if (address in model.knownAbsent) return false
        if (!blockAvailable(address.block)) return false
        if (address in c.silentFunctions) return false
        val key = address to packet.operator
        return key !in refusedReads && key !in silentReads
    }

    private fun blockAvailable(block: Int): Boolean = presentBlocks?.contains(block) != false && block !in refusedBlocks

    companion object {
        const val REFRESH_READ_TIMEOUT_MS = 2_000L
        const val MAX_CONSECUTIVE_TIMEOUTS = 3
        /** The `[0.1]` check after [MAX_CONSECUTIVE_TIMEOUTS] silent reads. */
        const val CONFIRM_TIMEOUT_MS = 1_500L
        private const val AUDIO_MODES_BLOCK = 31
        const val MODES_UNAVAILABLE_MESSAGE = "This firmware doesn't expose listening modes to other apps"
        const val LE_AUDIO_ABSENT_MESSAGE =
            "These headphones' firmware has no LE Audio switch. LE Audio can only come with a firmware update from the maker's app."
        /** `[4.4]` can list more than the two multipoint links; the UI shows a handful at most. */
        const val MAX_PAIRED_DEVICES = 8
        /** Battery re-read cadence while `[9.2]` pushes are off. */
        const val BATTERY_POLL_MS = 60_000L
        const val MODES_READ_ONLY_MESSAGE = "These headphones' modes can be switched here but not edited"
        /** `[1.5]` SET_GET refused and no listening modes to fall back on. */
        const val NOISE_READ_ONLY_MESSAGE = "These headphones' firmware doesn't let other apps change the noise cancelling level"
        const val NO_FAVORITES_MESSAGE = "These headphones don't have favourite modes"
        const val UNSUPPORTED_MESSAGE = "Earshot can't control these headphones"
    }
}
