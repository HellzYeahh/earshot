package dev.earshot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Who started one of Earshot's exported activities, as far as Android vouches for it.
 *
 * Exported entries ([MainActivity], the headset trigger aliases) can be started by any app in the
 * foreground. Some of what they do borrows Earshot's own grants: ringing the find tone, starting a
 * Sleep night (Do Not Disturb access, the end action that powers the headset off) and starting a
 * voice memo (the microphone). Those run only for a caller Android names here.
 *
 * The caller is `Activity.getReferrer()`'s `android-app://` package, which the system records from
 * the starting app's uid. It is the publisher for a launcher shortcut (LauncherApps starts it "as
 * the package") and the creator for a notification's PendingIntent, so both read as Earshot. Two
 * things weaken it, and both count as "unknown":
 *  - `getReferrer()` prefers `Intent.EXTRA_REFERRER` / `EXTRA_REFERRER_NAME`, which the caller
 *    sets itself;
 *  - Android 11+ hides the package of an app Earshot can't see (package visibility), so another
 *    app may arrive as null. The [PRESS_SOURCES] are kept visible by the manifest's `<queries>`,
 *    so a real headset press is never unknown for that reason.
 */
object LaunchOrigin {

    /**
     * The packages that deliver a real press: the platform (`android`: the media key handler for a
     * wired headset's long press, and the pre-14 app chooser), Android's Bluetooth stack (AOSP /
     * Samsung, and the Bluetooth mainline module) and Android 14+'s app chooser. Only these can be
     * [Caller.SYSTEM]. "Preinstalled" alone is far too wide: FLAG_SYSTEM stays on every bundled
     * app after a Play update, so it covers Facebook, Netflix, Spotify, the Microsoft apps on
     * Galaxy phones, every Google app and the adb shell; and the launcher `<queries>` entry makes
     * the launchable ones visible, so Android no longer hides their referrer.
     *
     * The manifest's `<queries>` must name every one (PackageVisibilityManifestTest checks it). If
     * a phone's real press logs another package, add it here and there together.
     */
    val PRESS_SOURCES: Set<String> = setOf(
        "android",
        "com.android.bluetooth",
        "com.google.android.bluetooth",
        "com.android.intentresolver",
    )

    enum class Caller {
        /** Earshot itself: its notifications, its launcher shortcut, its own starts. */
        OWN,
        /**
         * One of the [PRESS_SOURCES] that is really preinstalled: the Bluetooth stack, the media key
         * handler, Android's app chooser. Any other preinstalled app is [OTHER].
         */
        SYSTEM,
        /** Another installed app, preinstalled ones included. */
        OTHER,
        /** No trustworthy answer: no referrer, or the caller supplied one itself. */
        UNKNOWN,
    }

    /** [packageName] is what Android reported (null when unknown); only for the log. */
    data class Origin(val caller: Caller, val packageName: String?)

    /**
     * Pure decision. [referrerPackage]: the system referrer's package; [hasReferrerExtras]: the
     * intent carries `EXTRA_REFERRER` or `EXTRA_REFERRER_NAME` (then the referrer is the caller's
     * own claim); [isSystemPackage] decides [Caller.SYSTEM] ([of] passes [isPressSource], which asks
     * the package manager).
     */
    fun classify(
        referrerPackage: String?,
        hasReferrerExtras: Boolean,
        ownPackage: String,
        isSystemPackage: (String) -> Boolean,
    ): Caller {
        if (hasReferrerExtras) return Caller.UNKNOWN
        val pkg = referrerPackage?.takeIf { it.isNotBlank() } ?: return Caller.UNKNOWN
        return when {
            pkg == ownPackage -> Caller.OWN
            isSystemPackage(pkg) -> Caller.SYSTEM
            else -> Caller.OTHER
        }
    }

    /** Ring the find tone or start a Sleep night from [MainActivity]: only Earshot's own notification or shortcut. */
    fun mayRunAppAction(caller: Caller): Boolean = caller == Caller.OWN

    /**
     * Start a voice memo from the headset trigger: a press comes from Android's Bluetooth stack
     * (HFP voice recognition), the system (a wired headset's long press) or Android's app chooser
     * in between, never from another app, preinstalled or not.
     */
    fun mayStartVoiceMemo(caller: Caller): Boolean = caller == Caller.OWN || caller == Caller.SYSTEM

    /**
     * The `isSystemPackage` rule [of] passes to [classify]: [pkg] is one of the [PRESS_SOURCES] and
     * [isPreinstalled] (FLAG_SYSTEM). The flag check stays so that a sideloaded app that took one of
     * those names on a phone that lacks it (a Samsung has no `com.google.android.bluetooth`, an
     * Android 13 phone no `com.android.intentresolver`) is still refused. The package manager is
     * asked only about the [PRESS_SOURCES].
     */
    fun isPressSource(pkg: String, isPreinstalled: (String) -> Boolean): Boolean =
        pkg in PRESS_SOURCES && isPreinstalled(pkg)

    /**
     * Who started [activity] with [intent]. Call it while [intent] is the activity's current one
     * (in `onCreate`, or in `onNewIntent` after `setIntent`): `getReferrer()` reads that intent's
     * extras, and during `onNewIntent` Android reports the new start's caller.
     */
    fun of(activity: Activity, intent: Intent?): Origin {
        val claimed = hasReferrerExtras(intent) || (activity.intent !== intent && hasReferrerExtras(activity.intent))
        val pkg = if (claimed) null else runCatching { activity.referrer?.takeIf { it.scheme == "android-app" }?.host }.getOrNull()
        val caller = classify(pkg, claimed, activity.packageName) { name -> isPressSource(name) { isPreinstalled(activity, it) } }
        return Origin(caller, pkg)
    }

    /** An unreadable bundle (an unknown parcelable on older Android) counts as a claim: unknown. */
    private fun hasReferrerExtras(intent: Intent?): Boolean = intent != null &&
        runCatching { intent.hasExtra(Intent.EXTRA_REFERRER) || intent.hasExtra(Intent.EXTRA_REFERRER_NAME) }.getOrDefault(true)

    /**
     * A preinstalled app (updated or not) or the platform; only asked about the [PRESS_SOURCES],
     * which the manifest's `<queries>` keeps visible, so the lookup works.
     */
    private fun isPreinstalled(context: Context, pkg: String): Boolean = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(pkg, 0)
        }
        info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    } catch (_: Exception) {
        false // NameNotFoundException, or anything odd: not vouched for
    }
}
