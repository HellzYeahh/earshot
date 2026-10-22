package dev.earshot.ui.whatsnew

import android.content.Context
import android.util.Log
import androidx.core.content.edit

/**
 * When to show the "What's new" sheet: once per release, after an update, never on a fresh
 * install (onboarding introduces the app there). The decision is pure ([decide]) so it is
 * unit-tested; [WhatsNewStore] only reads and writes the one remembered value.
 */
object WhatsNew {
    /** The release whose notes the sheet carries. Bump it together with the sheet's text. */
    const val RELEASE = "0.75.0"

    enum class Decision {
        /** Show the sheet; mark it seen once dismissed. */
        SHOW,

        /** Nothing to show; remember [RELEASE] silently (fresh install: the next update compares against it). */
        MARK_SEEN,

        /** Already seen. */
        NOTHING,
    }

    /**
     * [lastSeen] is the release stored after the last sheet (null: never stored, i.e. a fresh
     * install or an update from 0.3.x, which predates the sheet). [freshInstall] tells those apart.
     */
    fun decide(lastSeen: String?, freshInstall: Boolean): Decision = when {
        lastSeen == RELEASE -> Decision.NOTHING
        lastSeen == null && freshInstall -> Decision.MARK_SEEN
        else -> Decision.SHOW
    }
}

/**
 * The remembered release, in plain SharedPreferences (one string, read once at start). Every
 * access is wrapped: a storage failure must never block the app, at worst the sheet doesn't show.
 */
class WhatsNewStore(context: Context) {
    private val appContext = context.applicationContext

    fun decision(): WhatsNew.Decision = try {
        val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        WhatsNew.decide(prefs.getString(KEY_LAST_SEEN, null), isFreshInstall())
    } catch (e: Exception) {
        Log.w(TAG, "What's new: could not read the last seen release", e)
        WhatsNew.Decision.NOTHING
    }

    fun markSeen() {
        try {
            appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY_LAST_SEEN, WhatsNew.RELEASE) }
        } catch (e: Exception) {
            Log.w(TAG, "What's new: could not store the last seen release", e)
        }
    }

    /** Installed and never updated: the package manager's first-install and last-update times match. */
    private fun isFreshInstall(): Boolean {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return info.firstInstallTime == info.lastUpdateTime
    }

    private companion object {
        const val FILE = "whats_new"
        const val KEY_LAST_SEEN = "last_seen_release"
        const val TAG = "Earshot/WhatsNew"
    }
}
