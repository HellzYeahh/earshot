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
 * Another Earshot next to this build. Two Earshots compete for the same headset control channel
 * (only one app can hold it), so the user is told; the action is the other app's App info page,
 * where it can be removed, which needs no permission.
 *
 * Since 2026-10-30 the app IDs are: Google Play `dev.earshot.foss`, GitHub `dev.earshot.github`,
 * and `dev.earshot` only for the pre-1.0 GitHub builds (signed with the Android debug key, up to
 * the 0.9.1 bridge, [Distribution.LEGACY_MAX_VERSION_CODE]). So there are two cases:
 * [Kind.LEGACY], the old app to move the settings away from, and [Kind.OTHER_EDITION], the other
 * current build ([Distribution.OTHER_EDITION_PACKAGE]), which only gets a neutral note.
 */
object LegacyApp {
    private const val TAG = "Earshot/LegacyApp"

    /** The subject of the certificate in Android's debug keystore (`~/.android/debug.keystore`). */
    private const val DEBUG_SUBJECT_CN = "CN=Android Debug"

    enum class Kind {
        /** A pre-1.0 GitHub build: move the settings over (Back up and restore), then remove it. */
        LEGACY,

        /** The other current edition (Play next to GitHub or the other way round): only one should drive the headset. */
        OTHER_EDITION,
    }

    /** The other Earshot on this phone, or null. The old app wins. Reads the package manager: call it off the main thread. */
    fun find(context: Context): Kind? {
        Distribution.LEGACY_PACKAGE?.takeIf { it != context.packageName }?.let { pkg ->
            info(context, pkg, PackageManager.GET_SIGNING_CERTIFICATES)?.let { info ->
                if (isLegacy(PackageInfoCompat.getLongVersionCode(info), debugSigned(info))) return Kind.LEGACY
            }
        }
        val other = Distribution.OTHER_EDITION_PACKAGE
        if (other != context.packageName && info(context, other, 0) != null) return Kind.OTHER_EDITION
        return null
    }

    /** True only for a real pre-1.0 build, the one the new builds replace. */
    fun isInstalled(context: Context): Boolean = find(context) == Kind.LEGACY

    /** The package [kind] stands for, for the connection report and the App info page. */
    fun packageOf(kind: Kind): String? = when (kind) {
        Kind.LEGACY -> Distribution.LEGACY_PACKAGE
        Kind.OTHER_EDITION -> Distribution.OTHER_EDITION_PACKAGE
    }

    /**
     * A pre-1.0 build has both marks: a version code at or below the bridge's and the debug key.
     * [debugSigned] null (the signer couldn't be read) leaves the version code to decide. A
     * `dev.earshot` without them isn't one of ours: it is ignored.
     */
    internal fun isLegacy(versionCode: Long, debugSigned: Boolean?): Boolean =
        versionCode <= Distribution.LEGACY_MAX_VERSION_CODE && debugSigned != false

    /** Whether an X.500 subject (RFC 2253, as `X500Principal.getName()` gives it) is the Android debug key's. */
    internal fun isDebugSubject(subject: String): Boolean =
        subject.split(',').any { it.trim().equals(DEBUG_SUBJECT_CN, ignoreCase = true) }

    private fun info(context: Context, pkg: String, flags: Int): PackageInfo? = try {
        context.packageManager.getPackageInfo(pkg, flags)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (e: RuntimeException) {
        // A package manager that throws anything else (a dead binder) knows nothing useful.
        Log.w(TAG, "could not read the other Earshot: ${e.javaClass.simpleName}")
        null
    }

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

    /** Opens the App info page of the Earshot [kind] stands for, where the user can uninstall it. */
    fun openAppInfo(context: Context, kind: Kind) {
        val pkg = packageOf(kind) ?: return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "no App info page: ${it.javaClass.simpleName}") }
    }
}
