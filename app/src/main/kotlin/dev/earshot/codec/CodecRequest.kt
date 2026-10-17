package dev.earshot.codec

import dev.earshot.core.CodecSelection

/**
 * A codec switch in the numbers `BluetoothCodecConfig` wants, i.e. the arguments of
 * `ICodecService.setCodecConfig`. Built app-side (pure, unit-tested) and turned into a real config
 * object inside the user service.
 *
 * The trick Developer options uses is the same: a config for the wanted codec at
 * `CODEC_PRIORITY_HIGHEST`; any field left at 0 (`*_NONE`) means "keep whatever is negotiated".
 */
data class CodecRequest(
    /** Normalised through [CodecNames.normalize]. */
    val codecName: String,
    /** `SOURCE_CODEC_TYPE_*` or the vendor id the stack printed; [UNKNOWN_TYPE] when neither is known. */
    val codecType: Int,
    /** `SAMPLE_RATE_*` bit; 0 = keep. */
    val sampleRateMask: Int,
    /** `BITS_PER_SAMPLE_*` bit; 0 = keep. */
    val bitsMask: Int,
    /** LDAC quality lives here (1000..1003); 0 elsewhere. */
    val codecSpecific1: Long,
) {
    companion object {
        const val UNKNOWN_TYPE = -1

        /**
         * @param knownTypeIds numeric ids the stack itself reported, keyed by normalised name. They win
         *   over the AOSP table because vendor codecs (Samsung Scalable = 8 on One UI 9, aptX Adaptive)
         *   have ids only the running stack knows.
         */
        fun from(selection: CodecSelection, knownTypeIds: Map<String, Int> = emptyMap()): CodecRequest {
            val name = CodecNames.normalize(selection.codecName)
            return CodecRequest(
                codecName = name,
                codecType = knownTypeIds[name] ?: CodecNames.aospType(name) ?: UNKNOWN_TYPE,
                sampleRateMask = CodecMasks.sampleRateMask(selection.sampleRateHz),
                bitsMask = CodecMasks.bitDepthMask(selection.bitsPerSample),
                codecSpecific1 = when (name) {
                    CodecNames.LDAC -> CodecNames.ldacQualityValue(selection.qualityMode) ?: 0L
                    else -> 0L
                },
            )
        }
    }
}
