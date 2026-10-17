package dev.earshot.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log
import dev.earshot.appContainer

/**
 * Manifest-registered receiver for `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` /
 * `ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION`, the way well-behaved media players hand their
 * audio session to "the system equaliser". We note the session in [SessionRegistry]; the
 * repository decides whether to attach an effect.
 *
 * Known limitations (all OEM/framework behaviour, nothing we can fix here):
 *  - Android 8+ stops delivering most implicit broadcasts to manifest receivers, but this pair is
 *    on the exemption list. Some OEM builds (notably older Samsung/Xiaomi ROMs) still only deliver
 *    them to apps that have been launched at least once since boot, or that are not in a
 *    "deep sleep" battery bucket. If sessions never arrive, opening Earshot once usually fixes it.
 *  - Only apps that *send* the broadcast are seen. Many modern players (YouTube, some browsers,
 *    games) don't; for them the optional global-session fallback is the only route.
 *  - Sessions opened before our process existed are not replayed. The player will re-send on
 *    its next track/start in practice, so this self-heals within a song.
 */
class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
        if (session == AudioEffect.ERROR) return
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> SessionRegistry.open(session, pkg)
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> SessionRegistry.close(session)
            else -> return
        }
        // The container builds repositories lazily. If this broadcast is what woke the process,
        // nobody is observing the registry yet; touching `tuning` constructs the real repository
        // (once the integrator wires it) so the engine can attach without the user opening the app.
        runCatching { context.appContainer.tuning }
            .onFailure { Log.w(TAG, "tuning repository not available: ${it.message}") }
    }

    private companion object { const val TAG = "Earshot/SessionRx" }
}
