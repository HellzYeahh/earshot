package dev.earshot.codec

import android.content.Context

/**
 * The Google Play build has no privileged codec route: Shizuku ships in the GitHub build only (B8,
 * docs/research/play-readiness.md). The codec is still read through the companion link, the codec
 * broadcast and the public APIs, and switched through the companion link (Android 13+) or by hand
 * in Developer options.
 */
internal object PrivilegedCodecRoute {
    @Suppress("UNUSED_PARAMETER") // the same signature as the GitHub build's
    fun create(context: Context): PrivilegedCodecAccess = PrivilegedCodecAccess.None
}
