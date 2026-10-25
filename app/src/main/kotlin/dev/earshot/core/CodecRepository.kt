package dev.earshot.core

import kotlinx.coroutines.flow.StateFlow

/** What the phone is currently streaming to the headphones over A2DP. */
data class CodecInfo(
    val codecName: String,          // "AAC", "SBC", "aptX", "aptX HD", "aptX Adaptive", "LDAC", "LC3", "Samsung Scalable", ...
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val channelMode: String? = null, // "Stereo" / "Mono" / "Dual"
    /** Codec-specific quality mode (LDAC quality, aptX Adaptive mode, ...). */
    val qualityLabel: String? = null,
)

data class CodecOption(
    val codecName: String,
    val sampleRatesHz: List<Int>,
    val bitDepths: List<Int>,
    val qualityModes: List<String> = emptyList(),
    /** Supported by both the phone and the headphones. */
    val isSelectable: Boolean,
)

data class CodecSelection(
    val codecName: String,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val qualityMode: String? = null,
)

enum class PrivilegeState {
    /** Neither Shizuku nor root available: display only, link to Developer options. */
    UNAVAILABLE,
    SHIZUKU_NOT_RUNNING,
    SHIZUKU_PERMISSION_NEEDED,
    GRANTED,
}

enum class ConnectionProfile { BEST_QUALITY, MOST_STABLE }

data class CodecState(
    val a2dpConnected: Boolean = false,
    val current: CodecInfo? = null,
    val options: List<CodecOption> = emptyList(),
    val privilege: PrivilegeState = PrivilegeState.UNAVAILABLE,
    val lastError: String? = null,
    /**
     * True when Shizuku is ready (its binder is alive and Earshot holds its permission), on any
     * Android version: switching runs in a Shizuku user service as the shell user. Also true when
     * [viaCompanionLink]. While false the UI hides the picker and profiles, shows
     * [CODEC_SWITCH_UNSUPPORTED_MESSAGE] and keeps the Developer options button.
     */
    val canSwitch: Boolean = true,
    /**
     * Experimental (0.8): the codec is read and switched through the headset's Companion Device
     * Manager link (Android 13+, universal remote), with no Shizuku. True once the phone's stack
     * accepted such a call; false again for the rest of the process when it refused one.
     */
    val viaCompanionLink: Boolean = false,
)

/** Shown (and thrown by [CodecRepository.selectCodec]) when [CodecState.canSwitch] is false. */
const val CODEC_SWITCH_UNSUPPORTED_MESSAGE = "Start Shizuku and grant Earshot to switch codecs."

interface CodecRepository {
    val state: StateFlow<CodecState>
    suspend fun refresh()
    /** Ask Shizuku for permission (shows its dialog). */
    suspend fun requestPrivilege()
    /**
     * Throws [HeadphoneCommandException] when not privileged, when [CodecState.canSwitch] is false
     * (message [CODEC_SWITCH_UNSUPPORTED_MESSAGE]) or the switch is refused.
     */
    suspend fun selectCodec(selection: CodecSelection)
    suspend fun applyProfile(profile: ConnectionProfile)
    /** Deep link into Developer options for the manual fallback. */
    fun openDeveloperCodecSettings()
}

// ---- Mic check ------------------------------------------------------------------------------

enum class MicCheckPhase { IDLE, RECORDING, READY, PLAYING, NO_PERMISSION, ERROR }

data class MicCheckState(
    val phase: MicCheckPhase = MicCheckPhase.IDLE,
    val recordedSeconds: Float = 0f,
    val playbackPositionSeconds: Float = 0f,
    /** Peak level 0..1 while recording, for a simple meter. */
    val inputLevel: Float = 0f,
    /** True when the recording used the headset microphone (SCO) rather than the phone mic. */
    val usedHeadsetMic: Boolean? = null,
    val message: String? = null,
)

interface MicCheckRepository {
    val state: StateFlow<MicCheckState>
    suspend fun record(maxSeconds: Int = 8)
    suspend fun play()
    fun stop()
    fun discard()
}
