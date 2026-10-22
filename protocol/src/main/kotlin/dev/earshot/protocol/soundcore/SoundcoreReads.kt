package dev.earshot.protocol.soundcore

/** One documented Soundcore read for the Lab: a command id (code < 0x80) sent with an empty body. */
class SoundcoreRead(val command: Int, val name: String) {
    val category: Int get() = command shr 8
    fun packet(): SoundcorePacket = SoundcorePacket(command)
}

/**
 * The Lab's read list for Soundcore. Soundcore reuses command ids across models (research notes
 * §4: `01 81` is wear detection on one model and something else on another), and unknown writes
 * can unpair the earbuds, power them off or play a loud tone, so the Lab never guesses an id: it
 * sends only the reads below, and of those only the ones the model's own allow-list
 * ([SoundcoreWriteGuard.isAllowed]) lets out.
 */
object SoundcoreReads {
    val DOCUMENTED: List<SoundcoreRead> = listOf(
        SoundcoreRead(SoundcoreCommands.REQUEST_STATE, "State (battery, firmware, serial, modes, settings)"),
        SoundcoreRead(SoundcoreCommands.REQUEST_INFO, "Device info (firmware, serial)"),
        SoundcoreRead(SoundcoreCommands.SESSION_INFO, "Capability table (session info)"),
        SoundcoreRead(SoundcoreCommands.ALARMS_GET, "Alarms"),
        SoundcoreRead(SoundcoreCommands.SLEEP_SETTINGS_GET, "Sleep settings"),
    )

    /** The command categories, the Lab's scan groups. */
    val CATEGORY_NAMES: Map<Int, String> = linkedMapOf(
        0x01 to "Device",
        0x05 to "Session",
        0x14 to "Alarms",
        0x15 to "Sleep",
    )

    /**
     * A read Earshot may send to [model] (null = the original Sleep A30 connection): a read code,
     * empty body, never one of [SoundcoreWriteGuard.NEVER], and on the model's allow-list.
     */
    fun isRead(model: SoundcoreModel?, command: Int): Boolean {
        val packet = SoundcorePacket(command)
        return !packet.isWrite && command !in SoundcoreWriteGuard.NEVER && SoundcoreWriteGuard.isAllowed(model, packet)
    }

    /** The documented reads [model]'s allow-list lets out, in a stable order. */
    fun forModel(model: SoundcoreModel?): List<SoundcoreRead> = DOCUMENTED.filter { isRead(model, it.command) }
}
