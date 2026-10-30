package dev.earshot.distribution

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Another Earshot under [Distribution.LEGACY_PACKAGE] next to this build (GitHub build only: the
 * Play build has no such package). Two Earshots compete for the same headset control channel
 * (only one app can hold it), so the user is told; the action is the other app's App info page,
 * where it can be removed, which needs no permission.
 *
 * `dev.earshot` is two different apps: the pre-1.0 GitHub builds (signed with the Android debug
 * key, up to the 0.9.1 bridge, [Distribution.LEGACY_MAX_VERSION_CODE]) and the current Google
 * Play build. Only the first is "the older Earshot" to move away from; telling a Play user to
 * remove their current install (and later their paid unlock) would be wrong, so they get a
 * neutral note instead ([Kind.PLAY_BUILD]).
 */
object LegacyApp {
    private const val TAG = "Earshot/LegacyApp"

    /** The subject of the certificate in Android's debug keystore (`~/.android/debug.keystore`). */
    private const val DEBUG_SUBJECT_CN = "CN=Android Debug"

    enum class Kind {
        /** A pre-1.0 GitHub build: move the settings over (Back up and restore), then remove it. */
        LEGACY,

        /** A current `dev.earshot` (the Google Play build): both work, only one should drive the headset. */
        PLAY_BUILD,
    }

    /** The other Earshot on this phone, or null. Reads the package manager: off the main thread where it can be. */
    fun find(context: Context): Kind? {
        val pkg = Distribution.LEGACY_PACKAGE ?: return null
        if (pkg == context.packageName) return null
        val info = try {
            context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        } catch (e: RuntimeException) {
            // A package manager that throws anything else (a dead binder) knows nothing useful.
            Log.w(TAG, "could not read the other Earshot: ${e.javaClass.simpleName}")
            return null
        }
        return classify(PackageInfoCompat.getLongVersionCode(info), debugSigned(info))
    }

    /** True only for a real pre-1.0 build, the one the GitHub build replaces. */
    fun isInstalled(context: Context): Boolean = find(context) == Kind.LEGACY

    /**
     * A pre-1.0 build has both marks: a version code at or below the bridge's and the debug key.
     * [debugSigned] null (the signer couldn't be read) leaves the version code to decide.
     */
    internal fun classify(versionCode: Long, debugSigned: Boolean?): Kind =
        if (versionCode <= Distribution.LEGACY_MAX_VERSION_CODE && debugSigned != false) Kind.LEGACY else Kind.PLAY_BUILD

    /** Whether an X.500 subject (RFC 2253, as `X500Principal.getName()` gives it) is the Android debug key's. */
    internal fun isDebugSubject(subject: String): Boolean =
        subject.split(',').any { it.trim().equals(DEBUG_SUBJECT_CN, ignoreCase = true) }

    /** True when any signer of [info] is the debug certificate, false when none is, null when they can't be read. */
    private fun debugSigned(info: PackageInfo): Boolean? {
        val signing = info.signingInfo ?: return null
        val signatures = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        if (signatures.isNullOrEmpty()) return null
        return runCatching {
            val factory = CertificateFactory.getInstance("X.509")
            signatures.any { signature ->
                val cert = factory.generateCertificate(signature.toByteArray().inputStream()) as X509Certificate
                isDebugSubject(cert.subjectX500Principal.name)
            }
        }.getOrNull()
    }

    /** Opens the other app's App info page, where the user can uninstall it. */
    fun openAppInfo(context: Context) {
        val pkg = Distribution.LEGACY_PACKAGE ?: return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "no App info page: ${it.javaClass.simpleName}") }
    }
}
