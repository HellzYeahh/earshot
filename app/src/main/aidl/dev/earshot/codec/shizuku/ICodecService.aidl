package dev.earshot.codec.shizuku;

/**
 * Served by CodecUserService, which Shizuku runs as the shell user (uid 2000) in the process
 * "dev.earshot:codec". Failures come back as SecurityException / IllegalStateException /
 * IllegalArgumentException carrying a readable message.
 *
 * Numbers use the BluetoothCodecConfig values: codecType is SOURCE_CODEC_TYPE_* (or the vendor id
 * the stack printed; -1 when unknown), sampleRate / bits are the SAMPLE_RATE_* / BITS_PER_SAMPLE_*
 * bit masks (0 = keep whatever is negotiated).
 */
interface ICodecService {
    /** Shizuku's reserved destroy transaction: the service calls System.exit(0). */
    void destroy() = 16777114;

    /** BluetoothCodecStatus.toString() for the sink with this address, or throws. */
    String getCodecStatus(String mac) = 1;

    /** Requests the codec, polls the stack for about 2.5 s and returns the resulting status string. */
    String setCodecConfig(String mac, String codecName, int codecType, int sampleRate, int bits, long specific1) = 2;

    /** BluetoothCodecType names from getSupportedCodecTypes() on Android 15+, empty otherwise. */
    List<String> supportedCodecs() = 3;
}
