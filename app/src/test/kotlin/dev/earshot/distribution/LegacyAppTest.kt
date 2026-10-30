package dev.earshot.distribution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `dev.earshot` next to the GitHub build is either the pre-1.0 app (debug key, up to the 0.9.1
 * bridge) or the current Google Play build; only the first may be called "the older Earshot".
 */
class LegacyAppTest {

    @Test fun `the bridge is the last legacy version`() {
        // 0.9.0 is versionCode 10, the debug-signed 0.9.1 bridge 11.
        assertEquals(11L, Distribution.LEGACY_MAX_VERSION_CODE)
    }

    @Test fun `a debug-signed build up to the bridge is the old app`() {
        assertEquals(LegacyApp.Kind.LEGACY, LegacyApp.classify(versionCode = 1, debugSigned = true))
        assertEquals(LegacyApp.Kind.LEGACY, LegacyApp.classify(versionCode = 10, debugSigned = true))
        assertEquals(LegacyApp.Kind.LEGACY, LegacyApp.classify(versionCode = 11, debugSigned = true))
    }

    @Test fun `a newer or store-signed build is the Play build, not the old app`() {
        // The Play release: Play's signing key and a 1.0 version code.
        assertEquals(LegacyApp.Kind.PLAY_BUILD, LegacyApp.classify(versionCode = 12, debugSigned = false))
        // A developer's own playDebug of 1.0: debug key, but newer than any old release.
        assertEquals(LegacyApp.Kind.PLAY_BUILD, LegacyApp.classify(versionCode = 12, debugSigned = true))
        // Not debug-signed is never the old app, whatever its version code says.
        assertEquals(LegacyApp.Kind.PLAY_BUILD, LegacyApp.classify(versionCode = 10, debugSigned = false))
    }

    @Test fun `an unreadable signer leaves the version code to decide`() {
        assertEquals(LegacyApp.Kind.LEGACY, LegacyApp.classify(versionCode = 11, debugSigned = null))
        assertEquals(LegacyApp.Kind.PLAY_BUILD, LegacyApp.classify(versionCode = 12, debugSigned = null))
    }

    @Test fun `the Android debug certificate is recognised by its subject`() {
        // X500Principal.getName() (RFC 2253) of ~/.android/debug.keystore, in either RDN order.
        assertTrue(LegacyApp.isDebugSubject("CN=Android Debug,O=Android,C=US"))
        assertTrue(LegacyApp.isDebugSubject("C=US,O=Android,CN=Android Debug"))
        assertTrue(LegacyApp.isDebugSubject("C=US, O=Android, CN=Android Debug"))
        assertFalse(LegacyApp.isDebugSubject("CN=Android,O=Google Inc.,L=Mountain View,ST=California,C=US"))
        assertFalse(LegacyApp.isDebugSubject("CN=Android Debugger,O=Android,C=US"))
        assertFalse(LegacyApp.isDebugSubject("CN=Earshot,O=Earshot"))
        assertFalse(LegacyApp.isDebugSubject(""))
    }
}
