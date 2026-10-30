package dev.earshot.codec

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shizuku is the GitHub build's alone (B8, docs/research/play-readiness.md), so its manifest entries
 * live in app/src/foss/AndroidManifest.xml. Without them the codec panel breaks quietly: no
 * permission means Shizuku refuses Earshot, no provider means its binder never arrives, and no
 * `<queries>` entry means Android 11+ hides the manager app, so [ShizukuPrivilege.isManagerInstalled]
 * always says "not installed". PackageVisibilityManifestTest keeps them out of the shared manifest.
 */
class ShizukuManifestTest {

    // Gradle runs unit tests with the module directory as the working directory.
    private val manifest = File("src/foss/AndroidManifest.xml")
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

    @Test
    fun `the Shizuku permission is requested`() {
        assertTrue("moe.shizuku.manager.permission.API_V23" in root.children("uses-permission").map { it.name() })
    }

    @Test
    fun `the Shizuku manager is visible`() {
        val queried = root.children("queries").flatMap { it.children("package") }.map { it.name() }
        assertTrue(ShizukuPrivilege.MANAGER_PACKAGE in queried, "ShizukuPrivilege.isManagerInstalled would always say no")
    }

    @Test
    fun `the old install lookup is still there next to it`() {
        // tools/release/check_release.py tells the foss flavour (and the 0.9.1 bridge) by this entry.
        val queried = root.children("queries").flatMap { it.children("package") }.map { it.name() }
        assertTrue("dev.earshot" in queried)
    }

    @Test
    fun `the provider hands Shizuku's binder to this app only`() {
        val provider = root.children("provider").single { it.name() == "rikka.shizuku.ShizukuProvider" }
        assertEquals("\${applicationId}.shizuku", provider.getAttributeNS(androidNs, "authorities"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", provider.getAttributeNS(androidNs, "permission"))
        assertEquals("false", provider.getAttributeNS(androidNs, "multiprocess"))
    }
}
