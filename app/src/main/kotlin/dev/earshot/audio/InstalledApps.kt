package dev.earshot.audio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Label + package of apps that show up in the launcher, for the per-app profile picker. */
object InstalledApps {
    data class Entry(val label: String, val packageName: String)

    /**
     * Launchable apps, sorted by label, without ourselves. On Android 11+ they are visible through
     * the manifest's `<queries>` MAIN/LAUNCHER intent (not `QUERY_ALL_PACKAGES`, which Google Play
     * refuses for this); keep that entry in step with the intent below, or the list comes back
     * nearly empty. This is a blocking package-manager call: run it off the main thread.
     */
    fun launchable(context: Context): List<Entry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return resolved.asSequence()
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (pkg, _) -> pkg != context.packageName }
            .distinctBy { (pkg, _) -> pkg }
            .map { (pkg, label) -> Entry(label = label.ifBlank { pkg }, packageName = pkg) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
