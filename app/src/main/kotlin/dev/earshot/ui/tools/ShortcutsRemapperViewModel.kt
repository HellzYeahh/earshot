package dev.earshot.ui.tools

import androidx.lifecycle.viewModelScope
import dev.earshot.core.ButtonAction
import dev.earshot.core.ButtonAssignment
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.shortcut.AssistantCandidate
import dev.earshot.shortcut.ControlSignal
import dev.earshot.shortcut.DetectedShortcut
import dev.earshot.shortcut.DetectionSession
import dev.earshot.shortcut.FirstPressHint
import dev.earshot.shortcut.PhysicalControl
import dev.earshot.shortcut.PressStyle
import dev.earshot.shortcut.SecondaryButtonPolicy
import dev.earshot.shortcut.SignalOrigin
import dev.earshot.shortcut.HandlerStatus
import dev.earshot.shortcut.HfpHeadset
import dev.earshot.shortcut.ShortcutAction
import dev.earshot.shortcut.ShortcutButtonPolicy
import dev.earshot.shortcut.ShortcutEngine
import dev.earshot.shortcut.ShortcutSettings
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.UiMessenger
import dev.earshot.virtual.DemoPresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The headset the remapper edits: shown at the top, its address keys the saved choice. */
data class RemapperHeadset(
    val address: String,
    val name: String?,
    val earbuds: Boolean,
    /** Connected through Earshot (a Bose headset): its Shortcut button can be set from here. */
    val viaEarshot: Boolean,
) {
    companion object {
        /** Earshot's connected headphones first, else the phone's current HFP headset, else none (the default is edited). */
        fun resolve(state: HeadphoneState, hfp: HfpHeadset?): RemapperHeadset? {
            val address = state.connection.deviceAddress
            if (state.isConnected && address != null) {
                return RemapperHeadset(
                    address, HeadphoneLabels.displayName(state) ?: state.connection.deviceName,
                    HeadphoneLabels.isEarbuds(HeadphoneLabels.formFactor(state)), viaEarshot = true,
                )
            }
            return hfp?.let { RemapperHeadset(it.address, it.name, HeadphoneLabels.isEarbuds(HeadphoneLabels.formFactorFromName(it.name)), viaEarshot = false) }
        }
    }
}

/** Demo mode's "press a control" buttons in the detect sheet. */
enum class DemoPress { PLAY_PAUSE, VOLUME, BUTTON, ASSISTANT }

/** Everything the Controls screen shows. */
data class ShortcutsUiState(
    val handler: HandlerStatus = HandlerStatus.Unknown,
    val settings: ShortcutSettings = ShortcutSettings(),
    /** Null: nothing connected, the dropdown edits the default. */
    val headset: RemapperHeadset? = null,
    /** The Bose Shortcut button `[1.9]`, only for a headset connected through Earshot. */
    val button: ButtonAssignment? = null,
    /** Every `[1.9]` record, primary ([button]) first; earbuds may have one per bud. */
    val buttons: List<ButtonAssignment> = listOfNotNull(button),
    /** Other apps' VOICE_COMMAND / VOICE_ASSIST activities. */
    val assistants: List<AssistantCandidate> = emptyList(),
    /** What "automatic" resolves to on this phone right now. */
    val autoAssistant: AssistantCandidate? = null,
) {
    val choice: ShortcutAction get() = ShortcutButtonPolicy.displayedChoice(settings, headset?.address, button)
    val nativeModes: List<ButtonAction> get() = ShortcutButtonPolicy.nativeOptions(button?.supportedActions)
    val showFirstPressHint: Boolean get() = FirstPressHint.visible(choice, handler, settings.seenHeadsetPress)
    /** The buttons after the first, each with its own row. */
    val secondaryButtons: List<ButtonAssignment> get() = buttons.drop(1)
    /** Two or more Bose buttons on Voice assistant: they run the same Earshot action. */
    val sharesAssistant: Boolean get() = SecondaryButtonPolicy.sharesAssistant(buttons)
    /** The last detection of this headset's assistant gesture, with the user's name for the control. */
    val detected: DetectedShortcut? get() = settings.detectedFor(headset?.address)
}

class ShortcutsRemapperViewModel(
    private val engine: ShortcutEngine,
    private val headphones: HeadphoneRepository,
    messenger: UiMessenger,
) : EarshotViewModel(messenger) {

    private data class Lookups(
        val handler: HandlerStatus = HandlerStatus.Unknown,
        val assistants: List<AssistantCandidate> = emptyList(),
        val autoAssistant: AssistantCandidate? = null,
        val hfp: HfpHeadset? = null,
    )

    private val lookups = MutableStateFlow(Lookups())

    val state: StateFlow<ShortcutsUiState> = combine(engine.prefs.settings, headphones.state, lookups) { settings, hp, l ->
        val headset = RemapperHeadset.resolve(hp, l.hfp)
        ShortcutsUiState(
            handler = l.handler,
            settings = settings,
            headset = headset,
            button = engine.buttonSync.button(hp).takeIf { headset?.viaEarshot == true },
            buttons = if (headset?.viaEarshot == true) engine.buttonSync.buttons(hp) else emptyList(),
            assistants = l.assistants,
            autoAssistant = l.autoAssistant,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShortcutsUiState())

    /** The "Detect a button" session (shared with the trigger activity through the engine). */
    val detection: StateFlow<DetectionSession> = engine.detector.session

    fun startDetect() = engine.detector.start(state.value.headset?.address)

    fun stopDetect() = engine.detector.stop()

    /** The sheet closed: stop listening and forget the list. */
    fun closeDetect() = engine.detector.clear()

    /** The user's name for the control that sent the gesture, for the headset at the top. */
    fun nameDetected(control: PhysicalControl?, press: PressStyle?, customName: String?) = launchCatching {
        val address = state.value.headset?.address ?: return@launchCatching
        engine.prefs.update { it.withDetectedName(address, control, press, customName) }
    }

    /** A secondary Bose button (earbuds: the other bud): saved for that headset, then written. */
    fun chooseSecondary(buttonId: Int, mode: ButtonAction) = launchCatching {
        val headset = state.value.headset?.takeIf { it.viaEarshot } ?: return@launchCatching
        engine.prefs.update { it.withButtonChoice(headset.address, buttonId, mode.storedId) }
        engine.buttonSync.applySecondary(buttonId, mode)
    }

    /**
     * Demo mode's "press a control": a virtual unit that takes presses ([DemoPresses]) pushes what
     * real headphones would (the Bose unit: `[5.4]`, `[5.5]`, `[2.12]`). It is used only while the
     * connected headset has its pushes on, since a press can only reach the app as a push; the
     * assistant gesture, which can't travel over a virtual HFP link, and every control of a demo
     * without pushes (the Crusher, Soundcore, Sennheiser, Sony) go through the report path,
     * labelled as a demo.
     */
    fun simulate(press: DemoPress) {
        val hp = headphones.state.value
        val unit = DemoPresses.current()?.takeIf { hp.isConnected && hp.livePushEnabled }
        val now = System.currentTimeMillis()
        when (press) {
            DemoPress.ASSISTANT -> engine.simulateAssistantGesture()
            DemoPress.PLAY_PAUSE -> if (unit != null) unit.pressPlayPause() else {
                demoPlaying = !demoPlaying
                engine.detector.report(ControlSignal.PlayState(demoPlaying, SignalOrigin.DEMO, now))
            }
            DemoPress.VOLUME -> if (unit != null) unit.swipeVolume(+2) else {
                engine.detector.report(ControlSignal.Volume(demoVolume, demoVolume + 1, SignalOrigin.DEMO, now))
                demoVolume = (demoVolume + 1) % 15
            }
            DemoPress.BUTTON -> unit?.pressMainButton()
        }
    }

    private var demoPlaying = true
    private var demoVolume = 7

    override fun onCleared() {
        engine.detector.clear()
        super.onCleared()
    }

    init {
        // A headset coming or going through Earshot can change which HFP headset is current too.
        headphones.state.map { it.isConnected to it.connection.deviceAddress }.distinctUntilChanged()
            .onEach { refreshHeadset() }
            .launchIn(viewModelScope)
    }

    /** Re-reads the package manager and the connected headset; called on every resume. */
    fun refresh() {
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                Lookups(
                    handler = runCatching { engine.assistants.handlerStatus() }.getOrDefault(HandlerStatus.Unknown),
                    assistants = runCatching { engine.assistants.candidates() }.getOrDefault(emptyList()),
                    autoAssistant = runCatching { engine.assistants.forwardTarget(null) }.getOrNull(),
                    hfp = runCatching { engine.headsets.currentHeadset() }.getOrNull(),
                )
            }
            lookups.update { fresh }
        }
    }

    private fun refreshHeadset() {
        viewModelScope.launch {
            val hfp = runCatching { engine.headsets.currentHeadset() }.getOrNull()
            lookups.update { it.copy(hfp = hfp) }
        }
    }

    /**
     * Saves [action] for the headset at the top (or as the default with none), then puts a Bose
     * Shortcut button on the mode it needs. A refused write shows its message; the choice stays.
     */
    fun choose(action: ShortcutAction) = launchCatching {
        val headset = state.value.headset
        // A headphone mode only means something for the headset that has it.
        if (action is ShortcutAction.HeadphoneButton && headset?.viaEarshot != true) return@launchCatching
        engine.prefs.update { it.withChoice(headset?.address, headset?.name, action) }
        if (headset?.viaEarshot == true) engine.buttonSync.applyChoice(action)
    }

    fun setSpokenFeedback(enabled: Boolean) = launchCatching { engine.prefs.update { it.copy(spokenFeedback = enabled) } }
}
