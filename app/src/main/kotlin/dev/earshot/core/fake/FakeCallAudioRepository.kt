package dev.earshot.core.fake

import dev.earshot.core.CallAudioPreferences
import dev.earshot.core.CallAudioRepository
import dev.earshot.core.CallAudioState
import dev.earshot.core.HFP_GAIN_MAX
import dev.earshot.core.HeadphoneCommandException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/**
 * In-memory [CallAudioRepository] for previews and UI work.
 *
 * @param inCall start in a call on the headphones (mSBC) so the "live" UI renders.
 * @param shizukuReady false mimics Shizuku not running / not granted: the absolute-volume switch is
 *   unknown and its setter throws.
 */
class FakeCallAudioRepository(inCall: Boolean = true, private val shizukuReady: Boolean = true) : CallAudioRepository {
    private val _state = MutableStateFlow(
        CallAudioState(
            inCall = inCall,
            onHeadphones = inCall,
            routedDeviceName = if (inCall) "Bose QC Ultra Headphones" else null,
            callVolume = if (inCall) 4 else 0,
            callVolumeMax = 7,
            callCodec = "mSBC",
            wideband = true,
            callSampleRateHz = 16_000,
            codecReadAt = System.currentTimeMillis(),
            headsetCallGain = if (inCall) 9 else null,
            headsetMicGain = if (inCall) 15 else null,
            phoneNoiseReduction = if (inCall) false else null,
            absoluteVolumeDisabled = if (shizukuReady) false else null,
        ),
    )
    override val state: StateFlow<CallAudioState> = _state

    private val _preferences = MutableStateFlow(CallAudioPreferences())
    override val preferences: StateFlow<CallAudioPreferences> = _preferences

    override suspend fun setPreferences(p: CallAudioPreferences) {
        _preferences.value = p.copy(boostPercent = p.boostPercent.coerceIn(CallAudioPreferences.MIN_BOOST_PERCENT, 100))
    }

    override suspend fun boostNow() {
        val s = _state.value
        if (!s.inCall) throw HeadphoneCommandException("Start a call first: Android keeps a separate call volume for each output.")
        delay(100)
        val target = (s.callVolumeMax * _preferences.value.boostPercent / 100.0).roundToInt()
        // Android maps the call index onto the headset's 0..15 HFP gain.
        val gain = if (s.callVolumeMax > 0) (target * HFP_GAIN_MAX + s.callVolumeMax / 2) / s.callVolumeMax else null
        _state.update { it.copy(callVolume = target, headsetCallGain = gain, lastBoostAppliedAt = System.currentTimeMillis(), lastError = null) }
    }

    override suspend fun setAbsoluteVolumeDisabled(disabled: Boolean) {
        if (!shizukuReady) {
            val msg = "Changing absolute volume needs Shizuku: start it and grant Earshot."
            _state.update { it.copy(lastError = msg) }
            throw HeadphoneCommandException(msg)
        }
        delay(200)
        _state.update {
            it.copy(
                absoluteVolumeDisabled = disabled,
                absoluteVolumeNeedsReconnect = it.absoluteVolumeNeedsReconnect || it.absoluteVolumeDisabled != disabled,
                lastError = null,
            )
        }
    }

    override suspend fun refresh() { delay(100) }

    /** Preview/test helper: pretend a call started or ended. */
    fun simulateCall(active: Boolean) = _state.update {
        it.copy(inCall = active, onHeadphones = active, routedDeviceName = if (active) "Bose QC Ultra Headphones" else null)
    }
}
