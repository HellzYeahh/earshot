package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import dev.earshot.protocol.qcultra2.BoseModel
import kotlinx.coroutines.CoroutineScope

/** What [ConnectionManager] needs from a live session, whichever protocol it speaks. */
interface ManagedLink {
    val isOpen: Boolean

    /** Suspends until the session is closed, by us or by the link. Returns the cause (null when local). */
    suspend fun awaitClosed(): Throwable?

    fun close()
}

/**
 * How one family of headphones is reached: which socket to open, how to confirm the far end really
 * answers, and what to do for the demo transport. [ConnectionManager] owns the reconnect policy and
 * is the same for every family; a factory only knows how to open one session.
 */
interface LinkFactory<C : ManagedLink> {
    /** What to tell the user when the control connection is silent: usually another app holds it. */
    val busyMessage: String get() = ConnectionManager.MESSAGE_CHANNEL_BUSY

    /** The maker's app that holds the control channel when it is silent ("Bose"), for [ConnectionProblem.ChannelHeld]. */
    val competingApp: String get() = "Bose"

    /** The family this stack drives; the manager stands down for a device [DeviceFamily.resolve] puts in another one. */
    val family: DeviceFamily

    /**
     * Open a session to [device] and prove it answers (so a socket that opens but stays silent is
     * reported as a busy or wrong channel here rather than after CONNECTED is published). Throws
     * [RfcommConnectException] with `looksBusy` when it opened but nothing answered, or any other
     * exception when it could not open. On failure nothing is left open.
     */
    suspend fun open(device: BluetoothDevice, name: String?, prefs: DevicePrefs, scope: CoroutineScope): C

    /** Wrap an already-open transport (demo mode: no Bluetooth, no probe). */
    fun adopt(transport: ByteTransport, scope: CoroutineScope): C
}

/**
 * The Bose BMAP family. The channel is probed, not assumed: within one [open] the factory tries
 * the learned channel, the model's channel guessed from the Bluetooth name, then 2, 8, 9 and the
 * two SDP lookups ([BmapCandidates.order]), and keeps the first that passes the strict
 * [LivenessProbe]. Why: two QC Headphones units put BMAP on channel 8 and 9 respectively and
 * other channels accept the socket without speaking BMAP (bosectl #22/#26/#44). The winner is
 * remembered per device; when the learned one stops working the walk falls through and the new
 * winner replaces it.
 *
 * LE Audio (0.4.0): headphones the phone reaches over LE only appear as a separate "LE-Bose …"
 * device with no classic link. The factory then also tries BMAP over an LE L2CAP channel
 * ([BmapCandidate.LeCoc], as the Bose app does), first when the phone is on LE Audio, and opens
 * the RFCOMM candidates on the classic twin of an LE identity ([LeAudioTwins]).
 */
class BoseLinkFactory internal constructor(
    private val search: BmapChannelSearch,
    /** For the GATT lookup of the LE channel; null (tests) leaves LE out. */
    private val context: Context? = null,
    /** Twins, device types and LE Audio state; null (tests) treats the device as classic only. */
    private val finder: DeviceFinder? = null,
) : LinkFactory<BmapConnection> {
    constructor() : this(BmapChannelSearch())
    constructor(context: Context, finder: DeviceFinder) : this(BmapChannelSearch(), context.applicationContext, finder)

    override val family: DeviceFamily get() = DeviceFamily.BOSE

    /** Which device to open each kind of candidate on. */
    private class Route(val classic: BluetoothDevice?, val le: BluetoothDevice?, val preferLe: Boolean)

    override suspend fun open(device: BluetoothDevice, name: String?, prefs: DevicePrefs, scope: CoroutineScope): BmapConnection {
        val address = device.address
        val learned = prefs.candidateFor(address)
        val route = routeFor(device, name)
        val candidates = BmapCandidates.order(
            learned,
            BoseModel.fromName(name)?.rfcommChannel,
            hasClassic = route.classic != null,
            fixedChannels = DeviceFamily.looksLikeBose(name) || learned != null || prefs.knownFamily(address) == DeviceFamily.BOSE,
            hasLe = route.le != null,
            preferLe = route.preferLe,
        )
        BtLog.d(
            "BMAP candidates for $address: ${candidates.joinToString()} (learned: ${learned ?: "none"}; " +
                "classic ${route.classic?.address ?: "none"}, LE ${route.le?.address ?: "none"}${if (route.preferLe) ", LE first" else ""})",
        )
        // A socket that opens but never answers BMAP is another app holding the channel or the wrong
        // channel; find out now rather than after publishing CONNECTED with empty state. The search
        // throws RfcommConnectException(looksBusy) when something opened but nothing answered, which
        // the manager turns into the busy message. It closes every socket it does not return.
        val found = search.find(candidates, scope) { candidate ->
            if (candidate == BmapCandidate.LeCoc) openLe(route.le!!)
            else RfcommTransport.connectCandidate(route.classic!!, candidate)
        }
        if (found.candidate != learned) {
            try {
                prefs.saveCandidate(address, found.candidate)
            } catch (t: Throwable) {
                found.connection.close()
                throw t
            }
        }
        return found.connection
    }

    private suspend fun routeFor(device: BluetoothDevice, name: String?): Route {
        val finder = finder ?: return Route(classic = device, le = null, preferLe = false)
        val type = finder.typeOf(device)
        val twin = finder.twinsOf(device.address).firstOrNull()
        val isLeIdentity = type == BluetoothDevice.DEVICE_TYPE_LE || LeAudioTwins.isLeName(name)
        return if (isLeIdentity) {
            // No RFCOMM on an LE-only address: the classic candidates go to its twin, if bonded.
            Route(classic = twin?.takeIf { finder.typeOf(it) != BluetoothDevice.DEVICE_TYPE_LE }, le = device.takeIf { context != null }, preferLe = true)
        } else {
            val le = (twin ?: device.takeIf { type == BluetoothDevice.DEVICE_TYPE_DUAL })?.takeIf { context != null }
            Route(classic = device, le = le, preferLe = le != null && finder.isLeAudioConnected(device.address))
        }
    }

    /** GATT lookup of the PSM, then the L2CAP socket; the GATT client lives as long as the socket. */
    @SuppressLint("MissingPermission") // the manager checked BLUETOOTH_CONNECT; close() is wrapped in runCatching
    private suspend fun openLe(device: BluetoothDevice): ByteTransport {
        val ctx = context ?: throw RfcommConnectException("LE channel unavailable", looksBusy = false)
        val lookup = BmapPsmReader.read(ctx, device)
        return RfcommTransport.connectLeCoc(device, lookup.psm) { runCatching { lookup.gatt.close() } }
    }

    override fun adopt(transport: ByteTransport, scope: CoroutineScope): BmapConnection = BmapConnection(transport, scope)
}
