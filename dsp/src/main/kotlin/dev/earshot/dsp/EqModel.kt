package dev.earshot.dsp

import kotlinx.serialization.Serializable

/**
 * Shared equaliser model. This file is the contract between the DSP module, the audio
 * engine in the app and the UI. Keep it plain data; the maths lives next to it in this module.
 */
@Serializable
enum class FilterType { PEAK, LOW_SHELF, HIGH_SHELF }

@Serializable
data class ParametricBand(
    val frequencyHz: Double,
    val gainDb: Double,
    val q: Double = 1.41,
    val type: FilterType = FilterType.PEAK,
    val enabled: Boolean = true,
) {
    companion object {
        const val MIN_GAIN_DB = -15.0
        const val MAX_GAIN_DB = 15.0
        const val MIN_Q = 0.1
        const val MAX_Q = 10.0
    }
}

@Serializable
data class EqPreset(
    val id: String,
    val name: String,
    val bands: List<ParametricBand>,
    /** Applied before the bands; negative values keep boosted bands from clipping. */
    val preampDb: Double = 0.0,
    val isBuiltIn: Boolean = false,
    val description: String? = null,
)

/** The 10 ISO centre frequencies used by the graphic EQ view. */
object GraphicEq {
    val FREQUENCIES_HZ: List<Double> = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    const val BAND_COUNT = 10
    /** Octave-spaced graphic bands overlap nicely at this Q. */
    const val DEFAULT_Q = 1.41

    fun bandsFromGains(gainsDb: List<Double>): List<ParametricBand> {
        require(gainsDb.size == BAND_COUNT) { "expected $BAND_COUNT gains" }
        return FREQUENCIES_HZ.zip(gainsDb) { f, g -> ParametricBand(frequencyHz = f, gainDb = g, q = DEFAULT_Q) }
    }
}

/** What the sound engine should be doing right now. Persisted by the app. */
@Serializable
data class TuningSettings(
    val enabled: Boolean = false,
    val activePresetId: String? = null,
    /** Live bands being edited; when [activePresetId] is set these mirror that preset. */
    val bands: List<ParametricBand> = GraphicEq.bandsFromGains(List(GraphicEq.BAND_COUNT) { 0.0 }),
    val preampDb: Double = 0.0,
    /** Extra loudness in dB, 0..[MAX_BOOST_DB]. */
    val boostDb: Double = 0.0,
    val limiterEnabled: Boolean = true,
) {
    companion object {
        const val MAX_BOOST_DB = 12.0
        /** Above this the UI shows the hearing-safety warning. */
        const val BOOST_WARNING_DB = 6.0
    }
}
