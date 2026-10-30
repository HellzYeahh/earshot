package dev.earshot.call

import android.content.Context

/**
 * The Google Play build has no Shizuku (B8, docs/research/play-readiness.md), so this shell is never
 * available: the call codec stays unknown, the absolute-volume switch stays greyed out and a generic
 * headset's first battery comes from the next source. It keeps the GitHub build's name and
 * constructor only so the shared code that creates one (CallAudioRepositoryImpl's default,
 * AppContainer) compiles unchanged.
 */
class ShizukuPrivilegedShell(@Suppress("UNUSED_PARAMETER") context: Context) : PrivilegedShell {
    override fun isAvailable(): Boolean = false

    override suspend fun exec(cmd: String): Result<String> =
        Result.failure(UnsupportedOperationException("Not available in this version of Earshot"))
}
