package dev.earshot.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single source of truth for the headphones. One implementation lives inside the
 * foreground service and is shared by the UI, the tile, the widget, the notification and
 * the automation engine.
 *
 * Commands are `suspend` and throw [HeadphoneCommandException] when the headphones refuse or
 * are not connected. On success [state] is updated before the call returns.
 */
interface HeadphoneRepository {
    val state: StateFlow<HeadphoneState>
    val events: SharedFlow<HeadphoneEvent>

    /** Start (or restart) connecting to the paired QC Ultra headphones. Safe to call repeatedly. */
    fun connect()
    fun disconnect()

    /** Bonded devices to offer when the status is [ConnectionStatus.NO_PAIRED_DEVICE]. */
    suspend fun deviceCandidates(): List<HeadphoneCandidate> = emptyList()

    /** Remember [address] as the headphones and connect to it. */
    suspend fun chooseDevice(address: String, name: String?) = connect()

    /** Re-read everything from the headphones. */
    suspend fun refresh()

    // ---- Listening modes -------------------------------------------------------------------

    suspend fun setMode(index: Int)

    /** Write a custom slot (4..10). Presets 0..3 are firmware-locked and will throw. */
    suspend fun saveCustomMode(index: Int, name: String, settings: NoiseSettings)

    suspend fun setFavorites(indices: Set<Int>)

    // ---- Live noise control ([31.10], applies immediately) ---------------------------------

    suspend fun setNoise(settings: NoiseSettings)
    suspend fun setCancellingLevel(level: Int) = setNoise(requireNoise().copy(cancellingLevel = level))
    suspend fun setAncEnabled(enabled: Boolean) = setNoise(requireNoise().copy(ancEnabled = enabled))
    suspend fun setWindBlock(enabled: Boolean) = setNoise(requireNoise().copy(windBlock = enabled))
    suspend fun setSpatialAudio(mode: SpatialAudio) = setNoise(requireNoise().copy(spatialAudio = mode))

    // ---- Settings block --------------------------------------------------------------------

    suspend fun setEq(eq: HeadphoneEq)
    suspend fun setSidetone(level: Sidetone, persistent: Boolean = true)
    suspend fun setAutoPlayPause(enabled: Boolean)
    suspend fun setAutoAnswer(enabled: Boolean)
    suspend fun setMultipoint(enabled: Boolean)
    suspend fun setVoicePrompts(prompts: VoicePrompts)
    suspend fun setShortcutButton(action: ButtonAction)

    /**
     * `[1.9]` for one record of [HeadphoneState.shortcutButtons] (earbuds: one per bud). The
     * default ignores the record and writes the primary button, which is right for single-button
     * products.
     */
    suspend fun setShortcutButton(buttonId: Int, eventType: Int, action: ButtonAction): Unit = setShortcutButton(action)
    /** May throw with [HeadphoneCommandException.notSupportedByFirmware] set. */
    suspend fun setDeviceName(name: String)

    // ---- Devices and power -----------------------------------------------------------------

    /** Route audio to another paired device. The caller should tell the user to start playback there first. */
    suspend fun switchToDevice(address: String)
    suspend fun enterPairingMode()
    suspend fun powerOff()

    // ---- Added 2026-10-17 (Bose app 13.3.4 tables). Defaults throw so other implementations keep compiling. ----

    /**
     * Reset custom slot [index] (4..10) to the firmware's empty state with `[31.9]` START, then
     * re-read it. Untested on hardware.
     */
    suspend fun resetCustomMode(index: Int): Unit = unsupported()

    /**
     * Switch LE Audio on or off (`[1.37]`). **The headphones restart** after the change: the link
     * drops, the call tolerates that, returns with [HeadphoneState.leAudioEnabled] = [enabled],
     * and the connection manager reconnects by itself (expect ~10-20 s of "Connecting").
     *
     * Suggested warning before calling (UI copy):
     * "Your headphones will restart to switch LE Audio on/off. Audio stops for a few seconds and
     * Earshot reconnects by itself. With LE Audio on, the voice-assistant and Spotify Tap
     * shortcuts and multipoint may not work, and this phone may keep using Classic Bluetooth
     * (AAC/SBC) if it does not support LE Audio for these headphones."
     */
    suspend fun setLeAudioEnabled(enabled: Boolean): Unit = unsupported()

    /**
     * **Experimental, untested on hardware.** Write the `[5.7]` latency value (milliseconds,
     * inside [HeadphoneState.latencyRangeMs]). The Bose app has this setter but never calls it,
     * and the value may be a measurement the firmware overwrites; the state shows what the
     * headphones echo back.
     *
     * Suggested UI copy for the slider: "Experimental: asks the headphones for a different audio
     * latency. The Bose app never uses this, so the headphones may ignore it or change the sound
     * timing unexpectedly. Reset by moving it back to the original value (shown in grey)."
     */
    suspend fun setLatencyMs(ms: Int): Unit = unsupported()

    /** Turn the touch controls on or off (`[1.34]`, wire bit 1 = on). */
    suspend fun setTouchControls(enabled: Boolean): Unit = unsupported()

    /** `[1.4]` auto-off timer in minutes (0 = never). Only offered when [HeadphoneState.autoOffMinutes] is non-null. */
    suspend fun setAutoOff(minutes: Int): Unit = unsupported()

    /** Absolute headphone volume 0..[HeadphoneState.volumeMax] (`[5.5]` SET_GET). Untested on hardware. */
    suspend fun setVolume(level: Int): Unit = unsupported()

    /**
     * Answer a pending [HeadphoneState.pairing]: true when the code matches what the headphones
     * announced, false to cancel. Does nothing when no pairing is waiting.
     */
    suspend fun answerPairing(accept: Boolean): Unit = unsupported()

    /** Forget the secure-session pairing with the saved headphones; the next connect pairs again. */
    suspend fun forgetPairing(): Unit = unsupported()

    /**
     * Skullcandy Crusher only: change the haptic bass. Any argument left null stays as it is on the
     * headphones; [mode] is 0 = high, 1 = medium, 2 = low. Throws on every other model.
     */
    suspend fun setCrusherBass(active: Boolean? = null, intensity: Int? = null, mode: Int? = null): Unit = unsupported()

    /** Play / pause / next / previous / stop through the headphones (`[5.3]` START). Untested on hardware. */
    suspend fun transport(control: TransportControl): Unit = unsupported()

    /**
     * Lab only, **experimental**: one line for the firmware's Print debug console (`[22.x]`):
     * `bind <arg>`, `unbind <arg>`, `verbosity <arg>` or `output [n]`. Returns the frames that came
     * back, one per line, as text. Throws for an unknown verb or when not connected.
     */
    suspend fun printConsole(command: String): String = unsupported()

    // ---- Lab (read-only exploration) -------------------------------------------------------

    /** Send one GET to [block].[function] and return every frame that came back. Never writes. */
    suspend fun readRaw(block: Int, function: Int): List<RawFrame>

    /** GET every function 0..[maxFunction] in each of [blocks]; emits as results arrive. */
    fun scanFunctions(blocks: List<Int>, maxFunction: Int = 40): Flow<LabReading>

    private fun requireNoise(): NoiseSettings =
        state.value.noise ?: throw HeadphoneCommandException("Noise settings not loaded yet")

    private fun unsupported(): Nothing = throw HeadphoneCommandException("Not supported by this connection")
}
