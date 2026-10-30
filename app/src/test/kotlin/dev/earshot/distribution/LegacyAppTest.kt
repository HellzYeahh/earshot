package dev.earshot.distribution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Since 2026-10-30 `dev.earshot` is only ever the pre-1.0 GitHub app (debug key, up to the 0.9.1
 * bridge); Google Play is `dev.earshot.foss` and GitHub `dev.earshot.github`. A `dev.earshot`
 * without both marks isn't ours and is ignored; the other edition is found by its own package.
 */
class LegacyAppTest {

    @Test fun `the bridge is the last legacy version`() {
        // 0.9.0 is versionCode 10, the debug-signed 0.9.1 bridge 11.
        assertEquals(11L, Distribution.LEGACY_MAX_VERSION_CODE)
    }

    @Test fun `a debug-signed build up to the bridge is the old app`() {
        assertTrue(LegacyApp.isLegacy(versionCode = 1, debugSigned = true))
        assertTrue(LegacyApp.isLegacy(versionCode = 10, debugSigned = true))
        assertTrue(LegacyApp.isLegacy(versionCode = 11, debugSigned = true))
    }

    @Test fun `a newer or store-signed dev_earshot is not the old app`() {
        assertFalse(LegacyApp.isLegacy(versionCode = 12, debugSigned = false))
        assertFalse(LegacyApp.isLegacy(versionCode = 12, debugSigned = true))
        // Not debug-signed is never the old app, whatever its version code says.
        assertFalse(LegacyApp.isLegacy(versionCode = 10, debugSigned = false))
    }

    @Test fun `an unreadable signer leaves the version code to decide`() {
        assertTrue(LegacyApp.isLegacy(versionCode = 11, debugSigned = null))
        assertFalse(LegacyApp.isLegacy(versionCode = 12, debugSigned = null))
    }

    @Test fun `each kind opens the right app`() {
        assertEquals("dev.earshot", LegacyApp.packageOf(LegacyApp.Kind.LEGACY))
        assertEquals(Distribution.OTHER_EDITION_PACKAGE, LegacyApp.packageOf(LegacyApp.Kind.OTHER_EDITION))
        // The other edition is never this build's own ID or the legacy one.
        assertTrue(Distribution.OTHER_EDITION_PACKAGE in setOf("dev.earshot.foss", "dev.earshot.github"))
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
