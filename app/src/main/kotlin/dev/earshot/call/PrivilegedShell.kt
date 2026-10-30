package dev.earshot.call

/**
 * The slice of a privileged (shell-uid) shell this feature needs; faked in tests.
 *
 * Each flavour provides `ShizukuPrivilegedShell(context)`: the real one over Shizuku in the GitHub
 * build (`app/src/foss/`), a stub that is never available in the Google Play build (`app/src/play/`,
 * B8 in docs/research/play-readiness.md). Without it the call codec stays unknown, the
 * absolute-volume switch stays greyed out and a generic headset's first battery comes from the next
 * source.
 */
interface PrivilegedShell {
    /** Ready to run commands right now (Shizuku running and granted). */
    fun isAvailable(): Boolean
    suspend fun exec(cmd: String): Result<String>
    /** Called whenever the shell may have become available (Shizuku binder (re)received). */
    fun setOnAvailableListener(listener: () -> Unit) {}
}
