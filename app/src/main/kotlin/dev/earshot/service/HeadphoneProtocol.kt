package dev.earshot.service

import dev.earshot.bt.BmapRequester
import dev.earshot.core.ButtonAction
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.core.Sidetone
import dev.earshot.core.TransportControl
import dev.earshot.core.VoicePrompts
import dev.earshot.protocol.BmapPacket

/**
 * The seam between the transport ([ServiceHeadphoneRepository] + [dev.earshot.bt.BmapConnection])
 * and the typed QC Ultra protocol in `:protocol`. One method per repository command, plus
 * [refresh] and the unsolicited-frame hook. [QcUltra2Protocol] is the real implementation.
 *
 * Contract for implementers:
 *  - Every method receives the open connection (as a [BmapRequester], so tests can script one)
 *    and the repository's current [HeadphoneState], and returns the state as it should look after
 *    the command. Return `current.copy(...)` with only the fields the command touched; the
 *    repository preserves the `connection`, `isBusy` and `lastUpdatedMillis` fields itself.
 *  - Talk to the headphones only through [BmapRequester.request] / [BmapRequester.send].
 *    Use [dev.earshot.bt.ReplyMode.MULTI] for `[31.6]` / `[31.1]` dumps.
 *  - On an ERROR frame throw [dev.earshot.protocol.BmapException]; the repository turns it into
 *    a [HeadphoneCommandException] and sets `notSupportedByFirmware` from
 *    `BmapException.operatorNotSupported` (error 5). Throw [HeadphoneCommandException] directly when you
 *    already have a user-readable message.
 *  - Wire conversions (inverted CNC level, sidetone order, EQ layout) live here or in
 *    `:protocol`, never in `core`.
 *  - Methods run one at a time (the repository serialises commands), on a background dispatcher.
 */
interface HeadphoneProtocol {
    /** Read everything that fills [HeadphoneState]. Called right after connect and by `refresh()`. */
    suspend fun refresh(c: BmapRequester, current: HeadphoneState): HeadphoneState

    suspend fun setMode(c: BmapRequester, current: HeadphoneState, index: Int): HeadphoneState
    suspend fun saveCustomMode(c: BmapRequester, current: HeadphoneState, index: Int, name: String, settings: NoiseSettings): HeadphoneState
    suspend fun setFavorites(c: BmapRequester, current: HeadphoneState, indices: Set<Int>): HeadphoneState
    suspend fun setNoise(c: BmapRequester, current: HeadphoneState, settings: NoiseSettings): HeadphoneState

    suspend fun setEq(c: BmapRequester, current: HeadphoneState, eq: HeadphoneEq): HeadphoneState
    suspend fun setSidetone(c: BmapRequester, current: HeadphoneState, level: Sidetone, persistent: Boolean): HeadphoneState
    suspend fun setAutoPlayPause(c: BmapRequester, current: HeadphoneState, enabled: Boolean): HeadphoneState
    suspend fun setAutoAnswer(c: BmapRequester, current: HeadphoneState, enabled: Boolean): HeadphoneState
    suspend fun setMultipoint(c: BmapRequester, current: HeadphoneState, enabled: Boolean): HeadphoneState
    suspend fun setVoicePrompts(c: BmapRequester, current: HeadphoneState, prompts: VoicePrompts): HeadphoneState
    suspend fun setShortcutButton(c: BmapRequester, current: HeadphoneState, action: ButtonAction): HeadphoneState
    /** One `[1.9]` record by its own button id and event type; the default writes the primary button. */
    suspend fun setShortcutButton(c: BmapRequester, current: HeadphoneState, buttonId: Int, eventType: Int, action: ButtonAction): HeadphoneState =
        setShortcutButton(c, current, action)
    suspend fun setDeviceName(c: BmapRequester, current: HeadphoneState, name: String): HeadphoneState

    suspend fun switchToDevice(c: BmapRequester, current: HeadphoneState, address: String): HeadphoneState
    /** The headphones usually drop the link right after; the manager reconnects on its own. */
    suspend fun enterPairingMode(c: BmapRequester, current: HeadphoneState): HeadphoneState
    /** `[7.4]` START `[0]`. No reply is expected: the link goes away. Prefer [BmapRequester.send]. */
    suspend fun powerOff(c: BmapRequester, current: HeadphoneState): HeadphoneState

    // ── Added 2026-10-17; defaults keep other implementations (the stub) compiling ────────

    /**
     * Once per connection, before the first [refresh]: subscribe to the headphones' own STATUS
     * pushes (`[9.2]`) and anything else that only needs doing once. Returns the state with
     * [HeadphoneState.livePushEnabled] set. Must tolerate failures (the repository falls back to
     * polling when pushes stay off).
     */
    suspend fun onConnected(c: BmapRequester, current: HeadphoneState): HeadphoneState = current

    /** Fallback poll when pushes are off: re-read the battery (and nothing else). */
    suspend fun pollBattery(c: BmapRequester, current: HeadphoneState): HeadphoneState = current

    suspend fun resetCustomMode(c: BmapRequester, current: HeadphoneState, index: Int): HeadphoneState = notSupported()
    /** The headphones restart after `[1.37]`: tolerate the link dropping. */
    suspend fun setLeAudioEnabled(c: BmapRequester, current: HeadphoneState, enabled: Boolean): HeadphoneState = notSupported()
    suspend fun setLatencyMs(c: BmapRequester, current: HeadphoneState, ms: Int): HeadphoneState = notSupported()
    suspend fun setTouchControls(c: BmapRequester, current: HeadphoneState, enabled: Boolean): HeadphoneState = notSupported()
    /** `[1.4]` auto-off minutes, 0 = never (choices: [dev.earshot.protocol.qcultra2.AutoOffOptions]). */
    suspend fun setAutoOff(c: BmapRequester, current: HeadphoneState, minutes: Int): HeadphoneState = notSupported()
    suspend fun setVolume(c: BmapRequester, current: HeadphoneState, level: Int): HeadphoneState = notSupported()
    suspend fun transport(c: BmapRequester, current: HeadphoneState, control: TransportControl): HeadphoneState = notSupported()
    /** Lab console; returns text, never touches state. */
    suspend fun printConsole(c: BmapRequester, command: String): String = notSupported()

    /**
     * A frame nobody asked for (battery push, mode changed with the button, source switched).
     * Return the updated state, or null when the frame is irrelevant. Must not suspend or talk
     * to the headphones; schedule a `refresh()` through the repository if a re-read is needed.
     */
    fun onUnsolicited(frame: BmapPacket, current: HeadphoneState): HeadphoneState?

    private fun notSupported(): Nothing = throw HeadphoneCommandException("Not supported by this protocol")
}

/**
 * Placeholder until the typed protocol lands: connecting works and the Lab explorer can GET
 * anything, but every command throws. [refresh] deliberately succeeds without reading so the
 * connection path shows CONNECTED cleanly on a phone.
 */
open class StubHeadphoneProtocol : HeadphoneProtocol {
    override suspend fun refresh(c: BmapRequester, current: HeadphoneState): HeadphoneState = current

    override suspend fun setMode(c: BmapRequester, current: HeadphoneState, index: Int) = notImplemented()
    override suspend fun saveCustomMode(c: BmapRequester, current: HeadphoneState, index: Int, name: String, settings: NoiseSettings) = notImplemented()
    override suspend fun setFavorites(c: BmapRequester, current: HeadphoneState, indices: Set<Int>) = notImplemented()
    override suspend fun setNoise(c: BmapRequester, current: HeadphoneState, settings: NoiseSettings) = notImplemented()
    override suspend fun setEq(c: BmapRequester, current: HeadphoneState, eq: HeadphoneEq) = notImplemented()
    override suspend fun setSidetone(c: BmapRequester, current: HeadphoneState, level: Sidetone, persistent: Boolean) = notImplemented()
    override suspend fun setAutoPlayPause(c: BmapRequester, current: HeadphoneState, enabled: Boolean) = notImplemented()
    override suspend fun setAutoAnswer(c: BmapRequester, current: HeadphoneState, enabled: Boolean) = notImplemented()
    override suspend fun setMultipoint(c: BmapRequester, current: HeadphoneState, enabled: Boolean) = notImplemented()
    override suspend fun setVoicePrompts(c: BmapRequester, current: HeadphoneState, prompts: VoicePrompts) = notImplemented()
    override suspend fun setShortcutButton(c: BmapRequester, current: HeadphoneState, action: ButtonAction) = notImplemented()
    override suspend fun setDeviceName(c: BmapRequester, current: HeadphoneState, name: String) = notImplemented()
    override suspend fun switchToDevice(c: BmapRequester, current: HeadphoneState, address: String) = notImplemented()
    override suspend fun enterPairingMode(c: BmapRequester, current: HeadphoneState) = notImplemented()
    override suspend fun powerOff(c: BmapRequester, current: HeadphoneState) = notImplemented()

    override fun onUnsolicited(frame: BmapPacket, current: HeadphoneState): HeadphoneState? = null

    private fun notImplemented(): Nothing = throw HeadphoneCommandException("Not implemented yet")
}
