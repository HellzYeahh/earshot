/*
 * BluetoothTransport.kt – Android RFCOMM socket transport for BMAP devices.
 * Port of RfcommTransport.cs / python/pybmap/transport.py.
 *
 * Primary: native Linux AF_BLUETOOTH socket via JNI (rfcomm.c) – bypasses
 *          MIUI/OEM Bluetooth stacks that force SDP → channel 0.
 *          Equivalent to: Python socket.connect((mac, 2))
 *                         Windows SOCKADDR_BTH { serviceClassId=Guid.Empty, port=2 }
 * Fallback: Android BluetoothSocket via SDP (may land on wrong channel).
 */
package net.quietrebellion

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "BoseCtl"

class BluetoothTransport private constructor(
    private val inp: InputStream,
    private val out: OutputStream,
    private val closer: Closeable,
) : Closeable {
    // Serialises concurrent callers (service poll + MainActivity poll share one socket).
    private val mutex = Mutex()

    companion object {
        const val SEND_RECV_DELAY_MS      = 200L
        const val FIRST_PACKET_TIMEOUT_MS = 3_000L
        const val DRAIN_GAP_TIMEOUT_MS    = 400L

        /**
         * Connect to RFCOMM channel 2 directly.
         * Primary: native Linux AF_BLUETOOTH via JNI (rfcomm.c).
         * Fallback: Android BluetoothSocket SDP.
         */
        @android.annotation.SuppressLint("MissingPermission")
        suspend fun connectDirect(device: BluetoothDevice): BluetoothTransport {
            // Strategy 1: createInsecureRfcommSocket(int) via reflection.
            // Goes through the privileged Bluetooth service → not blocked by SELinux.
            // Was confirmed "allowed" on MIUI; the SecurityException from notifyPowerKeeper
            // is MIUI-internal and thrown AFTER connect() succeeds (check isConnected).
            try {
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.java)
                val socket = m.invoke(device, QcUltra2.RFCOMM_CHANNEL) as android.bluetooth.BluetoothSocket
                Log.d(TAG, "connectDirect: reflection socket created, connecting ch=${QcUltra2.RFCOMM_CHANNEL}…")
                try {
                    withContext(Dispatchers.IO) { socket.connect() }
                    Log.d(TAG, "connectDirect: reflection connect() ok isConnected=${socket.isConnected}")
                } catch (e: SecurityException) {
                    // MIUI notifyPowerKeeper() throws SecurityException AFTER connect succeeds.
                    // Treat as success if socket reports isConnected.
                    Log.w(TAG, "connectDirect: SecurityException (notifyPowerKeeper?), isConnected=${socket.isConnected}: ${e.message}")
                    if (!socket.isConnected) { socket.close(); throw e }
                }
                val t = BluetoothTransport(socket.inputStream, socket.outputStream, socket)
                t.drainGreeting()
                return t
            } catch (e: SecurityException) {
                throw e  // re-throw real permission failures
            } catch (e: Exception) {
                Log.w(TAG, "connectDirect: reflection failed (${e::class.simpleName}: ${e.message}), trying JNI")
            }

            // Strategy 2: native JNI (Linux AF_BLUETOOTH) – blocked by SELinux on unrooted devices.
            try {
                val mac = device.address
                Log.d(TAG, "connectDirect: JNI AF_BLUETOOTH $mac ch=${QcUltra2.RFCOMM_CHANNEL}")
                val fd = withContext(Dispatchers.IO) { RfcommJni.connect(mac, QcUltra2.RFCOMM_CHANNEL) }
                if (fd >= 0) {
                    Log.d(TAG, "connectDirect: JNI ok fd=$fd")
                    val pfd = RfcommJni.wrap(fd)
                    val t = BluetoothTransport(FileInputStream(pfd.fileDescriptor), FileOutputStream(pfd.fileDescriptor), pfd)
                    t.drainGreeting()
                    return t
                }
                Log.w(TAG, "connectDirect: JNI errno=${-fd}")
            } catch (e: Throwable) {
                Log.w(TAG, "connectDirect: JNI failed: ${e.message}")
            }

            // Strategy 3: SDP fallback – may land on wrong channel (MIUI resolves BMAP_UUID to SPP ch14).
            Log.w(TAG, "connectDirect: SDP fallback – channel may be wrong")
            val socket = device.createInsecureRfcommSocketToServiceRecord(QcUltra2.BMAP_UUID)
            try {
                withContext(Dispatchers.IO) { socket.connect() }
            } catch (e: SecurityException) {
                if (!socket.isConnected) { socket.close(); throw e }
            } catch (e: Exception) {
                socket.close(); throw e
            }
            Log.d(TAG, "connectDirect: SDP ok isConnected=${socket.isConnected}")
            val t = BluetoothTransport(socket.inputStream, socket.outputStream, socket)
            t.drainGreeting()
            return t
        }
    }

    suspend fun sendRecv(packet: ByteArray, drain: Boolean = false): ByteArray = mutex.withLock {
        withContext(Dispatchers.IO) { out.write(packet); out.flush() }
        delay(SEND_RECV_DELAY_MS)

        // Same available()-polling as drainGreeting – MIUI read() not interruptible.
        val first = withTimeout(FIRST_PACKET_TIMEOUT_MS) {
            while (inp.available() == 0) delay(20)
            withContext(Dispatchers.IO) { readChunk() }
        }
        if (!drain) return first

        val all = first.toMutableList()
        while (true) {
            val deadline = System.currentTimeMillis() + DRAIN_GAP_TIMEOUT_MS
            var got: ByteArray? = null
            while (System.currentTimeMillis() < deadline) {
                if (inp.available() > 0) { got = withContext(Dispatchers.IO) { readChunk() }; break }
                delay(20)
            }
            all.addAll((got ?: break).toList())
        }
        return all.toByteArray()
    }

    private fun readChunk(): ByteArray {
        val buf = ByteArray(4096)
        val n = inp.read(buf)
        return if (n > 0) buf.copyOfRange(0, n) else ByteArray(0)
    }

    /** Drain greeting bytes sent on connect. Polls available() to avoid blocking read() on MIUI. */
    private suspend fun drainGreeting() {
        // MIUI BluetoothSocket.inputStream.read() ignores Thread.interrupt(),
        // so withTimeoutOrNull hangs. Use available()-based polling instead.
        // Ceiling: 50ms poll granularity; device greeting must arrive within 400ms.
        val deadline = System.currentTimeMillis() + DRAIN_GAP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (inp.available() > 0) {
                val g = withContext(Dispatchers.IO) { readChunk() }
                Log.d(TAG, "drainGreeting: ${g.joinToString("") { "%02x".format(it) }}")
                return
            }
            delay(50)
        }
        Log.d(TAG, "drainGreeting: silent (correct channel)")
    }


    override fun close() = closer.close()
}

