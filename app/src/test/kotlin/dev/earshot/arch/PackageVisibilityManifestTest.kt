package dev.earshot.arch

import dev.earshot.LaunchOrigin
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Package visibility without `QUERY_ALL_PACKAGES` (audit R00/R01/R02, roadmap F5): Google Play
 * refuses the broad permission for Earshot's uses, so every app the code resolves or looks up
 * needs its own `<queries>` entry, or Android 11+ quietly hides it (the app picker comes back
 * empty, a real headset press reads as an unknown caller).
 * Reads the main manifest, which both flavours merge. Shizuku is the GitHub build's alone (B8):
 * its entries are checked against app/src/foss/AndroidManifest.xml by the foss-only
 * ShizukuManifestTest, and kept out of this one here.
 */
class PackageVisibilityManifestTest {

    // Gradle runs unit tests with the module directory as the working directory.
    private val manifest = File("src/main/AndroidManifest.xml")
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private val root: Element by lazy {
        assertTrue(manifest.isFile, "run from the app module directory (${File(".").absolutePath})")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest).documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.name(): String = getAttributeNS(androidNs, "name")

    private fun queries(): List<Element> = root.children("queries")

    /** Each `<intent>` in `<queries>` as (action, categories, data "scheme|mimeType"). */
    private fun queryIntents(): List<Triple<String, Set<String>, Set<String>>> = queries().flatMap { it.children("intent") }.map { intent ->
        Triple(
            intent.children("action").single().name(),
            intent.children("category").map { it.name() }.toSet(),
            intent.children("data").map { "${it.getAttributeNS(androidNs, "scheme")}|${it.getAttributeNS(androidNs, "mimeType")}" }.toSet(),
        )
    }

    private fun queriedPackages(): Set<String> = queries().flatMap { it.children("package") }.map { it.name() }.toSet()

    @Test
    fun `QUERY_ALL_PACKAGES is not requested`() {
        val permissions = root.children("uses-permission").map { it.name() }
        assertTrue(permissions.isNotEmpty(), "the manifest parsed without permissions")
        assertFalse("android.permission.QUERY_ALL_PACKAGES" in permissions)
    }

    @Test
    fun `launchable apps are visible for the app pickers and Launch an app`() {
        assertTrue(
            queryIntents().any { (action, categories, _) ->
                action == "android.intent.action.MAIN" && categories == setOf("android.intent.category.LAUNCHER")
            },
            "audio/InstalledApps and ShortcutEngine.launchApp need the MAIN/LAUNCHER query",
        )
    }

    @Test
    fun `Shizuku stays out of the manifest both flavours share`() {
        // The Google Play build has no Shizuku (B8, docs/research/play-readiness.md): its permission,
        // provider and package query live in app/src/foss/AndroidManifest.xml only.
        val permissions = root.children("uses-permission").map { it.name() }
        assertFalse(permissions.any { it.startsWith("moe.shizuku") }, "the Shizuku permission belongs in the foss manifest")
        assertFalse(root.children("provider").any { it.name().startsWith("rikka.shizuku") }, "the ShizukuProvider belongs in the foss manifest")
        assertFalse(queriedPackages().any { it.startsWith("moe.shizuku") }, "the Shizuku manager query belongs in the foss manifest")
        // The play flavour must not add them back through a manifest of its own.
        val playManifest = File("src/play/AndroidManifest.xml")
        if (playManifest.isFile) assertFalse("shizuku" in playManifest.readText().lowercase(), "the Google Play build has no Shizuku")
    }

    @Test
    fun `the callers of the headset trigger are visible`() {
        // LaunchOrigin: Android hides the referrer of an invisible app, and a hidden Bluetooth
        // stack would make every real press an unknown caller that can't start a voice memo. The
        // allow-list itself is the source, so the manifest and the code can't drift apart.
        val missing = LaunchOrigin.PRESS_SOURCES - queriedPackages()
        assertTrue(missing.isEmpty(), "add <package> entries to <queries> for $missing")
    }

    @Test
    fun `the other resolveActivity checks have a matching query`() {
        val intents = queryIntents()
        val actions = intents.map { it.first }.toSet()
        // ShortcutsRemapper / AssistantDirectory and the TextToSpeech engine.
        assertTrue(actions.containsAll(setOf("android.intent.action.VOICE_COMMAND", "android.intent.action.VOICE_ASSIST", "android.intent.action.TTS_SERVICE")))
        // CodecRepositoryImpl.openDeveloperCodecSettings skips an intent nothing visible resolves.
        assertTrue(actions.containsAll(setOf("android.settings.APPLICATION_DEVELOPMENT_SETTINGS", "android.settings.SETTINGS")))
        // VoiceMemoNotifications: VIEW of a MediaStore content:// URI typed audio/mp4.
        assertEquals(
            1,
            intents.count { (action, _, data) -> action == "android.intent.action.VIEW" && "content|audio/*" in data },
            "the memo notification's tap needs a VIEW content audio/* query",
        )
    }

    @Test
    fun `the headset trigger stays reachable only through its exported aliases`() {
        val application = root.children("application").single()
        val trigger = application.children("activity").single { it.name() == ".shortcut.ShortcutTriggerActivity" }
        assertEquals("false", trigger.getAttributeNS(androidNs, "exported"))
        val aliases = application.children("activity-alias").associateBy { it.name() }
        for (alias in listOf(".shortcut.HeadsetTrigger", ".shortcut.WiredHeadsetTrigger")) {
            val element = aliases[alias] ?: error("$alias missing")
            assertEquals(".shortcut.ShortcutTriggerActivity", element.getAttributeNS(androidNs, "targetActivity"))
            assertEquals("false", element.getAttributeNS(androidNs, "enabled"), "$alias must stay off until a choice needs it")
        }
    }
}
