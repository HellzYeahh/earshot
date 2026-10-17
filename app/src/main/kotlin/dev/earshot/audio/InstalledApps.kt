package dev.earshot.audio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Label + package of apps that show up in the launcher, for the per-app profile picker. */
object InstalledApps {
    data class Entry(val label: String, val packageName: String)

    /**
     * Launchable apps, sorted by label, without ourselves. Relies on `QUERY_ALL_PACKAGES`
     * (declared in the manifest) on Android 11+, otherwise the list would only contain apps that
     * declare a `<queries>` match. This is a blocking package-manager call: run it off the main thread.
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
