package dev.earshot.protocol.soundcore

/** A change Earshot won't send, with a sentence the user can read. */
class SoundcoreWriteRefused(message: String) : RuntimeException(message)

/**
 * Builds the packets that change a gen-2 Soundcore model, from the state it last reported. Only
 * models whose layout was confirmed ([SoundcoreState.writable]) get any; every other call throws
 * [SoundcoreWriteRefused]. All of them are read-modify-write of the whole block, with only the one
 * field changed, so the bytes Earshot does not understand go back as the earbuds sent them.
 *
 * Transitions follow what the official app does (research notes §3, "Mode-writing quirks"): some
 * v2 firmware misbehaves on a write the app never makes, so the noise-cancelling type only changes
 * while noise cancelling is the active mode, the manual level only in the manual type, the
 * transparency type only in transparency and wind only in noise cancelling or transparency. The
 * app shows the same refusals as messages instead of making the user's mode change for them.
 */
object SoundcoreWrites {
    // Ids of the mode controls (the settings in SoundcoreLayout use SoundcoreSettings).
    const val NC_TYPE = "nc_type"
    const val NC_LEVEL = "nc_level"
    const val TRANSPARENCY_TYPE = "transparency_type"
    const val WIND = "wind"

    private fun requireWritable(state: SoundcoreState): SoundModes {
        if (!state.writable) throw SoundcoreWriteRefused(state.note ?: "Earshot can read this model but doesn't change its settings yet")
        return state.modes ?: throw SoundcoreWriteRefused("The earbuds haven't reported their sound mode yet")
    }

    private fun packet(modes: SoundModes) = SoundcorePacket(SoundcoreCommands.SET_SOUND_MODES, modes.raw)

    fun setAmbient(state: SoundcoreState, mode: AmbientMode): SoundcorePacket =
        packet(requireWritable(state).withAmbient(mode))

    fun setNcType(state: SoundcoreState, index: Int): SoundcorePacket {
        val modes = requireWritable(state)
        if (index !in modes.codec.ncTypes.indices) throw SoundcoreWriteRefused("These earbuds have no such noise-cancelling type")
        if (modes.ambient != AmbientMode.NOISE_CANCELLING) throw SoundcoreWriteRefused("Switch to noise cancelling first, then change its type")
        return packet(modes.withNcType(index))
    }

    fun setManualLevel(state: SoundcoreState, level: Int): SoundcorePacket {
        val modes = requireWritable(state)
        val range = modes.codec.manualLevels ?: throw SoundcoreWriteRefused("These earbuds have no manual noise-cancelling level")
        if (level !in range) throw SoundcoreWriteRefused("The level must be ${range.first} to ${range.last}")
        if (modes.ambient != AmbientMode.NOISE_CANCELLING) throw SoundcoreWriteRefused("Switch to noise cancelling first, then change the level")
        if (modes.ncType != 0) throw SoundcoreWriteRefused("Set the noise-cancelling type to Manual first, then change the level")
        return packet(modes.withManualLevel(level))
    }

    fun setTransparencyType(state: SoundcoreState, index: Int): SoundcorePacket {
        val modes = requireWritable(state)
        if (index !in modes.codec.transparencyTypes.indices) throw SoundcoreWriteRefused("These earbuds have no such transparency type")
        if (modes.ambient != AmbientMode.TRANSPARENCY) throw SoundcoreWriteRefused("Switch to transparency first, then change its type")
        return packet(modes.withTransparencyType(index))
    }

    fun setWind(state: SoundcoreState, on: Boolean): SoundcorePacket {
        val modes = requireWritable(state)
        if (!modes.codec.hasWind) throw SoundcoreWriteRefused("These earbuds have no wind noise reduction")
        if (modes.ambient == AmbientMode.NORMAL) throw SoundcoreWriteRefused("Wind noise reduction works with noise cancelling or transparency")
        return packet(modes.withWind(on))
    }

    /**
     * A documented setting by its id. [value] is 0/1, or for auto power-off the option index
     * (0 = off, n = the nth duration in [SoundcoreLayout.autoOffMinutes]).
     */
    fun setSetting(state: SoundcoreState, id: String, value: Int): SoundcorePacket {
        requireWritable(state)
        val layout = state.model.layout ?: throw SoundcoreWriteRefused("This model has no settings Earshot knows")
        val setting = layout.setting(id) ?: throw SoundcoreWriteRefused("The ${state.model.displayName.removePrefix("Soundcore ")} has no setting \"$id\"")
        if (!setting.autoOff) {
            return SoundcorePacket(setting.command, byteArrayOf(if (value != 0) 1 else 0))
        }
        val steps = layout.autoOffMinutes
        if (value !in 0..steps.size) throw SoundcoreWriteRefused("Pick one of the listed times")
        if (value == 0) {
            // Off keeps the duration the earbuds hold, so switching it back on later restores it.
            val keep = state.autoOffIndex[id] ?: 0
            return SoundcorePacket(setting.command, byteArrayOf(0, keep.toByte()))
        }
        return SoundcorePacket(setting.command, byteArrayOf(1, (value - 1).toByte()))
    }

    /** One control id from the app: the mode controls above, or a [SoundcoreSetting] id. */
    fun control(state: SoundcoreState, id: String, value: Int): SoundcorePacket = when (id) {
        NC_TYPE -> setNcType(state, value)
        NC_LEVEL -> setManualLevel(state, (state.modes?.codec?.manualLevels?.first ?: 1) + value)
        TRANSPARENCY_TYPE -> setTransparencyType(state, value)
        WIND -> setWind(state, value != 0)
        else -> setSetting(state, id, value)
    }

    /**
     * Did the change in [packet] show up in [after], the state read back after the write? Only
     * the fields the write changed relative to [before] are compared: the earbuds may normalise the
     * rest of the block. A model that acknowledges a write and ignores it (the Space 2 before its
     * handshake) fails this, and the app says so instead of showing a switch that did nothing.
     */
    fun confirms(before: SoundcoreState, after: SoundcoreState, packet: SoundcorePacket): Boolean {
        val layout = after.model.layout ?: return true
        if (packet.command == SoundcoreCommands.SET_SOUND_MODES) {
            val now = after.modes ?: return false
            if (packet.body.size != now.codec.size) return false
            val sent = SoundModes(now.codec, packet.body)
            val was = before.modes
            fun <T> changedTo(read: (SoundModes) -> T): Boolean = was != null && read(was) == read(sent) || read(now) == read(sent)
            return changedTo { it.ambient } && changedTo { it.manualLevel } && changedTo { it.ncType } &&
                changedTo { it.transparencyType } && changedTo { it.windOn }
        }
        val setting = layout.settings.firstOrNull { it.command == packet.command } ?: return true
        val want = when {
            !setting.autoOff -> packet.body.firstOrNull()?.toInt() ?: return true
            packet.body.firstOrNull()?.toInt() == 0 -> 0
            else -> (packet.body.getOrNull(1)?.toInt()?.and(0xFF) ?: return true) + 1
        }
        return after.settings[setting.id] == want
    }

    /**
     * The state after a successful write, for models that push nothing back: only the
     * mode block changes here, the app re-reads `01 01` for the rest.
     */
    fun applied(state: SoundcoreState, packet: SoundcorePacket): SoundcoreState {
        val codec = state.modes?.codec ?: return state
        return if (packet.command == SoundcoreCommands.SET_SOUND_MODES && packet.body.size == codec.size) {
            state.withModeBlock(SoundModes(codec, packet.body))
        } else {
            state
        }
    }
}
