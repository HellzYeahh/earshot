package dev.earshot.service

import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCapabilities
import dev.earshot.protocol.qcultra2.BoseModel
import dev.earshot.protocol.qcultra2.CncState
import dev.earshot.protocol.qcultra2.ModeLayout
import dev.earshot.protocol.qcultra2.ModeSlotPlan
import dev.earshot.protocol.qcultra2.ModesCapabilities
import dev.earshot.protocol.qcultra2.ProductForm
import dev.earshot.protocol.qcultra2.QcUltra2

/**
 * How noise settings are changed on this connection (docs/bose-models.md §E):
 * - [LIVE]: `[31.10]` SET_GET (QC Ultra family);
 * - [DIRECT]: `[1.5]` SET_GET `[step, enabled]` (QC Earbuds 2020, NC 700, any unit without block 31);
 * - [SLOTS]: rewrite the active custom mode with `[31.6]` (QC45, QC Headphones, no `[31.10]`);
 * - [READ_ONLY]: the level is shown but cannot be changed (`[1.5]` SET_GET refused, no modes);
 * - [NONE]: no noise control at all (unsupported products).
 */
internal enum class NoisePath { LIVE, DIRECT, SLOTS, READ_ONLY, NONE }

/**
 * What one connection has learned on top of the catalogue entry, newest facts winning: `[31.2]`
 * mode counts and flags, the `[31.6]` STATUS layout, the `[31.8]` slot total and the `[1.5]`
 * steps. The slot plan ([ModeSlotPlan]) is what the mode mapping and the writes use, so a model
 * whose catalogue entry is a guess (or no entry at all) still gets the device's own numbers.
 */
internal data class ConnectionFacts(
    /** The catalogue entry; [BoseModel.DEFAULT] (the QC Ultra 2 defaults) when nothing matched. */
    val model: BoseModel = BoseModel.DEFAULT,
    /** [model] really is this product (by `[0.3]` or `[0.15]`), not the fallback. */
    val catalogued: Boolean = true,
    /** `[0.15]` when no catalogue entry matched: names the family of an unknown product. */
    val unknownProductName: String? = null,
    /** `[31.2]`, once read. */
    val modes: ModesCapabilities? = null,
    /** Layout of the `[31.6]` STATUS frames seen; beats the catalogue (a guess on unverified models). */
    val seenLayout: ModeLayout? = null,
    /** `[31.8]` byte 0: the firmware's own slot total. */
    val favoritesTotal: Int? = null,
    /** `[1.5]` as last read or pushed. */
    val cnc: CncState? = null,
) : ModeSlotPlan {
    /** The `[31.6]` write layout: the frames actually seen, else the catalogue's guess. */
    val writeLayout: ModeLayout get() = seenLayout ?: model.modeLayout

    override val slotCount: Int
        get() = (favoritesTotal ?: modes?.totalModes?.takeIf { it > 0 } ?: model.slotCount).coerceIn(0, QcUltra2.MODE_SLOT_COUNT)

    override val presetSlots: IntRange get() = modes?.presetSlots ?: model.presetSlots

    override val editableSlots: IntRange get() = modes?.customSlots ?: model.editableSlots

    override val presetNames: List<String> get() = model.presetNames

    /**
     * Custom modes can be written: there are custom slots, and either the catalogue knows the
     * write works or the device reported its counts *and* the slot layout (whose write length the
     * Bose app's builder fixes, docs/bose-models.md §C2). That second case opens editing on the
     * Ultra Open Earbuds once they report custom slots; a refused write is then reported as such.
     */
    override val canEditModes: Boolean
        get() = !editableSlots.isEmpty() && (model.canEditModes || (modes != null && seenLayout != null))

    /** The product line: the catalogue's, else the `[0.15]` name of an unknown product (null without one). */
    val family: String? get() = if (catalogued) model.family else BoseModel.familyFromName(unknownProductName)

    val generation: String? get() = if (catalogued) model.generation else null

    /** Unknown products keep the QC Ultra 2 protocol defaults, but their name may still say "earbuds". */
    val formFactor: ProductForm get() = if (catalogued) model.formFactor else BoseModel.formFromName(unknownProductName)

    /**
     * What the UI may offer. Each feature needs the device (`[31.2]` flags when read, else the
     * catalogue) *and* a way to write it on [path]: on [NoisePath.SLOTS] the slot layout must carry
     * the byte; on [NoisePath.DIRECT] there is only the level and, when `[1.5]` allows, ANC on/off.
     */
    fun capabilities(path: NoisePath, modesAvailable: Boolean): HeadphoneCapabilities {
        if (model.isUnsupported) return unsupportedCapabilities()
        val layout = writeLayout
        val live = path == NoisePath.LIVE
        val noiseCancelling = modes?.cnc ?: model.hasNoiseCancelling
        val ancToggle = when (path) {
            // [1.5] flags bit 1 = 0: the user may switch noise cancelling off (docs/bose-models.md §C3).
            NoisePath.DIRECT -> noiseCancelling && cnc?.userCanToggle == true
            NoisePath.READ_ONLY, NoisePath.NONE -> false
            NoisePath.LIVE, NoisePath.SLOTS ->
                noiseCancelling && (modes?.ancToggle ?: layout.hasAncToggle) && (live || layout.hasAncToggle)
        }
        // Spatial audio and wind block are mode settings: only [31.10] or a slot rewrite can change them.
        val modal = modesAvailable && (live || path == NoisePath.SLOTS)
        return HeadphoneCapabilities(
            spatialAudio = modal && (modes?.spatial ?: model.hasSpatialAudio) && (live || layout.hasSpatial),
            ancToggle = ancToggle,
            liveNoiseControl = path == NoisePath.LIVE || path == NoisePath.DIRECT,
            windExcludesCancelling = layout == ModeLayout.COMPACT,
            noiseCancelling = noiseCancelling,
            ancAlwaysOn = noiseCancelling && !ancToggle,
            windBlock = modal && noiseCancelling && (modes?.windBlock ?: model.hasWindBlock) && (live || layout.hasWind),
            editableModes = canEditModes && modesAvailable,
            favoriteModes = modesAvailable && (modes?.favorites ?: true),
            listeningModes = modesAvailable,
            verified = model.verified,
            formFactor = formFactor.toFormFactor(),
            supportNote = if (catalogued) model.supportNote else null,
        )
    }

    private fun unsupportedCapabilities() = HeadphoneCapabilities(
        spatialAudio = false, ancToggle = false, liveNoiseControl = false, noiseCancelling = false,
        ancAlwaysOn = false, windBlock = false, editableModes = false, favoriteModes = false,
        listeningModes = false, verified = false, formFactor = model.formFactor.toFormFactor(),
        supportNote = model.supportNote,
    )
}

internal fun ProductForm.toFormFactor(): FormFactor = when (this) {
    ProductForm.HEADPHONES -> FormFactor.HEADPHONES
    ProductForm.EARBUDS -> FormFactor.EARBUDS
    ProductForm.OPEN_EARBUDS -> FormFactor.OPEN_EARBUDS
}
