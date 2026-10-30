package dev.earshot.shortcut

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Package-manager side of the shortcuts: who handles `VOICE_COMMAND` / `VOICE_ASSIST`, who holds
 * the assistant role, and whether Earshot is the default for the headset gesture. Visibility of
 * other apps comes only from the `<queries>` block in the manifest (`VOICE_COMMAND`,
 * `VOICE_ASSIST`; there is no `QUERY_ALL_PACKAGES`).
 */
class AssistantDirectory(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager
    private val ownPackage: String get() = context.packageName

    /** What the headset gesture opens right now. */
    fun handlerStatus(): HandlerStatus {
        val info = resolveDefault(Intent(AssistantCandidate.ACTION_VOICE_COMMAND))
        val activity = info?.activityInfo
        return HandlerStatus.classify(
            resolvedPackage = activity?.packageName,
            resolvedClass = activity?.name,
            resolvedLabel = runCatching { info?.loadLabel(pm)?.toString() }.getOrNull(),
            ownPackage = ownPackage,
        )
    }

    /** Every other app's `VOICE_COMMAND` and `VOICE_ASSIST` activity, for the picker. Blocking: call off the main thread. */
    fun candidates(): List<AssistantCandidate> = AssistantTargets.merge(
        query(AssistantCandidate.ACTION_VOICE_COMMAND, AssistantCandidate.Kind.VOICE_COMMAND),
        query(AssistantCandidate.ACTION_VOICE_ASSIST, AssistantCandidate.Kind.VOICE_ASSIST),
        ownPackage,
    )

    /**
     * The digital-assistant app the user chose in Settings (Gemini / Google, Bixby, …).
     * `RoleManager.getRoleHolders` needs a system permission, so this reads what Settings itself
     * stores (`assistant`, then the voice-interaction service) and falls back to the default
     * `VOICE_ASSIST` handler. Every source is optional; null means "don't know".
     */
    fun assistantRoleHolder(): String? {
        val cr = context.contentResolver
        val fromSettings = listOf("assistant", "voice_interaction_service").firstNotNullOfOrNull { key ->
            // Hidden keys may throw on some builds; that just means "unknown".
            runCatching { Settings.Secure.getString(cr, key) }.getOrNull()?.let(AssistantTargets::packageOf)
        }
        if (fromSettings != null && fromSettings != ownPackage) return fromSettings
        return resolveDefault(Intent(AssistantCandidate.ACTION_VOICE_ASSIST))?.activityInfo?.packageName
            ?.takeIf { it != ownPackage && it != "android" && it != "com.android.intentresolver" }
    }

    /** The target for "Open my assistant" with the user's stored pick ([chosen], `pkg/cls`). */
    fun forwardTarget(chosen: String?): AssistantCandidate? =
        AssistantTargets.choose(chosen, candidates(), assistantRoleHolder(), ownPackage)

    /** A launchable intent for [target]: explicit component, so it can never resolve back to Earshot. */
    fun forwardIntent(target: AssistantCandidate, source: Intent?): Intent? {
        if (target.packageName == ownPackage) return null // belt and braces; AssistantTargets already filters
        return Intent(target.action).apply {
            component = ComponentName(target.packageName, target.className)
            // The Bluetooth stack's extras (if any) belong to the real handler: pass them on.
            source?.extras?.let { runCatching { putExtras(it) } }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun resolveDefault(intent: Intent): ResolveInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }.onFailure { Log.w(TAG, "resolveActivity failed: $it") }.getOrNull()

    private fun query(action: String, kind: AssistantCandidate.Kind): List<AssistantCandidate> {
        val intent = Intent(action)
        val list = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrDefault(emptyList())
        return list.mapNotNull { info ->
            val a = info.activityInfo ?: return@mapNotNull null
            if (!a.exported) return@mapNotNull null
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: a.packageName
            AssistantCandidate(a.packageName, a.name, label, kind)
        }
    }

    private companion object {
        const val TAG = "Earshot/Shortcut"
    }
}
