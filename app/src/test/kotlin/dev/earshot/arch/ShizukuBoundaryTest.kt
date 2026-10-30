package dev.earshot.arch

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Shizuku ships in the GitHub build only (B8, docs/research/play-readiness.md): its library, code
 * and manifest entries are in the foss flavour, and the Google Play build gets stubs that never
 * offer it. A text scan like VendorBoundaryTest, so it runs in both flavours' unit tests:
 *  - shared and play code never names the Shizuku library or Earshot's own Shizuku classes (the
 *    flavours' `ShizukuPrivilegedShell` and `PrivilegedCodecRoute` are the seams);
 *  - every shared string that names Shizuku has a Play wording in src/play/res/values that doesn't.
 */
class ShizukuBoundaryTest {

    // Gradle runs unit tests with the module directory as the working directory.
    private val main = File("src/main")
    private val play = File("src/play")

    /** The library's packages and the classes that live in src/foss (ShizukuPrivilegedShell is the shared seam's name). */
    private val shizukuCode = Regex(
        """\brikka\.shizuku\b|\bmoe\.shizuku\b|\bdev\.earshot\.codec\.shizuku\b""" +
            """|\b(?:ShizukuShell|ShizukuPrivilege|ShizukuCodecAccess|CodecServiceClient|CodecUserService|ICodecService|A2dpAidlCalls|A2dpBinderLocator)\b""",
    )

    private fun isComment(line: String): Boolean = line.trim().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }

    private fun codeFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "aidl") }.toList()

    private fun offenders(dir: File): List<String> = codeFiles(dir).flatMap { file ->
        file.readLines().withIndex()
            .filter { (_, line) -> !isComment(line) && shizukuCode.containsMatchIn(line) }
            .map { (i, line) -> "${file.invariantSeparatorsPath}:${i + 1}: ${line.trim()}" }
    }

    /** name → text of every `<string>` in the `values` files under [res]. */
    private fun strings(res: File): Map<String, String> {
        val values = File(res, "values")
        if (!values.isDirectory) return emptyMap()
        return values.listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { file ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val nodes = root.getElementsByTagName("string")
            (0 until nodes.length).map { (nodes.item(it) as Element).let { e -> e.getAttribute("name") to e.textContent } }
        }.toMap()
    }

    @Test fun `the scan sees the sources`() {
        assertTrue(File(main, "kotlin/dev/earshot/codec/CodecRepositoryImpl.kt").isFile, "run from the app module directory (${File(".").absolutePath})")
        assertTrue(File(play, "kotlin/dev/earshot/codec/PrivilegedCodecRoute.kt").isFile)
    }

    @Test fun `the rule finds Shizuku code and passes the seam`() {
        for (line in listOf(
            "import rikka.shizuku.Shizuku",
            "import dev.earshot.codec.shizuku.CodecServiceClient",
            "ShizukuPrivilege.current(context)",
            "private val shell = ShizukuShell()",
        )) assertTrue(shizukuCode.containsMatchIn(line), line)
        for (line in listOf(
            "dev.earshot.call.ShizukuPrivilegedShell(appContext)",
            "private val privileged: PrivilegedCodecAccess = PrivilegedCodecRoute.create(context)",
        )) assertTrue(!shizukuCode.containsMatchIn(line), line)
    }

    @Test fun `shared code never names Shizuku's library or classes`() {
        val found = offenders(main)
        assertTrue(found.isEmpty(), "move these to src/foss behind PrivilegedCodecAccess / PrivilegedShell:\n" + found.joinToString("\n"))
    }

    @Test fun `the Google Play build never names them either`() {
        val found = offenders(play)
        assertTrue(found.isEmpty(), "the play flavour has no Shizuku:\n" + found.joinToString("\n"))
    }

    @Test fun `every string that names Shizuku has a Play wording without it`() {
        val shared = strings(File(main, "res")).filterValues { it.contains("Shizuku", ignoreCase = true) }
        assertTrue(shared.isNotEmpty(), "no shared string names Shizuku any more: this check has nothing to guard")
        val playStrings = strings(File(play, "res"))
        val missing = shared.keys.filter { it !in playStrings }
        assertTrue(missing.isEmpty(), "add a Play wording to src/play/res/values for $missing")
        val stillNaming = playStrings.filterValues { it.contains("Shizuku", ignoreCase = true) }.keys
        assertTrue(stillNaming.isEmpty(), "the Play wording still names Shizuku: $stillNaming")
    }
}
