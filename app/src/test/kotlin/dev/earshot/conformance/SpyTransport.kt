package dev.earshot.conformance

import dev.earshot.bt.ByteTransport

/**
 * A virtual device's own transport, recording every byte the phone writes. The conformance checks
 * decode what it saw with the stack's own frame buffer ([ConformanceRig.decode]), so "nothing but
 * reads left the phone" and "a refused write never reached the socket" are proven on the bytes.
 *
 * A copy of the private spy in `LabScanEndToEndTest` (F2 phase 1 keeps that test untouched; the
 * two can be merged later).
 */
class SpyTransport(private val inner: ByteTransport) : ByteTransport {
    private val written = mutableListOf<ByteArray>()
    override val isConnected: Boolean get() = inner.isConnected
    override val incoming = inner.incoming
    override suspend fun write(bytes: ByteArray) {
        synchronized(written) { written += bytes.copyOf() }
        inner.write(bytes)
    }
    override fun close() = inner.close()

    /** Forget what was written so far: the next [all] covers only what follows. */
    fun clear() = synchronized(written) { written.clear() }

    /** Everything written since the last [clear], concatenated as the socket would carry it. */
    fun all(): ByteArray = synchronized(written) { written.fold(ByteArray(0)) { a, b -> a + b } }
}
