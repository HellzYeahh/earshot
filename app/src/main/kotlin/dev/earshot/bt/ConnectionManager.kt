package dev.earshot.bt

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.earshot.core.ConnectionProblem
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the lifecycle of the one session ([C]: a [BmapConnection] for Bose, a [BragiConnection] for
 * the Skullcandy Crusher) to the headphones. How a session is opened is the [factory]'s business;
 * the policy below is the same for every family.
 *
 * States map 1:1 onto [ConnectionStatus]. The loop in [runLoop] is the whole policy:
 *  - [connect] starts it (idempotent); [disconnect] stops it and marks the stop as the user's,
 *    which suppresses retries until the headphones link to the phone again.
 *  - After a failed connect or a dropped link it retries on [ReconnectBackoff], but only while
 *    the phone still has the headphones connected for audio; otherwise it parks and waits for
 *    the next `ACL_CONNECTED` broadcast, which restarts it when auto-connect is on. Once the
 *    fast schedule is spent it keeps a slow pulse going as long as the audio link is up (the
 *    Bose app holding the channel is the usual reason), and an A2DP play/pause event re-arms
 *    a parked loop.
 *  - A socket that opens but stays silent (the factory's probe at connect, the connection's
 *    watchdog mid-session) is treated as dead: closed, reported as [MESSAGE_CHANNEL_BUSY] (the
 *    signature of the Bose app holding channel 2) and counted as a failed attempt.
 *  - Bluetooth turning off ends everything; turning on restarts it when auto-connect is on.
 *
 * Link-up broadcasts (`ACL_CONNECTED`, A2DP connected) are *not* handled here: the manifest
 * receiver `service.BluetoothConnectionReceiver` gets them whether or not the process is alive
 * and calls `connect()`; listening here as well delivered every link-up twice.
 *
 * [connect], [disconnect] and [chooseDevice] are called from broadcast receivers, the UI and
 * the service on whatever thread they run on, so the mutations of [loop] are synchronised.
 *
 * Nothing here knows about services or notifications: `service/` layers those on top.
 */
class ConnectionManager<C : ManagedLink>(
    private val context: Context,
    private val finder: DeviceFinder,
    private val prefs: DevicePrefs,
    private val scope: CoroutineScope,
    private val factory: LinkFactory<C>,
    /** Read lazily on every Bluetooth event; mirrors `AppPreferences.autoConnectOnBluetooth`. */
    private val autoConnect: () -> Boolean = { true },
    /**
     * Demo mode: returns a transport factory for the virtual headphones, or null to use real
     * Bluetooth. Consulted at the start of every connect loop, so toggling it takes effect on
     * the next connect.
     */
    private val demoTransport: () -> (() -> ByteTransport)? = { null },
    /** Shown while the virtual headphones are connected (demo mode); read per demo session, so it follows the chosen demo device. */
    private val demoName: () -> String = { DEMO_NAME },
) {
    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _connection = MutableStateFlow<C?>(null)
    /** The open session, or null. Consumers must not close it themselves. */
    val connection: StateFlow<C?> = _connection.asStateFlow()

    @Volatile
    private var loop: Job? = null

    /** Poked by [connect] to cut a backoff wait short. Conflated: one poke is as good as ten. */
    private val retryNow = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var userStopped = false

    @Volatile
    private var currentAddress: String? = null

    /** True while the loop talks to the in-process virtual headphones (demo mode). */
    @Volatile
    var isDemoSession: Boolean = false
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = onBluetoothEvent(intent)
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // These are protected system broadcasts; exporting the receiver is harmless and avoids
        // the API 34 "must specify export flag" trap for context-registered receivers.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /**
     * Start connecting, or keep going if already connecting/connected. Safe to call often and
     * from any thread: a second call while the loop runs only cuts a backoff wait short.
     */
    @Synchronized
    fun connect() {
        userStopped = false
        if (loop?.isActive == true) {
            retryNow.trySend(Unit) // a tap during the backoff wait should not sit out the timer
            return
        }
        loop = scope.launch { runLoop() }
    }

    /** User-initiated stop: closes the session and parks until the next ACL_CONNECTED. */
    @Synchronized
    fun disconnect() {
        userStopped = true
        stopLoop()
        _state.update { it.copy(status = ConnectionStatus.DISCONNECTED, message = null) }
    }

    /** Remember [address] as the headphones and (re)connect to it. For the device picker. */
    suspend fun chooseDevice(address: String, name: String?) {
        prefs.save(address, name)
        restart()
    }

    @Synchronized
    private fun restart() {
        stopLoop()
        connect()
    }

    @Synchronized
    private fun stopLoop() {
        loop?.cancel()
        loop = null
        _connection.value?.close()
        _connection.value = null
    }

    private suspend fun runLoop() {
        demoTransport()?.let { makeTransport ->
            isDemoSession = true
            try {
                runDemoLoop(makeTransport)
            } finally {
                isDemoSession = false
            }
            return
        }
        var attempt = 0
        while (true) {
            val found = finder.find()
            val device = when (found) {
                FindResult.NoPermission -> {
                    setState(ConnectionStatus.NO_PERMISSION, message = "Allow Earshot to connect to nearby devices")
                    return
                }
                FindResult.BluetoothOff -> {
                    setState(ConnectionStatus.DISCONNECTED, message = "Bluetooth is off")
                    return
                }
                is FindResult.NoDevice -> {
                    val message = if (found.candidates.isEmpty()) "Pair the headphones in Bluetooth settings first" else "Choose your headphones"
                    setState(ConnectionStatus.NO_PAIRED_DEVICE, message = message)
                    return
                }
                is FindResult.Found -> found.device
            }
            val address = device.address
            val name = found.candidate.name
            // The same rule the router uses (DevicePrefs.familyFor: the device's own "Headphone type",
            // then what answered on it before, then its name), so exactly one stack drives a device,
            // a renamed one is not left with nobody, and nothing else ever gets this stack's probe.
            if (prefs.familyFor(address, name) != factory.family) {
                BtLog.d("the ${factory.family.name.lowercase()} stack does not drive \"$name\"; standing down")
                return
            }
            currentAddress = address
            // Remember an auto-pick so later runs and the manifest receiver recognise the device, and
            // keep the saved name current (the router resolves the family from it before connecting).
            if (!found.candidate.isSaved || prefs.savedDevice()?.name != name) prefs.save(address, name)
            setState(ConnectionStatus.CONNECTING, name, address, message = null)

            var connection: C? = null
            try {
                // The factory opens the socket and proves the far end answers; on a silent or busy
                // channel it throws RfcommConnectException(looksBusy) → the busy message below.
                connection = factory.open(device, name, prefs, scope)
                _connection.value = connection
                setState(ConnectionStatus.CONNECTED, name, address, message = null)
                // This stack answered here: a later rename doesn't turn the device into a generic one.
                runCatching { prefs.rememberFamily(address, factory.family) }.onFailure { BtLog.w("could not remember the family of $address: ${it.message}") }
                val attemptsBefore = attempt
                attempt = 0
                val cause = connection.awaitClosed()
                _connection.value = null
                BtLog.i("session ended: ${cause?.message ?: "closed locally"}")
                val end = SessionEnd.classify(cause, userStopped, linked = if (userStopped) null else finder.isAudioConnected(address))
                setState(ConnectionStatus.DISCONNECTED, name, address, message = localized(end.message))
                if (userStopped) return
                // The watchdog closed a half-dead session: it never really worked, so it costs a
                // backoff step like a failed connect instead of resetting the schedule.
                if (end.countsAsFailedAttempt) attempt = attemptsBefore
            } catch (e: CancellationException) {
                connection?.close()
                _connection.value = null
                throw e
            } catch (e: SecurityException) {
                BtLog.e("connect refused by the system", e)
                setState(ConnectionStatus.NO_PERMISSION, name, address, message = "Bluetooth permission refused")
                return
            } catch (e: Exception) {
                val linked = finder.isAudioConnected(address)
                val message = localized(SessionEnd.connectFailureMessage(e, linked))
                BtLog.w("connect to $address failed (linked=$linked): ${e.message}")
                setState(ConnectionStatus.DISCONNECTED, name, address, message = message)
                if (linked == false) return // ACL_CONNECTED will wake us
            }

            // Retry policy.
            if (userStopped) return
            if (!finder.isBluetoothOn()) return
            val linked = finder.isAudioConnected(address)
            if (linked == false) {
                BtLog.i("headphones not linked to the phone; parking until ACL_CONNECTED")
                return
            }
            val wait = if (attempt < ReconnectBackoff.MAX_ATTEMPTS) {
                ReconnectBackoff.delayMs(attempt++)
            } else if (linked == true) {
                // Linked for audio but the channel will not open, most likely because the Bose
                // app holds it. A slow pulse picks the link back up once that app is closed.
                ReconnectBackoff.SLOW_RETRY_MS
            } else {
                BtLog.w("giving up after $attempt attempts; waiting for the next link-up or a tap")
                return
            }
            BtLog.d("retrying in ${wait}ms (attempt $attempt)")
            retryNow.tryReceive() // drop a poke that arrived while we were already connecting
            withTimeoutOrNull(wait) { retryNow.receive() }
        }
    }

    /**
     * Demo mode: no bonded device, no Bluetooth; the virtual headphones live in-process. A
     * "power off" closes the transport like a dropped socket, and the factory powers the virtual
     * unit back on for the next attempt, so the reconnect path is exercised too.
     */
    private suspend fun runDemoLoop(transportFactory: () -> ByteTransport) {
        currentAddress = DEMO_ADDRESS
        while (true) {
            setState(ConnectionStatus.CONNECTING, demoName(), DEMO_ADDRESS, message = null)
            var connection: C? = null
            try {
                connection = factory.adopt(transportFactory(), scope)
                _connection.value = connection
                setState(ConnectionStatus.CONNECTED, demoName(), DEMO_ADDRESS, message = null)
                val cause = connection.awaitClosed()
                _connection.value = null
                BtLog.i("demo session ended: ${cause?.message ?: "closed locally"}")
                if (userStopped) {
                    setState(ConnectionStatus.DISCONNECTED, demoName(), DEMO_ADDRESS, message = null)
                    return
                }
                setState(ConnectionStatus.DISCONNECTED, demoName(), DEMO_ADDRESS, message = "Connection lost")
            } catch (e: CancellationException) {
                connection?.close()
                _connection.value = null
                throw e
            } catch (e: Exception) {
                BtLog.w("demo transport failed: ${e.message}")
                setState(ConnectionStatus.DISCONNECTED, demoName(), DEMO_ADDRESS, message = "Demo headphones failed: ${e.message}")
            }
            if (userStopped) return
            retryNow.tryReceive()
            withTimeoutOrNull(DEMO_RECONNECT_MS) { retryNow.receive() }
        }
    }

    /** The busy message names the app that is probably holding the connection, which depends on the family. */
    private fun localized(message: String?): String? = if (message == MESSAGE_CHANNEL_BUSY) factory.busyMessage else message

    private fun setState(status: ConnectionStatus, name: String? = null, address: String? = null, message: String?) {
        _state.update {
            it.copy(
                status = status,
                deviceName = name ?: it.deviceName,
                deviceAddress = address ?: it.deviceAddress,
                message = message,
                problem = if (message != null && message == factory.busyMessage) ConnectionProblem.ChannelHeld(factory.competingApp) else null,
            )
        }
    }

    private fun onBluetoothEvent(intent: Intent) {
        when (intent.action) {
            // Audio started or stopped on the headphones: they are linked and awake, so a loop
            // that gave up or parked gets another go (a running one is merely poked).
            BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> DeviceFinder.deviceAddressFrom(intent)?.let(::rearm)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> DeviceFinder.deviceAddressFrom(intent)?.let { address ->
                if (address.equals(currentAddress, ignoreCase = true)) {
                    BtLog.i("ACL down for $address")
                    // The socket will notice on its own; nothing to do but let the loop park.
                }
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    BtLog.i("Bluetooth off")
                    stopLoop()
                    setState(ConnectionStatus.DISCONNECTED, message = "Bluetooth is off")
                }
                BluetoothAdapter.STATE_ON -> if (autoConnect() && !userStopped) connect()
            }
        }
    }

    /**
     * Re-arm after a play/pause on our headphones. Unlike a link-up this does not override a
     * manual disconnect: the user said stop, and music playing is not them changing their mind.
     */
    private fun rearm(address: String) {
        if (userStopped || !autoConnect()) return
        if (_state.value.status == ConnectionStatus.CONNECTED) return
        scope.launch {
            if (!isOurDevice(address)) return@launch
            BtLog.i("audio state changed on $address, re-arming the connect loop")
            connect()
        }
    }

    /**
     * True when [address] is the saved device (or its LE Audio / classic twin, which links up on
     * its own address) or, before any choice was made, looks like a Bose.
     */
    suspend fun isOurDevice(address: String): Boolean {
        val saved = prefs.savedDevice()?.address
        if (saved != null) {
            return saved.equals(address, ignoreCase = true) || finder.twinsOf(saved).any { it.address.equals(address, ignoreCase = true) }
        }
        // Nothing chosen yet: accept the device if the name says Bose.
        return finder.candidates().any { it.address.equals(address, ignoreCase = true) && it.looksLikeBose }
    }

    companion object {
        const val MESSAGE_CHANNEL_BUSY = "Close the Bose app, then try again"
        const val DEMO_NAME = "Virtual QC Ultra (demo)"
        const val DEMO_ADDRESS = "00:00:00:DE:40:01"
        private const val DEMO_RECONNECT_MS = 3_000L
    }
}
