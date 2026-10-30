package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothSocketSettings
import android.os.Build
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One RFCOMM socket to the headphones' BMAP channel.
 *
 * Connecting is where phones and units differ, so [connectCandidate] opens one [BmapCandidate]
 * and [BoseLinkFactory] walks an ordered list of them ([BmapCandidates.order]):
 *  - `createInsecureRfcommSocket(channel)` via reflection: goes straight to the channel, no SDP.
 *    Quiet Rebellion found this is the only strategy that lands on the right channel on MIUI,
 *    whose stack resolves Bose's UUID to channel 14 (a status beacon) or 0. The channel is 2 on
 *    the QC Ultra 2 family, 8 on the QC45, and 8 *or* 9 on the QC Headphones (bosectl #22/#26).
 *  - the SPP UUID through SDP: the public API, and what resolves prince to its own 8 or 9.
 *  - Bose's own BMAP UUID through SDP: last resort, same SDP caveat.
 *  - an LE L2CAP channel ([connectLeCoc]) when the phone reaches the headphones over LE Audio only.
 * Whichever opens still has to pass the strict [LivenessProbe] before it counts.
 *
 * Reading is done by a dedicated thread blocked in `read()`. The alternative, polling
 * `available()`, costs latency on every reply and CPU while idle for hours in a background
 * service. The thread is only ever ended by closing the socket: on several OEM stacks
 * `read()` ignores `Thread.interrupt()`, so nothing here relies on interrupts.
 */
class RfcommTransport private constructor(
    private val socket: BluetoothSocket,
    /** Which connect strategy succeeded, for logs and the bug reports that will follow. */
    val strategy: String,
    /** The RFCOMM channel when a reflection strategy opened it; null when SDP chose. */
    val channel: Int?,
    /** Extra cleanup when the socket closes: the GATT client of an LE channel ([connectLeCoc]). */
    private val onClose: (() -> Unit)? = null,
) : ByteTransport {

    private val input = socket.inputStream
    private val output = socket.outputStream
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    private val readerStarted = AtomicBoolean(false)

    @Volatile
    private var closed = false

    /** The greeting drain already saw end of stream: the reader must not block in another read(). */
    @Volatile
    private var streamEnded = false

    override val isConnected: Boolean get() = !closed && socket.isConnected

    override val incoming: Flow<ByteArray> = flow {
        check(readerStarted.compareAndSet(false, true)) { "incoming can only be collected once" }
        startReader()
        emitAll(chunks)
    }

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Closing the socket is what unblocks the reader thread's read().
        runCatching { socket.close() }
        chunks.close()
        onClose?.let { runCatching(it) }
    }

    private fun startReader() {
        thread(name = "Earshot-rfcomm-reader", isDaemon = true) {
            val cause = if (streamEnded) {
                if (closed) null else IOException(END_OF_STREAM)
            } else {
                readUntilEnd(input, { chunks.trySend(it) }, { closed })
            }
            chunks.close(cause)
            BtLog.d("reader thread finished (closed=$closed${if (cause != null) ", ${cause.message}" else ""})")
        }
    }

    /**
     * Some firmware revisions push a burst of frames right after the channel opens. Read and
     * discard whatever arrives in the first few hundred milliseconds so the first request does
     * not get a stale frame as its reply. Uses `available()` with a deadline because, with no
     * reader thread yet, a blocking `read()` on a silent channel could never be timed out.
     *
     * The quiet deadline is only honoured between frames: while the bytes so far end mid-frame
     * the drain keeps going (up to [GREETING_MAX_MS]), and whatever still has not framed by
     * then is handed to the connection as its first chunk instead of being thrown away. Cutting
     * a frame in half here used to leave the frame buffer a headless tail that desynced the
     * whole session.
     *
     * Returns the bytes seen (null when silent) so SDP strategies can sanity-check the channel.
     */
    private suspend fun drainGreeting(): ByteArray? {
        val greeting = collectGreeting(input)
        val bytes = greeting.bytes
        if (greeting.endOfStream) {
            // The link went down while the channel opened. The reader then closes `incoming`
            // with END_OF_STREAM at once, so the liveness probe fails fast instead of waiting.
            streamEnded = true
            BtLog.w("greeting: end of stream after ${bytes.size} bytes (link dropped)")
        }
        if (bytes.isEmpty()) {
            BtLog.d("greeting: silent")
            return null
        }
        val decoded = BmapPacket.decodeStream(bytes)
        // Size and one summary per frame, never the hex (audit R09): a greeting burst can carry
        // block-4 pushes (paired devices by MAC and name), and BtLog lines reach the connection
        // report users paste into bug reports. describeForLog prints a payload only where it is
        // known to be harmless, as BmapConnection does for every other frame.
        val frames = decoded.packets.joinToString(limit = GREETING_LOG_FRAMES) { it.describeForLog() }
        BtLog.d("greeting: ${bytes.size} bytes, ${decoded.packets.size} frames${if (frames.isEmpty()) "" else ": $frames"}")
        val consumed = decoded.consumed
        if (consumed < bytes.size) {
            // The rest of this frame will arrive on the reader; the connection's frame buffer
            // completes it (or flags it as a desync if it was never BMAP). The channel is
            // unlimited and `incoming` emits what is queued before the reader's own chunks.
            BtLog.d("greeting: ${bytes.size - consumed} bytes did not frame in time, passing them to the connection")
            chunks.trySend(bytes.copyOfRange(consumed, bytes.size))
        }
        return bytes
    }

    /** What the greeting drain read, and whether the stream ended (read() returned -1) under it. */
    internal class Greeting(val bytes: ByteArray, val endOfStream: Boolean)

    companion object {
        const val CHANNEL = 2
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val BMAP_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

        /**
         * The close cause of a link that ended by itself. Apps targeting Android 17 (API 37) get
         * -1 from an RFCOMM socket's read() when the far end closes or the link drops, where older
         * targets got an IOException. Closing `incoming` with this keeps a drop looking like a
         * drop: [BmapConnection.closeCause] is set and the log does not say "closed locally".
         */
        internal const val END_OF_STREAM = "RFCOMM end of stream"

        /**
         * The reader thread's loop, on a plain [InputStream] so a unit test can drive it. Hands
         * every chunk to [emit] until the stream ends, then returns the cause to close `incoming`
         * with: null when [isClosed] (we closed the socket ourselves), otherwise the link dropped.
         * Before target 37 a drop arrives as an IOException, from target 37 as read() == -1; both
         * end up as an IOException cause, so callers cannot tell the two targets apart.
         */
        internal fun readUntilEnd(input: InputStream, emit: (ByteArray) -> Unit, isClosed: () -> Boolean): Throwable? {
            val buf = ByteArray(READ_BUFFER)
            return try {
                while (!isClosed()) {
                    val n = input.read(buf)
                    if (n < 0) return if (isClosed()) null else IOException(END_OF_STREAM)
                    if (n > 0) emit(buf.copyOf(n))
                }
                null
            } catch (e: IOException) {
                // After close() an IOException is the expected way out; otherwise the link dropped.
                if (isClosed()) null else e
            } catch (e: Throwable) {
                // An OEM stack quirk (a NullPointerException deep in the socket's read path,
                // say) must not take the process down; surface it as a dropped link instead.
                BtLog.e("reader thread died", e)
                if (isClosed()) null else IOException("reader failed", e)
            }
        }

        /**
         * The greeting drain's read loop (see [drainGreeting]), on a plain [InputStream] for tests.
         * Stops at once on read() == -1: from target 37 that is how a dropped link shows up, and
         * `available()` on a dead socket is not guaranteed to fall to 0, so the loop would
         * otherwise spin until [GREETING_MAX_MS].
         */
        internal suspend fun collectGreeting(input: InputStream): Greeting {
            val out = ByteArrayOutputStream()
            val start = System.currentTimeMillis()
            var deadline = start + GREETING_WAIT_MS
            while (System.currentTimeMillis() - start < GREETING_MAX_MS) {
                if (System.currentTimeMillis() >= deadline && !endsMidFrame(out.toByteArray())) break
                val available = withContext(Dispatchers.IO) { input.available() }
                if (available > 0) {
                    val buf = ByteArray(available.coerceAtMost(READ_BUFFER))
                    val n = withContext(Dispatchers.IO) { input.read(buf) }
                    if (n < 0) return Greeting(out.toByteArray(), endOfStream = true)
                    if (n > 0) out.write(buf, 0, n)
                    deadline = System.currentTimeMillis() + GREETING_GAP_MS
                } else {
                    delay(GREETING_POLL_MS)
                }
            }
            return Greeting(out.toByteArray(), endOfStream = false)
        }

        /** True when [bytes] end in a partial frame that could still complete (not a non-BMAP header). */
        private fun endsMidFrame(bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return false
            val consumed = BmapPacket.decodeStream(bytes).consumed
            if (consumed == bytes.size) return false
            // The operator nibble is the third header byte; before it arrives anything is possible.
            return bytes.size - consumed < 3 || BmapOperator.fromCode(bytes[consumed + 2].toInt() and 0x0F) != null
        }

        private const val READ_BUFFER = 4096
        private const val GREETING_WAIT_MS = 400L
        private const val GREETING_GAP_MS = 150L
        private const val GREETING_MAX_MS = 1_500L
        private const val GREETING_POLL_MS = 25L

        /** Frames named in the greeting's log line; a wrong channel's log stream can decode to hundreds. */
        private const val GREETING_LOG_FRAMES = 12

        /**
         * [checkGreeting]: a non-BMAP greeting means a wrong channel, so move on. Off for fixed
         * channels, where a silent or odd greeting is left to the liveness probe.
         */
        private class Strategy(
            val name: String,
            val checkGreeting: Boolean,
            val channel: Int? = null,
            /** Wait for and hand on a BMAP greeting burst. Off for other protocols, whose bytes must reach their own decoder untouched. */
            val drainGreeting: Boolean = true,
            val open: (BluetoothDevice) -> BluetoothSocket,
        )

        @SuppressLint("MissingPermission", "DiscouragedPrivateApi")
        private fun reflection(channel: Int, checkGreeting: Boolean) =
            Strategy("reflection ch$channel", checkGreeting, channel) { device ->
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, channel) as BluetoothSocket
            }

        @SuppressLint("MissingPermission")
        private fun strategy(candidate: BmapCandidate): Strategy = when (candidate) {
            is BmapCandidate.Channel -> reflection(candidate.number, checkGreeting = false)
            BmapCandidate.SdpSpp -> Strategy("SDP SPP", checkGreeting = true) { it.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
            BmapCandidate.SdpBmap -> Strategy("SDP BMAP", checkGreeting = true) { it.createInsecureRfcommSocketToServiceRecord(BMAP_UUID) }
            // Needs a GATT lookup first: BoseLinkFactory opens it through connectLeCoc.
            BmapCandidate.LeCoc -> throw IllegalArgumentException("the LE channel is not an RFCOMM candidate")
        }

        /**
         * Open one Bose control-channel [candidate]. Throws [RfcommConnectException] when it cannot
         * be opened (or an SDP lookup landed on a channel whose greeting is not BMAP), or
         * [SecurityException] when it was refused for permission reasons (the caller already
         * checked BLUETOOTH_CONNECT, so that means an OEM quirk). An open socket is not proof of
         * BMAP: the caller probes it.
         */
        suspend fun connectCandidate(device: BluetoothDevice, candidate: BmapCandidate): RfcommTransport =
            open(device, listOf(strategy(candidate)))

        /**
         * BMAP over an LE L2CAP connection-oriented channel on [psm] (read by [BmapPsmReader]), for
         * headphones the phone reaches over LE Audio only. Same frames as RFCOMM. The Bose app opens
         * it insecure, and on API 36+ through socket settings that require encryption (the LE Audio
         * link is bonded and encrypted already); the secure variant is the last try. [onClose] runs
         * when the socket closes, or right away if nothing opens.
         */
        @SuppressLint("MissingPermission")
        suspend fun connectLeCoc(device: BluetoothDevice, psm: Int, onClose: () -> Unit): RfcommTransport {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                onClose()
                throw RfcommConnectException("Controlling headphones over LE Audio needs Android 10 or newer", looksBusy = false)
            }
            val name = "LE CoC psm 0x%02x".format(psm)
            val strategies = buildList {
                if (Build.VERSION.SDK_INT >= 36) {
                    add(Strategy("$name (encrypted)", checkGreeting = false) { d ->
                        d.createUsingSocketSettings(
                            BluetoothSocketSettings.Builder()
                                .setSocketType(BluetoothSocket.TYPE_LE)
                                .setL2capPsm(psm)
                                .setEncryptionRequired(true)
                                .setAuthenticationRequired(false)
                                .build(),
                        )
                    })
                }
                add(Strategy(name, checkGreeting = false) { it.createInsecureL2capChannel(psm) })
                add(Strategy("$name (secure)", checkGreeting = false) { it.createL2capChannel(psm) })
            }
            return try {
                open(device, strategies, onClose)
            } catch (t: Throwable) {
                onClose()
                throw t
            }
        }

        /**
         * Open an RFCOMM socket to a service by UUID (channel resolved by SDP), as the Skullcandy
         * app does for the Crusher: the secure variant first (the headphones are bonded), then the
         * insecure one. Unlike [connect] there is no BMAP greeting check: a different protocol
         * answers here, and the caller probes it.
         */
        @SuppressLint("MissingPermission")
        suspend fun connectService(device: BluetoothDevice, uuid: UUID): RfcommTransport = open(
            device,
            listOf(
                Strategy("SDP secure $uuid", checkGreeting = false, drainGreeting = false) { it.createRfcommSocketToServiceRecord(uuid) },
                Strategy("SDP insecure $uuid", checkGreeting = false, drainGreeting = false) { it.createInsecureRfcommSocketToServiceRecord(uuid) },
            ),
        )

        @SuppressLint("MissingPermission")
        private suspend fun open(device: BluetoothDevice, strategies: List<Strategy>, onClose: (() -> Unit)? = null): RfcommTransport {
            // No cancelDiscovery() here: on API 31+ it (and isDiscovering) needs BLUETOOTH_SCAN,
            // which Earshot does not request; we never start discovery ourselves anyway.
            val failures = ArrayList<String>()
            var looksBusy = false
            var firstSecurity: SecurityException? = null
            var allSecurity = true

            for (strategy in strategies) {
                val socket = try {
                    strategy.open(device)
                } catch (e: Exception) {
                    allSecurity = false
                    failures += "${strategy.name}: cannot create socket (${e.describe()})"
                    BtLog.w("${strategy.name}: cannot create socket", e)
                    continue
                }
                try {
                    try {
                        socket.connectCancellable()
                    } catch (e: SecurityException) {
                        // MIUI's notifyPowerKeeper() throws SecurityException *after* the socket
                        // is up. If it is connected, carry on; otherwise it was a real refusal.
                        if (!socket.isConnected) throw e
                        BtLog.w("${strategy.name}: SecurityException after connect, socket is up; continuing")
                    }
                    val transport = RfcommTransport(socket, strategy.name, strategy.channel, onClose)
                    val greeting = if (strategy.drainGreeting) transport.drainGreeting() else null
                    if (strategy.checkGreeting && greeting != null && !looksLikeBmap(greeting)) {
                        // SDP sent us to another channel: channel 14 beacons `ff55...`, channel 22
                        // streams logs. Neither frames as BMAP, so try the next strategy.
                        transport.close()
                        allSecurity = false
                        failures += "${strategy.name}: connected but the channel is not BMAP"
                        BtLog.w("${strategy.name}: wrong channel (non-BMAP greeting), trying next")
                        continue
                    }
                    BtLog.i("connected to ${device.address} via ${strategy.name}")
                    return transport
                } catch (e: CancellationException) {
                    // Off this thread: close() on a socket whose connect() is still stuck can block,
                    // and the whole point of the timeout is to let the walk move on.
                    thread(name = "Earshot-rfcomm-close", isDaemon = true) { runCatching { socket.close() } }
                    throw e
                } catch (e: SecurityException) {
                    runCatching { socket.close() }
                    if (firstSecurity == null) firstSecurity = e
                    failures += "${strategy.name}: ${e.describe()}"
                    BtLog.w("${strategy.name}: SecurityException", e)
                } catch (e: Exception) {
                    runCatching { socket.close() }
                    allSecurity = false
                    if (isBusySignature(e)) looksBusy = true
                    failures += "${strategy.name}: ${e.describe()}"
                    BtLog.w("${strategy.name}: connect failed: ${e.describe()}")
                }
            }
            if (allSecurity && firstSecurity != null) throw firstSecurity
            throw RfcommConnectException("Could not open the control channel (${failures.joinToString("; ")})", looksBusy)
        }

        /**
         * `BluetoothSocket.connect()` blocks and cannot be interrupted, but it normally aborts when
         * the socket is closed from another thread. So on cancellation we close the socket (off the
         * caller's thread) and give the call a moment to fall out; if the stack still does not
         * react, the caller is released anyway ([runAbandonable]) so the 6 s connect timeout really
         * bounds each candidate.
         */
        private suspend fun BluetoothSocket.connectCancellable() =
            runAbandonable("Earshot-rfcomm-connect", abort = { close() }) { connect() }

        /**
         * Does a non-empty greeting read as BMAP from the headphones? Every frame that decodes
         * must use a device-side operator and a known function block; a greeting that decodes
         * to nothing is "wrong channel" too. The bar is this high because the channel-14 beacon
         * `ff 55 02 …` happens to decode as a SET_GET on block 0xFF, which the old "anything
         * decodes" check accepted.
         */
        internal fun looksLikeBmap(bytes: ByteArray): Boolean {
            val frames = BmapPacket.decodeStream(bytes).packets
            return frames.isNotEmpty() && frames.all(LivenessProbe::isDeviceFrame)
        }

        /**
         * What the stack says when channel 2 is already taken (typically by the Bose app). The
         * same strings also show up when the headphones are simply out of range, so the
         * manager only trusts this when the phone reports the headphones as connected.
         */
        private fun isBusySignature(e: Throwable): Boolean {
            val msg = e.message?.lowercase().orEmpty()
            return "refused" in msg || "socket might closed" in msg || "read ret: -1" in msg || "connection reset" in msg
        }

        private fun Throwable.describe(): String = describeForReport()
    }
}
