package dev.earshot.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import dev.earshot.appContainer
import dev.earshot.core.AudioTuningRepository
import dev.earshot.core.AutomationRepository
import dev.earshot.core.CallAudioRepository
import dev.earshot.core.CodecRepository
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.MicCheckRepository
import dev.earshot.core.fake.FakeAudioTuningRepository
import dev.earshot.core.fake.FakeAutomationRepository
import dev.earshot.core.fake.FakeCallAudioRepository
import dev.earshot.core.fake.FakeCodecRepository
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.core.fake.FakeMicCheckRepository
import dev.earshot.ui.common.UiMessenger

/**
 * Everything the UI layer needs, bundled so ViewModels can be built from one object. At runtime
 * it wraps [dev.earshot.AppContainer]; previews and tests use [fakeUiDependencies].
 */
class UiDependencies(
    val headphones: HeadphoneRepository,
    val tuning: AudioTuningRepository,
    val codec: CodecRepository,
    val micCheck: MicCheckRepository,
    val automation: AutomationRepository,
    val callAudio: CallAudioRepository,
    val messenger: UiMessenger = UiMessenger(),
) {
    companion object {
        fun from(context: Context): UiDependencies {
            val c = context.appContainer
            // Process-wide messenger: ViewModels keep the instance they were built with across
            // configuration changes, so the root must collect from that same one.
            return UiDependencies(c.headphones, c.tuning, c.codec, c.micCheck, c.automation, c.callAudio, UiMessenger.shared)
        }
    }
}

val LocalUiDependencies = staticCompositionLocalOf<UiDependencies> {
    error("No UiDependencies provided; wrap the tree in EarshotApp or EarshotPreview")
}

fun fakeUiDependencies(startConnected: Boolean = true): UiDependencies = UiDependencies(
    headphones = FakeHeadphoneRepository(startConnected = startConnected),
    tuning = FakeAudioTuningRepository(),
    codec = FakeCodecRepository(),
    micCheck = FakeMicCheckRepository(),
    automation = FakeAutomationRepository(),
    callAudio = FakeCallAudioRepository(),
)
