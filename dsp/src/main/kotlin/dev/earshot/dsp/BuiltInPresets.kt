package dev.earshot.dsp

/**
 * Presets that ship with the app. All are `isBuiltIn = true` so the repository refuses to
 * delete or overwrite them; users "save as" to make their own copy.
 *
 * Every preset's `preampDb` is derived with [Headroom.recommendedPreampDb] so that no built-in
 * can clip on its own with the limiter off. Gains are deliberately modest: headphones already
 * have a voicing and most listeners want a nudge, not a new pair.
 */
object BuiltInPresets {
    const val FLAT_ID = "flat"
    const val BASS_ID = "bass"
    const val VOCAL_ID = "vocal"
    const val TREBLE_ID = "treble"
    const val PODCAST_ID = "podcast"
    const val LOUDNESS_ID = "loudness"
    const val QC_ULTRA_ID = "qc_ultra"

    /**
     * "Tuned for QC Ultra" — AutoEq correction of the Bose QuietComfort Ultra Headphones toward
     * AutoEq's Harman-based over-ear target.
     *
     * Source: AutoEq by Jaakko Pasanen (MIT licence, https://github.com/jaakkopasanen/AutoEq),
     * measurement by Rtings on the Brüel & Kjær 5128 rig:
     * https://github.com/jaakkopasanen/AutoEq/blob/master/results/Rtings/Bruel%20%26%20Kjaer%205128%20over-ear/Bose%20QuietComfort%20Ultra%20Headphones/Bose%20QuietComfort%20Ultra%20Headphones%20ParametricEQ.txt
     * (fetched 2026-10-16). Text below is the file verbatim.
     *
     * Why this measurement: AutoEq has no oratory1990 or crinacle result for this model. Of the
     * four it does have (Rtings/5128, HypetheSonics, Kuulokenurkka "ANC on", Filk "ANC on") the
     * Rtings one matches the product name exactly, comes from the best-characterised rig and asks
     * for the smallest corrections (−3 dB bass shelf, ±3 dB elsewhere); the others disagree with
     * each other by up to 10 dB in the bass, which is a sign of fit variance, not of the headphone.
     * A conservative curve is the right default for a one-tap preset.
     *
     * Licence: MIT — Copyright (c) 2018-2022 Jaakko Pasanen. Permission is hereby granted, free of
     * charge, to any person obtaining a copy of this software and associated documentation files,
     * to deal in the Software without restriction … (full text: AutoEq repository LICENSE).
     */
    const val QC_ULTRA_AUTOEQ_URL =
        "https://github.com/jaakkopasanen/AutoEq/blob/master/results/Rtings/Bruel%20%26%20Kjaer%205128%20over-ear/Bose%20QuietComfort%20Ultra%20Headphones/Bose%20QuietComfort%20Ultra%20Headphones%20ParametricEQ.txt"

    private const val QC_ULTRA_AUTOEQ_TEXT = """Preamp: -2.8 dB
Filter 1: ON LSC Fc 105 Hz Gain -3.0 dB Q 0.70
Filter 2: ON PK Fc 5032 Hz Gain 2.8 dB Q 3.69
Filter 3: ON PK Fc 156 Hz Gain 1.5 dB Q 1.61
Filter 4: ON PK Fc 94 Hz Gain -2.4 dB Q 2.32
Filter 5: ON PK Fc 2373 Hz Gain -2.8 dB Q 5.58
Filter 6: ON HSC Fc 10000 Hz Gain 2.7 dB Q 0.70
Filter 7: ON PK Fc 1633 Hz Gain 2.6 dB Q 3.51
Filter 8: ON PK Fc 491 Hz Gain -1.1 dB Q 2.50
Filter 9: ON PK Fc 2194 Hz Gain -1.0 dB Q 3.76
Filter 10: ON PK Fc 36 Hz Gain -0.5 dB Q 2.83
"""

    val flat: EqPreset = EqPreset(
        id = FLAT_ID,
        name = "Flat",
        bands = GraphicEq.bandsFromGains(List(GraphicEq.BAND_COUNT) { 0.0 }),
        preampDb = 0.0,
        isBuiltIn = true,
        description = "No change — the headphones' own sound.",
    )

    val bassBoost: EqPreset = withHeadroom(
        EqPreset(
            id = BASS_ID,
            name = "Bass boost",
            bands = listOf(
                ParametricBand(frequencyHz = 90.0, gainDb = 5.0, q = 0.7, type = FilterType.LOW_SHELF),
                ParametricBand(frequencyHz = 60.0, gainDb = 1.5, q = 1.0),
                // Pull the upper bass back a touch so the shelf does not thicken male voices.
                ParametricBand(frequencyHz = 250.0, gainDb = -1.0, q = 1.2),
            ),
            isBuiltIn = true,
            description = "Fuller low end without muddying the mids.",
        ),
    )

    val vocalClarity: EqPreset = withHeadroom(
        EqPreset(
            id = VOCAL_ID,
            name = "Vocal clarity",
            bands = listOf(
                ParametricBand(frequencyHz = 120.0, gainDb = -2.0, q = 0.7, type = FilterType.LOW_SHELF),
                ParametricBand(frequencyHz = 300.0, gainDb = -1.5, q = 1.2),
                ParametricBand(frequencyHz = 1200.0, gainDb = 1.0, q = 1.0),
                ParametricBand(frequencyHz = 2800.0, gainDb = 2.5, q = 1.1),
                ParametricBand(frequencyHz = 5500.0, gainDb = 1.0, q = 1.5),
            ),
            isBuiltIn = true,
            description = "Lifts presence around 3 kHz and trims the mud below it.",
        ),
    )

    val trebleSparkle: EqPreset = withHeadroom(
        EqPreset(
            id = TREBLE_ID,
            name = "Treble sparkle",
            bands = listOf(
                ParametricBand(frequencyHz = 6000.0, gainDb = 2.0, q = 1.2),
                ParametricBand(frequencyHz = 9000.0, gainDb = 3.0, q = 0.7, type = FilterType.HIGH_SHELF),
                // Sidestep the 3–4 kHz region so the lift reads as "air", not "harsh".
                ParametricBand(frequencyHz = 3500.0, gainDb = -0.5, q = 1.5),
            ),
            isBuiltIn = true,
            description = "More air and detail in the top octaves.",
        ),
    )

    val podcast: EqPreset = withHeadroom(
        EqPreset(
            id = PODCAST_ID,
            name = "Podcast",
            bands = listOf(
                // Speech lives in 150 Hz–5 kHz; cut the rumble and hiss around it.
                ParametricBand(frequencyHz = 100.0, gainDb = -4.0, q = 0.7, type = FilterType.LOW_SHELF),
                ParametricBand(frequencyHz = 200.0, gainDb = -1.0, q = 1.0),
                ParametricBand(frequencyHz = 1500.0, gainDb = 1.5, q = 1.0),
                ParametricBand(frequencyHz = 3000.0, gainDb = 2.5, q = 1.2),
                ParametricBand(frequencyHz = 8000.0, gainDb = -1.5, q = 0.7, type = FilterType.HIGH_SHELF),
            ),
            isBuiltIn = true,
            description = "Intelligibility first: less rumble, clearer consonants.",
        ),
    )

    val loudness: EqPreset = withHeadroom(
        EqPreset(
            id = LOUDNESS_ID,
            name = "Loudness",
            bands = listOf(
                // Gentle smile: equal-loudness compensation for quiet listening.
                ParametricBand(frequencyHz = 100.0, gainDb = 3.5, q = 0.7, type = FilterType.LOW_SHELF),
                ParametricBand(frequencyHz = 700.0, gainDb = -1.0, q = 0.6),
                ParametricBand(frequencyHz = 8000.0, gainDb = 2.5, q = 0.7, type = FilterType.HIGH_SHELF),
            ),
            isBuiltIn = true,
            description = "Soft smile curve for low-volume listening.",
        ),
    )

    val tunedForQcUltra: EqPreset = AutoEqParser.parse(
        text = QC_ULTRA_AUTOEQ_TEXT,
        id = QC_ULTRA_ID,
        name = "Tuned for QC Ultra",
        description = "Correction toward the Harman over-ear target for the Bose QuietComfort Ultra " +
            "Headphones. From AutoEq (Jaakko Pasanen, MIT licence), Rtings measurement on the " +
            "B&K 5128 rig: $QC_ULTRA_AUTOEQ_URL",
        isBuiltIn = true,
    )

    val all: List<EqPreset> = listOf(flat, tunedForQcUltra, bassBoost, vocalClarity, trebleSparkle, podcast, loudness)

    fun byId(id: String?): EqPreset? = id?.let { wanted -> all.firstOrNull { it.id == wanted } }

    private fun withHeadroom(preset: EqPreset): EqPreset =
        preset.copy(preampDb = Headroom.recommendedPreampDb(preset.bands))
}
