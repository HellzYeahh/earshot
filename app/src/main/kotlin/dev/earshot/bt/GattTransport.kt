package dev.earshot.bt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The few `BluetoothGatt` calls a [GattTransport] makes. Each returns false when Android refused to
 * start the operation; the result arrives later through the transport's `on…` callbacks. Kept free
 * of Android types so the setup sequence and the write path are unit-tested with a fake.
 */
interface GattPort {
    fun discoverServices(): Boolean
    fun requestHighPriority(): Boolean
    fun requestMtu(mtu: Int): Boolean

    /** The characteristic's property bits, or null when the service or characteristic is absent. */
    fun properties(service: UUID, characteristic: UUID): Int?

    /** Turn on notifications ([indicate] false) or indications locally and write the CCCD. */
    fun enableNotifications(service: UUID, characteristic: UUID, indicate: Boolean): Boolean

    fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean): Boolean

    /** Disconnect and release the GATT client. Idempotent. */
    fun close()
}

/** The GATT link could not be set up: no LE connection, the service is missing, a setup step was refused or timed out. */
class GattSetupException(message: String) : IOException(message)

/**
 * A vendor's byte pipe over Bluetooth LE GATT, as a [ByteTransport]: what an RFCOMM socket is for
 * the classic drivers. JBL's Harman stacks need it (104 of the 123 products in JBL's app connect
 * over BLE), and so do Airoha RACE on LE Audio, Sennheiser's CX models and Sony over LE Audio
 * (research "Top brand headset protocols").
 *
 * [open] runs the sequence JBL's app uses (JBL notes §2): wait for the LE connection, discover
 * services, ask for high connection priority, ask for [GattLinkSpec.mtu], then switch on each
 * notifying characteristic in order. Android allows one GATT operation at a time, so every
 * operation goes through one lock and waits for its callback (or its timeout) before the next.
 *
 * Writes are cut into ATT payloads of `MTU - 3` bytes ([GattChunks]) and each one waits for its
 * write callback, so a long frame arrives whole and in order. Notifications come out of
 * [incoming] in arrival order, one chunk per notification; reassembling frames is the vendor
 * codec's job, exactly as with a socket.
 *
 * Callbacks come from Binder threads; everything they touch is a deferred, a channel or volatile.
 */
class GattTransport(
    private val port: GattPort,
    private val spec: GattLinkSpec,
    private val timeouts: Timeouts = Timeouts(),
) : ByteTransport {

    data class Timeouts(
        val connectMs: Long = 10_000L,
        val discoverMs: Long = 10_000L,
        val mtuMs: Long = 3_000L,
        val descriptorMs: Long = 3_000L,
        val writeMs: Long = 3_000L,
    )

    private enum class Op { DISCOVER, MTU, DESCRIPTOR, WRITE }

    private class Pending(val op: Op) {
        val status = CompletableDeferred<Int>()
    }

    private val connected = CompletableDeferred<Unit>()
    private val opLock = Mutex()
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    private val collected = AtomicBoolean(false)
    private val notifying = spec.notify.toSet()

    @Volatile
    private var pending: Pending? = null

    @Volatile
    private var closed = false

    @Volatile
    private var ready = false

    @Volatile
    private var withResponse = spec.writeWithResponse

    /** The ATT MTU the headset granted; [GattLinkSpec.MIN_MTU] until (or unless) it grants more. */
    @Volatile
    var mtu: Int = GattLinkSpec.MIN_MTU
        private set

    override val isConnected: Boolean get() = ready && !closed

    override val incoming: Flow<ByteArray> = flow {
        check(collected.compareAndSet(false, true)) { "incoming can only be collected once" }
        emitAll(chunks)
    }

    /**
     * Set the link up; returns once [incoming] carries the headset's notifications. Throws
     * [GattSetupException] (the transport is closed then) when a required step fails.
     */
    suspend fun open() {
        try {
            withTimeoutOrNull(timeouts.connectMs) { connected.await() }
                ?: throw GattSetupException("no LE connection within ${timeouts.connectMs} ms")
            val discovered = run(Op.DISCOVER, timeouts.discoverMs) { port.discoverServices() }
            if (discovered != GATT_SUCCESS) throw GattSetupException("service discovery failed (status $discovered)")
            // A hint only: Android may ignore it, and the link works without it, just slower.
            runCatching { port.requestHighPriority() }
            negotiateMtu()
            pickWriteType()
            for (characteristic in spec.notify) enableNotifications(characteristic)
            ready = true
            BtLog.d("GATT ${spec.name}: ready, MTU $mtu, write ${if (withResponse) "with" else "without"} response")
        } catch (t: Throwable) {
            close()
            // A drop mid-setup surfaces as a plain IOException from the op in flight; callers treat
            // every setup failure as GattSetupException.
            if (t is IOException && t !is GattSetupException) throw GattSetupException(t.message ?: "the LE link dropped during setup")
            throw t
        }
    }

    /** A refused or silent MTU request keeps the 23-byte minimum: smaller chunks, same bytes. */
    private suspend fun negotiateMtu() {
        val status = try {
            run(Op.MTU, timeouts.mtuMs) { port.requestMtu(spec.mtu) }
        } catch (e: GattSetupException) {
            BtLog.w("GATT ${spec.name}: ${e.message}; staying at MTU $mtu")
            return
        }
        if (status != GATT_SUCCESS) BtLog.w("GATT ${spec.name}: MTU request refused (status $status); staying at MTU $mtu")
    }

    /** The spec's write type when the characteristic offers it, else the other one it does offer. */
    private fun pickWriteType() {
        val props = port.properties(spec.service, spec.write)
            ?: throw GattSetupException("the ${spec.name} service or its write characteristic is missing")
        val canWithResponse = props and PROPERTY_WRITE != 0
        val canWithout = props and PROPERTY_WRITE_NO_RESPONSE != 0
        withResponse = when {
            spec.writeWithResponse && canWithResponse -> true
            !spec.writeWithResponse && canWithout -> false
            canWithResponse -> true
            canWithout -> false
            else -> throw GattSetupException("the ${spec.name} write characteristic can't be written")
        }
        if (withResponse != spec.writeWithResponse) BtLog.w("GATT ${spec.name}: write type differs from the spec, using what the characteristic offers")
    }

    private suspend fun enableNotifications(characteristic: UUID) {
        val props = port.properties(spec.service, characteristic)
            ?: throw GattSetupException("the ${spec.name} notify characteristic $characteristic is missing")
        val indicate = when {
            props and PROPERTY_NOTIFY != 0 -> false
            props and PROPERTY_INDICATE != 0 -> true
            else -> throw GattSetupException("$characteristic neither notifies nor indicates")
        }
        val status = run(Op.DESCRIPTOR, timeouts.descriptorMs) { port.enableNotifications(spec.service, characteristic, indicate) }
        if (status != GATT_SUCCESS) throw GattSetupException("switching on notifications for $characteristic failed (status $status)")
    }

    override suspend fun write(bytes: ByteArray) {
        if (!ready || closed) throw IOException("the ${spec.name} GATT link is not open")
        for (chunk in GattChunks.split(bytes, mtu)) {
            val status = try {
                run(Op.WRITE, timeouts.writeMs) { port.write(spec.service, spec.write, chunk, withResponse) }
            } catch (e: GattSetupException) {
                throw IOException(e.message, e)
            }
            if (status != GATT_SUCCESS) throw IOException("GATT write failed (status $status)")
        }
    }

    /** Start one operation and wait for its callback's status. One at a time, as Android requires. */
    private suspend fun run(op: Op, timeoutMs: Long, start: () -> Boolean): Int = opLock.withLock {
        if (closed) throw IOException("the ${spec.name} GATT link is closed")
        val p = Pending(op)
        pending = p
        try {
            if (!start()) throw GattSetupException("Android refused ${op.name.lowercase()} on ${spec.name}")
            withTimeoutOrNull(timeoutMs) { p.status.await() }
                ?: throw GattSetupException("${op.name.lowercase()} on ${spec.name}: no answer in $timeoutMs ms")
        } finally {
            pending = null
        }
    }

    private fun complete(op: Op, status: Int) {
        // A late callback for an operation that already timed out must not answer the next one.
        pending?.takeIf { it.op == op }?.status?.complete(status)
    }

    // ---- Callbacks (Binder threads) --------------------------------------------------------

    fun onConnectionStateChange(status: Int, isConnected: Boolean) {
        if (isConnected && status == GATT_SUCCESS) {
            connected.complete(Unit)
            return
        }
        if (!connected.isCompleted) {
            connected.completeExceptionally(GattSetupException("the LE connection failed (status $status)"))
            return
        }
        if (closed) return
        // The headset went away mid-session: end the stream with an error, as a dropped socket does.
        BtLog.i("GATT ${spec.name}: link dropped (status $status)")
        pending?.status?.completeExceptionally(IOException("the LE link dropped (status $status)"))
        chunks.close(IOException("the LE link dropped (status $status)"))
        closed = true
        runCatching { port.close() }
    }

    fun onServicesDiscovered(status: Int) = complete(Op.DISCOVER, status)

    fun onMtuChanged(mtu: Int, status: Int) {
        if (status == GATT_SUCCESS) this.mtu = mtu.coerceIn(GattLinkSpec.MIN_MTU, GattLinkSpec.MAX_MTU)
        complete(Op.MTU, status)
    }

    fun onDescriptorWrite(status: Int) = complete(Op.DESCRIPTOR, status)

    fun onCharacteristicWrite(status: Int) = complete(Op.WRITE, status)

    fun onCharacteristicChanged(characteristic: UUID, value: ByteArray) {
        if (closed || characteristic !in notifying || value.isEmpty()) return
        chunks.trySend(value.copyOf())
    }

    override fun close() {
        if (closed) {
            // The link may have dropped before GattLink handed the port its client (callbacks can
            // beat connectGatt's return); closing the port again releases it now. Idempotent.
            runCatching { port.close() }
            return
        }
        closed = true
        ready = false
        pending?.status?.completeExceptionally(IOException("the ${spec.name} GATT link is closed"))
        if (!connected.isCompleted) connected.completeExceptionally(GattSetupException("closed before the LE connection came up"))
        chunks.close()
        runCatching { port.close() }
    }

    companion object {
        const val GATT_SUCCESS = 0

        // BluetoothGattCharacteristic.PROPERTY_*, repeated so the transport stays Android-free.
        const val PROPERTY_WRITE_NO_RESPONSE = 0x04
        const val PROPERTY_WRITE = 0x08
        const val PROPERTY_NOTIFY = 0x10
        const val PROPERTY_INDICATE = 0x20
    }
}

/** How a frame is cut into ATT writes. Pure, for the tests. */
object GattChunks {
    /** Opcode (1) and handle (2) take three bytes of every ATT write. */
    const val ATT_HEADER = 3

    fun payloadSize(mtu: Int): Int = (mtu - ATT_HEADER).coerceAtLeast(GattLinkSpec.MIN_MTU - ATT_HEADER)

    fun split(bytes: ByteArray, mtu: Int): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val size = payloadSize(mtu)
        return (bytes.indices step size).map { start -> bytes.copyOfRange(start, minOf(start + size, bytes.size)) }
    }
}
