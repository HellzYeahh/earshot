package dev.earshot.shortcut

/** An activity that can take the forwarded gesture. */
data class AssistantCandidate(
    val packageName: String,
    val className: String,
    val label: String,
    val kind: Kind,
) {
    enum class Kind {
        /** Handles `VOICE_COMMAND` (the headset gesture itself, e.g. Google's HandsFreeActivity, Bixby). */
        VOICE_COMMAND,

        /** Handles `VOICE_ASSIST` (the assistant's own entry point, e.g. Gemini). */
        VOICE_ASSIST,
    }

    /** `pkg/cls`, the format stored in [ShortcutAction.OpenAssistant.component]. */
    val component: String get() = "$packageName/$className"

    /** The intent action to send this candidate. */
    val action: String get() = if (kind == Kind.VOICE_COMMAND) ACTION_VOICE_COMMAND else ACTION_VOICE_ASSIST

    companion object {
        const val ACTION_VOICE_COMMAND = "android.intent.action.VOICE_COMMAND"
        const val ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"
    }
}

/**
 * Where "Open my assistant" forwards to. Pure, and the one place that guarantees the loop guard:
 * nothing in [ownPackage] is ever returned, whatever was stored or resolved.
 */
object AssistantTargets {
    /**
     * - The user's pick ([chosen], `pkg/cls`) when it is still installed and not Earshot.
     * - Else the assistant role holder ([roleHolderPackage]): its `VOICE_COMMAND` handler first,
     *   because that is exactly what Android's own chooser would have opened for the headset
     *   (it knows how to take the Bluetooth voice-recognition request), then its `VOICE_ASSIST`.
     * - Else the first other `VOICE_COMMAND` handler, then the first `VOICE_ASSIST` one.
     */
    fun choose(
        chosen: String?,
        candidates: List<AssistantCandidate>,
        roleHolderPackage: String?,
        ownPackage: String,
    ): AssistantCandidate? {
        val usable = candidates.filter { it.packageName != ownPackage && it.packageName.isNotBlank() && it.className.isNotBlank() }
        chosen?.let { c -> usable.firstOrNull { it.component == c }?.let { return it } }
        roleHolderPackage?.takeIf { it != ownPackage }?.let { holder ->
            val own = usable.filter { it.packageName == holder }
            (own.firstOrNull { it.kind == AssistantCandidate.Kind.VOICE_COMMAND } ?: own.firstOrNull())?.let { return it }
        }
        return usable.firstOrNull { it.kind == AssistantCandidate.Kind.VOICE_COMMAND }
            ?: usable.firstOrNull()
    }

    /**
     * Every distinct candidate for the picker, without Earshot: `VOICE_COMMAND` handlers then
     * `VOICE_ASSIST` ones, each component once.
     */
    fun merge(voiceCommand: List<AssistantCandidate>, voiceAssist: List<AssistantCandidate>, ownPackage: String): List<AssistantCandidate> =
        (voiceCommand + voiceAssist)
            .filter { it.packageName != ownPackage }
            .distinctBy { it.component }

    /** The package of a flattened component (`pkg/cls` or `pkg/.cls`), or a bare package; null for blank. */
    fun packageOf(flattened: String?): String? =
        flattened?.trim()?.takeIf { it.isNotEmpty() }?.substringBefore('/')?.takeIf { it.isNotEmpty() }
}
