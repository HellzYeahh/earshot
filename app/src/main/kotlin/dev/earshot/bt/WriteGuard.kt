package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket

/**
 * The one safety net every outgoing frame passes through ([BmapConnection.writeSpaced]).
 *
 * Why: the Bose app's tables show that block 3 is FirmwareUpdate and block 18 is Authentication.
 * A START on `[3.2]` opens a firmware-update session (bosectl's notes mistook it for a noise
 * control), `[3.x]` Run applies an image and reboots, Reset erases a staged image; `[7.5]`
 * FactoryDefault, `[7.6]` ShipMode, `[7.11]` Reset and `[4.7]` ClearDeviceList are destructive.
 * None of these have a place in a headphone controller, so they are refused here regardless of
 * which feature (or Lab console) produced the frame. GETs stay allowed: reading is harmless.
 */
object WriteGuard {
    private val readOnlyBlocks = setOf(3, 18)
    private val forbiddenFunctions = setOf(7 to 5, 7 to 6, 7 to 11, 4 to 7)

    /** Null when the frame may be sent, otherwise the user-readable reason it was refused. */
    fun refusalReason(packet: BmapPacket): String? {
        if (packet.operator == BmapOperator.GET) return null
        if (packet.block in readOnlyBlocks) {
            val what = if (packet.block == 3) "firmware update" else "authentication"
            return "Blocked for safety: Earshot never writes to the $what block (${packet.address})"
        }
        if ((packet.block to packet.function) in forbiddenFunctions) {
            return "Blocked for safety: ${packet.address} would reset or wipe the headphones"
        }
        return null
    }

    fun check(packet: BmapPacket) {
        refusalReason(packet)?.let { throw HeadphoneCommandException(it) }
    }
}
