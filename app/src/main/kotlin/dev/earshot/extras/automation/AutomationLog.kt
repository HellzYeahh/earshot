package dev.earshot.extras.automation

/**
 * Where the engine writes "what fired" lines. [AutomationRepositoryImpl] persists them; the
 * core [dev.earshot.core.AutomationRepository] contract only exposes the read side, so the
 * engine checks whether its repository also implements this and otherwise logs to Logcat.
 */
interface AutomationLog {
    /** Prepend one human-readable line, e.g. `12:04 Wi-Fi 'Home' → Quiet`. */
    fun append(entry: String)
}
