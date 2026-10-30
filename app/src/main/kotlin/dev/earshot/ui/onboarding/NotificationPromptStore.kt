package dev.earshot.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

/**
 * Remembers that the app asked for POST_NOTIFICATIONS at start, so it asks once per install.
 * It used to be a per-Activity flag that reset on every cold start: once the user had refused
 * twice, Android answered "denied" without a dialog and the "Notifications are off" snackbar
 * came back on every launch, for good. Screens that need notifications ask in their own context.
 *
 * Plain SharedPreferences (one boolean), read off the main thread by the caller. A storage
 * failure counts as "already asked": asking on every launch is the worse outcome.
 */
class NotificationPromptStore(private val prefs: () -> SharedPreferences) {

    constructor(context: Context) : this(prefsOf(context.applicationContext))

    /** True the first time only (and stores that), false ever after. */
    fun claimFirstAsk(): Boolean = try {
        val store = prefs()
        if (store.getBoolean(KEY_ASKED, false)) {
            false
        } else {
            store.edit { putBoolean(KEY_ASKED, true) }
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read or store the notification prompt flag", e)
        false
    }

    /**
     * Gives a claimed ask back, so the next start asks again. For a request that never reached
     * the user: Android cancels a permission request sent while another one is open (empty
     * result, no dialog), and that is not an answer. A storage failure leaves the flag set.
     */
    fun release() {
        try {
            prefs().edit { remove(KEY_ASKED) }
        } catch (e: Exception) {
            Log.w(TAG, "could not clear the notification prompt flag", e)
        }
    }

    private companion object {
        const val FILE = "ui_prompts"
        const val KEY_ASKED = "notifications_asked"
        const val TAG = "Earshot/Onboarding"

        fun prefsOf(app: Context): () -> SharedPreferences = { app.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    }
}
