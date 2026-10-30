package dev.earshot

import dev.earshot.LaunchOrigin.Caller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The exported entries' caller rule (audit R12, R17/R33/R37): only Earshot's own intents ring
 * the find tone or start a Sleep night, and only Android's Bluetooth stack, the system or Android's
 * app chooser ([LaunchOrigin.PRESS_SOURCES]) starts a voice memo. Another app (preinstalled or
 * not), an unknown caller or a self-supplied referrer gets none of them.
 */
class LaunchOriginTest {
    private val own = "dev.earshot"

    /**
     * What FLAG_SYSTEM says on a Galaxy: the press sources, plus bundled third-party apps that
     * keep the flag after a Play update, Google Play services and the adb shell.
     */
    private val preinstalled = LaunchOrigin.PRESS_SOURCES + setOf(
        "com.facebook.katana", "com.netflix.mediaclient", "com.google.android.gms", "com.android.shell",
    )

    /** The rule [LaunchOrigin.of] uses on a phone, over [installed] instead of the package manager. */
    private fun classify(pkg: String?, extras: Boolean = false, ownPackage: String = own, installed: Set<String> = preinstalled) =
        LaunchOrigin.classify(pkg, extras, ownPackage) { name -> LaunchOrigin.isPressSource(name) { it in installed } }

    @Test
    fun `the referrer the system recorded decides who called`() {
        assertEquals(Caller.OWN, classify(own))
        assertEquals(Caller.SYSTEM, classify("com.android.bluetooth")) // a real headset press (AOSP / Samsung)
        assertEquals(Caller.SYSTEM, classify("com.google.android.bluetooth")) // the Bluetooth mainline module
        assertEquals(Caller.SYSTEM, classify("android")) // the media key handler: a wired headset's long press
        assertEquals(Caller.SYSTEM, classify("com.android.intentresolver")) // Android 14+'s app chooser
        assertEquals(Caller.OTHER, classify("com.example.recorder"))
        assertEquals(Caller.UNKNOWN, classify(null)) // no referrer, or one Android hid (package visibility)
        assertEquals(Caller.UNKNOWN, classify(""))
    }

    @Test
    fun `a referrer the caller supplied itself is never believed`() {
        // EXTRA_REFERRER / EXTRA_REFERRER_NAME win in getReferrer(): any app can claim to be Earshot or Bluetooth.
        assertEquals(Caller.UNKNOWN, classify(own, extras = true))
        assertEquals(Caller.UNKNOWN, classify("com.android.bluetooth", extras = true))
        assertEquals(Caller.UNKNOWN, classify(null, extras = true))
    }

    @Test
    fun `the own package is the running build's, so the foss build counts as itself`() {
        assertEquals(Caller.OWN, classify("dev.earshot.foss", ownPackage = "dev.earshot.foss"))
        // The pre-1.0 dev.earshot next to the foss build is just another app.
        assertEquals(Caller.OTHER, classify("dev.earshot", ownPackage = "dev.earshot.foss"))
    }

    @Test
    fun `only Earshot's own notification or shortcut rings or starts a night`() {
        assertTrue(LaunchOrigin.mayRunAppAction(Caller.OWN))
        for (caller in listOf(Caller.SYSTEM, Caller.OTHER, Caller.UNKNOWN)) {
            assertFalse(LaunchOrigin.mayRunAppAction(caller), "$caller")
        }
    }

    @Test
    fun `only a press from the Bluetooth stack or the system starts a voice memo`() {
        assertTrue(LaunchOrigin.mayStartVoiceMemo(Caller.SYSTEM))
        assertTrue(LaunchOrigin.mayStartVoiceMemo(Caller.OWN))
        assertFalse(LaunchOrigin.mayStartVoiceMemo(Caller.OTHER))
        assertFalse(LaunchOrigin.mayStartVoiceMemo(Caller.UNKNOWN))
        // The finding's attack: another app starts the alias with no extras, or claims to be Bluetooth.
        assertFalse(LaunchOrigin.mayStartVoiceMemo(classify("com.example.recorder")))
        assertFalse(LaunchOrigin.mayStartVoiceMemo(classify("com.android.bluetooth", extras = true)))
    }

    @Test
    fun `a preinstalled app that is not a press source is just another app`() {
        // R12 review: FLAG_SYSTEM stays on bundled apps after a Play update, and the launcher
        // <queries> entry makes them visible, so their referrer reaches us. None may record.
        for (pkg in listOf("com.facebook.katana", "com.netflix.mediaclient", "com.google.android.gms", "com.android.shell")) {
            assertTrue(pkg in preinstalled, pkg)
            assertEquals(Caller.OTHER, classify(pkg), pkg)
            assertFalse(LaunchOrigin.mayStartVoiceMemo(classify(pkg)), pkg)
        }
    }

    @Test
    fun `a sideloaded app with a press source's name is not a press source`() {
        // A Samsung has no com.google.android.bluetooth and Android 13 no com.android.intentresolver,
        // so another app can take the name; it is not FLAG_SYSTEM there.
        val samsung13 = setOf("android", "com.android.bluetooth", "com.facebook.katana")
        assertEquals(Caller.OTHER, classify("com.google.android.bluetooth", installed = samsung13))
        assertEquals(Caller.OTHER, classify("com.android.intentresolver", installed = samsung13))
        assertFalse(LaunchOrigin.mayStartVoiceMemo(classify("com.google.android.bluetooth", installed = samsung13)))
        assertEquals(Caller.SYSTEM, classify("com.android.bluetooth", installed = samsung13))
    }

    @Test
    fun `the package manager is asked only about the press sources`() {
        val asked = mutableListOf<String>()
        val probe = { pkg: String -> asked += pkg; true }
        assertFalse(LaunchOrigin.isPressSource("com.facebook.katana", probe))
        assertTrue(LaunchOrigin.isPressSource("com.android.bluetooth", probe))
        assertEquals(listOf("com.android.bluetooth"), asked)
    }

    @Test
    fun `the package manager is asked only about a named, unclaimed caller`() {
        val asked = mutableListOf<String>()
        val probe = { pkg: String -> asked += pkg; false }
        LaunchOrigin.classify(null, false, own, probe)
        LaunchOrigin.classify(own, false, own, probe)
        LaunchOrigin.classify("com.android.bluetooth", true, own, probe)
        LaunchOrigin.classify("com.example.recorder", false, own, probe)
        assertEquals(listOf("com.example.recorder"), asked)
    }
}
