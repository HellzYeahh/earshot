package dev.earshot.core

import kotlinx.coroutines.flow.StateFlow

/**
 * The Sleep tab (0.9, docs/sleep-mode.md): tonight's timer and sounds, the wake alarms and saved
 * mixes, for any headset. Headset steps (noise off, touch lock, disconnect…) are extras the
 * current driver may not have; [headset] says which, and a step that can't run becomes a
 * [SleepNote] instead of an error.
 *
 * Commands are `suspend`, update the flows before returning and throw
 * [HeadphoneCommandException] with a user-readable message; the UI uses `runCatching`.
 */
interface SleepRepository {
    val state: StateFlow<SleepState>

    /** Tonight's settings, kept between nights. */
    val plan: StateFlow<SleepPlan>
    val alarms: StateFlow<List<SleepAlarm>>
    val mixes: StateFlow<List<SavedMix>>
    val headset: StateFlow<SleepHeadset>
    val permissions: StateFlow<SleepPermissions>

    suspend fun updatePlan(plan: SleepPlan)

    /**
     * Starts the session with [plan]. Call it from a visible screen or a notification / tile tap
     * only: Android 17 silences audio from a service started in the background.
     */
    suspend fun start()

    /** Adds [minutes] to a running session (the "+15 min" button). */
    suspend fun extend(minutes: Int)

    /** Ends the session now: sounds fade quickly, night extras are put back, no end action. */
    suspend fun stop()

    /** Plays [layers] while the tab is open (choosing sounds); stopped by [stopPreview] or [start]. */
    fun preview(layers: List<SleepLayer>)
    fun stopPreview()

    suspend fun saveAlarm(alarm: SleepAlarm)
    suspend fun deleteAlarm(id: String)
    suspend fun snooze()
    suspend fun dismissAlarm()

    /** Plays an alarm tone ([SleepAlarm.toneId]) briefly on the chosen output; null stops it. */
    fun previewAlarmTone(toneId: String?, output: AlarmOutput = AlarmOutput.EARBUDS)

    suspend fun saveMix(name: String, layers: List<SleepLayer>)
    suspend fun deleteMix(id: String)

    /** The user's own sounds, oldest first. */
    val customSounds: StateFlow<List<CustomSound>>

    /**
     * Copies the audio file at [uri] (a `content://` document the user picked) into Earshot and
     * makes it a loop called [name]. Throws [HeadphoneCommandException] when the file can't be
     * read or played, or is shorter than a few seconds.
     */
    suspend fun importCustomSound(uri: String, name: String): CustomSound

    /** Deletes an own sound, and takes it out of tonight's mix and the saved mixes. */
    suspend fun deleteCustomSound(id: String)

    /** Re-reads [permissions], e.g. when the tab comes back from Settings. */
    fun refreshPermissions()
}
