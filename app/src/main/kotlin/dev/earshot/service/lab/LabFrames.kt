package dev.earshot.service.lab

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.RawFrame
import dev.earshot.protocol.toHex
import kotlinx.coroutines.CancellationException

/*
 * The Lab scanner, one object per stack (BmapLab, SoundcoreLab, SennheiserLab, BragiLab, SonyLab).
 * Each one is Android-free so the end-to-end tests run it against the virtual devices, and has
 * two parts:
 *  - profile(...): a LabProfile built from the stack's documented read list in :protocol
 *    (BragiReads, SoundcoreReads, SennheiserReads, SonyReads; BMAP's GET sweep), grouped the way
 *    the protocol groups its commands;
 *  - scan(conn, groupIds, deep): one request per read, each checked against the stack's own write
 *    guard as a read before it is sent (the connection checks the guard again on its single write
 *    path), one LabReading per read.
 *
 * Adding a stack: list its reads next to its write guard in :protocol (with a test that every one
 * passes the guard as a read and none is blocked), add a `<Stack>Lab` here with profile() and
 * scan(), override labProfile() / scanLab() in its repository, and add an end-to-end test against
 * its virtual device with a spy transport. The UI and the router need no change.
 */

/** The frames a scan reports for one read: the reply, or why there is none. */
internal object LabFrames {
    const val RESPONSE = "RESPONSE"
    const val ERROR = "ERROR"

    fun response(group: Int, command: Int, payload: ByteArray) =
        RawFrame(group, command, RESPONSE, payload.toHex(), isError = false)

    fun error(group: Int, command: Int, why: String?, payloadHex: String = "") =
        RawFrame(group, command, ERROR, payloadHex, isError = true, errorName = why)

    /** Shown for a read the guard did not accept; by construction of the read lists this never happens. */
    fun refused(group: Int, command: Int) = error(group, command, "not sent: not a read on this headset's allow-list")

    /**
     * Run one read; a refusal, a timeout or an error reply becomes an error frame and the scan goes
     * on. A closed link ends the scan (rethrown): nothing further can answer.
     */
    suspend fun attempt(isOpen: () -> Boolean, group: Int, command: Int, read: suspend () -> RawFrame): RawFrame = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HeadphoneCommandException) {
        if (!isOpen()) throw e
        error(group, command, e.message)
    }
}
