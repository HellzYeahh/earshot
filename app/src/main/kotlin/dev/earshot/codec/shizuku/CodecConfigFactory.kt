package dev.earshot.codec.shizuku

import android.annotation.SuppressLint
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecType
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dev.earshot.codec.CodecMasks
import dev.earshot.codec.CodecNames
import dev.earshot.codec.CodecRequest

/**
 * Builds the `BluetoothCodecConfig` for a [CodecRequest], inside the user service.
 *  - Android 15+: codecs are objects; vendor codecs (Samsung Scalable, aptX Adaptive) only work
 *    through the `BluetoothCodecType` the stack advertises, so that is looked up by name first;
 *  - Android 13–14 (or no advertised type): the public Builder with `setCodecType(int)`;
 *  - Android 9–12L: the hidden 9-argument constructor, unchanged since 8.1.
 */
internal object CodecConfigFactory {

    /** @param supportedTypes lazily fetches `getSupportedCodecTypes()` (Android 15+ only). */
    fun build(request: CodecRequest, supportedTypes: () -> List<Any>): Any {
        val channel = 0 // CHANNEL_MODE_NONE: never force mono
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return buildModern(request, channel, supportedTypes)
        val type = requireType(request)
        Log.i(SVC_TAG, "config via hidden constructor, type $type")
        return legacyConstructor(type, request, channel)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun buildModern(request: CodecRequest, channel: Int, supportedTypes: () -> List<Any>): BluetoothCodecConfig {
        val b = BluetoothCodecConfig.Builder()
            .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
            .setSampleRate(request.sampleRateMask)
            .setBitsPerSample(request.bitsMask)
            .setChannelMode(channel)
            .setCodecSpecific1(request.codecSpecific1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val type = advertisedType(supportedTypes(), request.codecName)
            if (type != null) {
                b.setExtendedCodecType(type)
                Log.i(SVC_TAG, "config via setExtendedCodecType(${type.codecName}, id ${type.codecId})")
                return b.build()
            }
            Log.i(SVC_TAG, "no advertised BluetoothCodecType named ${request.codecName}; using setCodecType")
        }
        val type = requireType(request)
        @Suppress("DEPRECATION")
        b.setCodecType(type)
        Log.i(SVC_TAG, "config via setCodecType($type)")
        return b.build()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun advertisedType(types: List<Any>, normalizedName: String): BluetoothCodecType? =
        types.filterIsInstance<BluetoothCodecType>().firstOrNull { CodecNames.normalize(it.codecName) == normalizedName }

    private fun requireType(request: CodecRequest): Int {
        if (request.codecType == CodecRequest.UNKNOWN_TYPE) {
            throw IllegalArgumentException("Don't know this Android build's id for ${request.codecName}")
        }
        return request.codecType
    }

    // Looked up by name: below API 33 the class is hidden, so a class literal would be an API-33 reference.
    @SuppressLint("PrivateApi")
    private fun legacyConstructor(type: Int, request: CodecRequest, channel: Int): Any {
        val cls = Class.forName("android.bluetooth.BluetoothCodecConfig")
        val i = Int::class.javaPrimitiveType
        val l = Long::class.javaPrimitiveType
        return cls.getConstructor(i, i, i, i, i, l, l, l, l).newInstance(
            type, CodecMasks.PRIORITY_HIGHEST, request.sampleRateMask, request.bitsMask, channel,
            request.codecSpecific1, 0L, 0L, 0L,
        )
    }
}
