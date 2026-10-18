package dev.earshot.core

import kotlinx.coroutines.flow.StateFlow

/**
 * Phone-call audio as Earshot sees it. What Android lets an app do here is narrow, and the UI should
 * say so: the uplink (your voice to the other party) cannot be processed by any app and the
 * headphones have no mic EQ, so "sounding better" comes down to the call *codec* (CVSD 8 kHz vs
 * mSBC 16 kHz vs LC3) which this exposes read-only. What we can change is the downlink volume
 * (`STREAM_VOICE_CALL`) and, through Shizuku, Android's Bluetooth absolute-volume switch.
 */
data class CallAudioState(
    /** Audio mode is `MODE_IN_CALL` (telephony) or `MODE_IN_COMMUNICATION` (VoIP: WhatsApp, Meet, ...). */
    val inCall: Boolean = false,
    /** Call audio currently routed to a Bluetooth headset (HFP SCO or LE Audio). */
    val onHeadphones: Boolean = false,
    /** Product name of the device the call is routed to, when Android reports one ("Bose QC Ultra Headphones"). */
    val routedDeviceName: String? = null,
    /** `STREAM_VOICE_CALL` index for the current call route, live while in a call. */
    val callVolume: Int = 0,
    val callVolumeMax: Int = 0,
    /**
     * "CVSD", "mSBC", "LC3" (HFP super-wideband), "aptX SWB", "LC3 (LE Audio)", or null when unknown
     * (no Shizuku, or the dump didn't say). While idle this is the codec of the *last* call seen
     * ([codecReadAt] tells when); the UI should label it accordingly.
     */
    val callCodec: String? = null,
    /** mSBC / LC3 / aptX SWB → true, CVSD → false, null unknown. */
    val wideband: Boolean? = null,
    /** Voice sample rate implied by (or read alongside) [callCodec]: 8000, 16000, 32000 ... */
    val callSampleRateHz: Int? = null,
    /** Wall-clock millis of the last successful codec read. */
    val codecReadAt: Long? = null,
    /**
     * HFP speaker gain of the call link, 0..[HFP_GAIN_MAX] (`+VGS`): the call volume as the
     * headset applies it, read with the codec. Shows whether a boost reached the headphones. null
     * when unknown (no Shizuku, LE Audio call, a build that doesn't print it).
     */
    val headsetCallGain: Int? = null,
    /** HFP microphone gain of the call link, 0..[HFP_GAIN_MAX] (`+VGM`); null when unknown. */
    val headsetMicGain: Int? = null,
    /**
     * The phone's own echo cancelling / noise reduction is on for this call (true) or the headset
     * switched it off with `AT+NREC=0` (false); null when the build doesn't print it.
     */
    val phoneNoiseReduction: Boolean? = null,
    /** Android's "Disable absolute volume" switch; null when Shizuku is unavailable (can't read it). */
    val absoluteVolumeDisabled: Boolean? = null,
    /**
     * True after [CallAudioRepository.setAbsoluteVolumeDisabled] changed the value, until the
     * headphones reconnect: Android only applies the switch on the next Bluetooth connection.
     */
    val absoluteVolumeNeedsReconnect: Boolean = false,
    /** Wall-clock millis of the last call-volume boost (automatic or [CallAudioRepository.boostNow]). */
    val lastBoostAppliedAt: Long? = null,
    /** Last failure, user-readable. Cleared by the next successful operation of the same kind. */
    val lastError: String? = null,
)

/** Top of the HFP speaker / mic gain scale (HFP 1.8 §4.29: 0..15). */
const val HFP_GAIN_MAX = 15

data class CallAudioPreferences(
    /** Raise the call volume automatically when a call starts on the headphones. */
    val boostEnabled: Boolean = true,
    /** Target, in % of the maximum call volume (clamped to [MIN_BOOST_PERCENT]..100). Auto-boost never lowers. */
    val boostPercent: Int = 100,
    /** Also raise the headphones' own volume over BMAP when they are the call device and connected. */
    val alsoRaiseHeadset: Boolean = true,
) {
    companion object {
        const val MIN_BOOST_PERCENT = 10
    }
}

interface CallAudioRepository {
    val state: StateFlow<CallAudioState>
    val preferences: StateFlow<CallAudioPreferences>

    suspend fun setPreferences(p: CallAudioPreferences)

    /**
     * Sets the call volume to exactly [CallAudioPreferences.boostPercent] of max now (and the headset
     * volume if [CallAudioPreferences.alsoRaiseHeadset]). Throws [HeadphoneCommandException] when no
     * call is active: Android keeps one call volume per output device, so it only means something
     * while the call is on the headphones.
     */
    suspend fun boostNow()

    /**
     * Writes Android's Bluetooth absolute-volume switch through Shizuku. Takes effect after the
     * headphones reconnect ([CallAudioState.absoluteVolumeNeedsReconnect]). Throws
     * [HeadphoneCommandException] (and sets [CallAudioState.lastError]) when Shizuku isn't ready or
     * the write is refused.
     */
    suspend fun setAbsoluteVolumeDisabled(disabled: Boolean)

    /** Re-read mode, route, volume, absolute-volume switch and (during a call) the codec. */
    suspend fun refresh()
}
