package dev.earshot.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What a listening mode *does*, independent of the slot number a headset keeps it in. Slot numbers
 * disagree between makers (slot 1 is Aware on a Bose, Off on the Sleep A30; slot 2 is Immersion on
 * a Bose, Off on the Crusher, Sennheiser and Sony), so anything saved (rules, the tile cycle) keeps
 * the meaning and resolves it to a slot on whatever headset is connected ([ModeResolver]).
 *
 * The stored names are pinned and lower-case; an unknown name (written by a newer build) reads
 * back as [CUSTOM] instead of making the whole rule unreadable.
 */
@Serializable(with = ModeMeaningSerializer::class)
enum class ModeMeaning(val storedName: String) {
    /** Noise cancelling at its strongest: Bose Quiet, Soundcore/Sennheiser/Sony noise cancelling. */
    QUIET("quiet"),
    /** Hear the world: Bose Aware, transparency, Sony ambient sound. */
    AWARE("aware"),
    /** Neither cancelling nor transparency: Crusher/Sennheiser/Sony Off, Soundcore Normal. */
    OFF("off"),
    /** Reserved for headsets that switch by themselves (AirPods, JBL); no driver produces it yet. */
    ADAPTIVE("adaptive"),
    /** Anything else (Bose Immersion, Cinema, Stereo and the user's own modes): matched by name. */
    CUSTOM("custom"),
    ;

    companion object {
        fun fromStoredName(name: String): ModeMeaning = entries.firstOrNull { it.storedName == name } ?: CUSTOM
    }
}

internal object ModeMeaningSerializer : KSerializer<ModeMeaning> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.earshot.core.ModeMeaning", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ModeMeaning) = encoder.encodeString(value.storedName)
    override fun deserialize(decoder: Decoder): ModeMeaning = ModeMeaning.fromStoredName(decoder.decodeString())
}

/**
 * A saved reference to a listening mode: its [meaning], plus where it was picked so the same
 * headset gets exactly the same slot back.
 *
 * Persisted inside the automation rules and the tile cycle; every field after [meaning] is optional
 * so older and newer builds can read each other's JSON.
 */
@Serializable
data class ModeRef(
    val meaning: ModeMeaning,
    /** For [ModeMeaning.CUSTOM] the match key (case-insensitive); otherwise only for display. */
    val name: String? = null,
    /** The headset it was picked on (upper-case Bluetooth address). */
    val address: String? = null,
    /** Its slot on [address]; trusted only on that headset. */
    val slot: Int? = null,
    /**
     * The bare slot number a 0.75 rule or tile entry stored, set only by the 0.8 migration: when the
     * meaning finds nothing on a headset, the slot is used as 0.75 did, so a migrated rule never does
     * less than before. A ref picked in 0.8 has none.
     */
    val legacySlot: Int? = null,
)

/**
 * Meaning ↔ slot, in core so no driver changes: `HeadphoneRepository.setMode(index)` stays as it is
 * and the slot is resolved just before it is called.
 */
object ModeResolver {
    /**
     * English names the frozen stacks give their built-in slots, for the stacks that don't set
     * [ListeningMode.meaning] yet: Bose (its preset names are the [31.6] prompt names, which the
     * firmware locks), the Bragi Crusher/Method (Quiet, Aware, Off) and the Sleep A30 (Noise
     * cancelling, Off). ModeResolverTest pins them: renaming one of these modes in its driver, or
     * localising the names, must first give that driver an explicit meaning.
     */
    private val QUIET_NAMES = setOf("Quiet", "Noise cancelling")
    private val AWARE_NAMES = setOf("Aware")
    private val OFF_NAMES = setOf("Off")

    /** [m]'s own meaning, else inferred from a built-in slot's name. A user's slot is always [ModeMeaning.CUSTOM]. */
    fun meaningOf(m: ListeningMode): ModeMeaning {
        m.meaning?.let { return it }
        // A custom slot is the user's, whatever it is called: a Bose custom mode named "Quiet" is not Quiet.
        if (!m.isPreset) return ModeMeaning.CUSTOM
        val name = m.name.trim()
        return when {
            QUIET_NAMES.any { it.equals(name, ignoreCase = true) } -> ModeMeaning.QUIET
            AWARE_NAMES.any { it.equals(name, ignoreCase = true) } -> ModeMeaning.AWARE
            OFF_NAMES.any { it.equals(name, ignoreCase = true) } -> ModeMeaning.OFF
            else -> ModeMeaning.CUSTOM
        }
    }

    /** The ref to save for [m] picked on the headset at [address]. */
    fun refFor(m: ListeningMode, address: String?): ModeRef = ModeRef(
        meaning = meaningOf(m),
        name = m.name.trim().ifEmpty { null },
        address = address?.uppercase(),
        slot = m.index,
    )

    /**
     * The ref for a bare 0.75 slot number: the mode in that slot on this headset, else a custom ref
     * that only knows the slot. Either way [ModeRef.legacySlot] keeps the number.
     */
    fun refForLegacySlot(slot: Int, modes: List<ListeningMode>, address: String?): ModeRef =
        modes.firstOrNull { it.index == slot && it.isConfigured }
            ?.let { refFor(it, address).copy(legacySlot = slot) }
            ?: ModeRef(ModeMeaning.CUSTOM, legacySlot = slot)

    fun slotFor(ref: ModeRef, state: HeadphoneState): Int? = slotFor(ref, state.modes, state.connection.deviceAddress)

    /**
     * The slot [ref] means on the headset at [address] with [modes], or null when it has no such
     * mode. Only configured modes count. In order:
     *  1. the headset it was picked on: the same slot, if it still has that meaning (for a custom
     *     mode any custom mode, so renaming it there keeps the rule working);
     *  2. a built-in meaning: the lowest slot with that meaning (Sony keeps its slot numbers when
     *     it lacks noise cancelling, so this is by index, never by list position);
     *  3. a custom mode: the custom mode with that name, ignoring case;
     *  4. the 0.75 slot number, if the migration left one and that slot exists.
     */
    fun slotFor(ref: ModeRef, modes: List<ListeningMode>, address: String?): Int? {
        val configured = modes.filter { it.isConfigured }
        if (ref.address != null && ref.slot != null && address != null && ref.address.equals(address, ignoreCase = true)) {
            configured.firstOrNull { it.index == ref.slot && meaningOf(it) == ref.meaning }?.let { return it.index }
        }
        if (ref.meaning != ModeMeaning.CUSTOM) {
            configured.filter { meaningOf(it) == ref.meaning }.minByOrNull { it.index }?.let { return it.index }
        } else {
            val name = ref.name?.trim()?.takeIf { it.isNotEmpty() }
            if (name != null) {
                configured.filter { meaningOf(it) == ModeMeaning.CUSTOM && it.name.trim().equals(name, ignoreCase = true) }
                    .minByOrNull { it.index }?.let { return it.index }
            }
        }
        ref.legacySlot?.let { legacy -> if (configured.any { it.index == legacy }) return legacy }
        return null
    }

    /**
     * What the tile, widget, notification and cycle action fall back to when nothing is chosen and
     * nothing is a favourite: the first three configured slots. On a QC Ultra that is still
     * Quiet/Aware/Immersion; on any other headset it is its own first modes, not Bose slot numbers.
     */
    fun defaultCycle(modes: List<ListeningMode>): List<Int> =
        modes.filter { it.isConfigured }.map { it.index }.distinct().sorted().take(3)

    /** A name for [ref] without a headset: its saved name, else its meaning. */
    fun label(ref: ModeRef): String =
        ref.name?.trim()?.takeIf { it.isNotEmpty() } ?: when (ref.meaning) {
            ModeMeaning.QUIET -> "Noise cancelling"
            ModeMeaning.AWARE -> "Aware"
            ModeMeaning.OFF -> "Off"
            ModeMeaning.ADAPTIVE -> "Adaptive"
            ModeMeaning.CUSTOM -> (ref.slot ?: ref.legacySlot)?.let { "mode $it" } ?: "Custom mode"
        }

    /**
     * The name to show for a "set mode" action: the mode it resolves to on the headset at
     * [address] (so a renamed mode shows its new name), else the saved ref's [label]. Null for an
     * unmigrated 0.75 action whose slot has no name here; callers show the bare number then.
     */
    fun nameOf(action: AutomationAction.SetMode, modes: List<ListeningMode>, address: String?): String? {
        val ref = action.mode
        val slot = if (ref != null) slotFor(ref, modes, address) else action.modeIndex
        val live = slot?.let { s -> modes.firstOrNull { it.index == s }?.name?.trim()?.takeIf { it.isNotEmpty() } }
        return live ?: ref?.let(::label)
    }

    /**
     * The slot a meaning-only ref (picked with no headset connected) writes as the legacy
     * `modeIndex`, which only a downgraded 0.75 build reads: 0 and 1 are Quiet and Aware there,
     * and 2 is Off on most other makers' layouts.
     */
    fun legacyIndexFor(meaning: ModeMeaning): Int = when (meaning) {
        ModeMeaning.AWARE -> 1
        ModeMeaning.OFF -> 2
        else -> 0
    }

    /**
     * A slot number drawn on a surface (widget button, notification action) is only valid on the
     * headset it was drawn for. True when it was drawn for [connectedAddress], when it carries no
     * address (drawn by an older build), or when nothing is connected (the command then fails on
     * its own with the driver's message).
     */
    fun slotStillApplies(drawnFor: String?, connectedAddress: String?): Boolean =
        drawnFor == null || connectedAddress == null || drawnFor.equals(connectedAddress, ignoreCase = true)

    /** The meanings offered when no headset is connected to pick from. */
    val GENERIC_CHOICES: List<ModeMeaning> = listOf(ModeMeaning.QUIET, ModeMeaning.AWARE, ModeMeaning.OFF)
}
