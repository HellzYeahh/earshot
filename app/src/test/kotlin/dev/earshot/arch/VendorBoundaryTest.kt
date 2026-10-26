package dev.earshot.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The module-split rule as a test (roadmap "Module split", option 2): the vendor-neutral layers
 * never reach into a vendor's stack. Real `:vendor-*` Gradle modules would not enforce it on their
 * own (`:app` depends on every vendor to wire AppContainer, `:protocol` holds every vendor's wire
 * code), so the source is scanned instead, the way BrandNeutralWordingTest scans the strings.
 *
 * Neutral: ui, extras, shortcut, call, core, audio, mic, codec, find. A vendor reference is a
 * vendor protocol package, a Bose `Bmap*` class, a virtual device class, anything in `service`
 * (the drivers and repositories), or a vendor's `bt` link class. Fine: `bt.DeviceFamily` and the
 * other platform classes (the router registry, DeviceFinder, BtTimeline ...) and `virtual.DemoDevice`.
 *
 * [BASELINE] lists the files that still offend; it may only shrink: a file that no longer
 * offends must leave it, a new offender fails. A text scan, so a typealias or reflection can slip
 * past it, and it runs only with `:app:testDebugUnitTest` (no CI before 0.9).
 */
class VendorBoundaryTest {

    // Gradle runs unit tests with the module directory as the working directory.
    private val root = File("src/main/kotlin/dev/earshot")

    private val neutral = listOf("ui", "extras", "shortcut", "call", "core", "audio", "mic", "codec", "find")

    private val vendorReference = Regex(
        """dev\.earshot\.protocol\.(?:(?:qcultra2|bragi|soundcore|sennheiser|sony|gaia|airoha|apple)\b|Bmap\w*)""" +
            """|dev\.earshot\.virtual\.Virtual\w*""" +
            """|dev\.earshot\.service\.\w+""" +
            """|dev\.earshot\.bt\.(?:Bmap|Bragi|Soundcore|Sennheiser|Sony|Race|Airoha|AndroidBragi|BoseLinkFactory|LivenessProbe|WriteGuard)\w*""",
    )

    /** Files (relative to [root], `/`-separated) that still reach a vendor stack. Only ever remove entries. */
    private val BASELINE = emptySet<String>()

    private fun isComment(line: String): Boolean = line.trim().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }

    private fun kotlinFiles(dir: String): List<File> = File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Every neutral file with a vendor reference, with the offending lines. */
    private fun offenders(): Map<String, List<String>> = neutral.flatMap(::kotlinFiles)
        .associate { file ->
            file.relativeTo(root).invariantSeparatorsPath to file.readLines().withIndex()
                .filter { (_, line) -> !isComment(line) && vendorReference.containsMatchIn(line) }
                .map { (i, line) -> "${i + 1}: ${line.trim()}" }
        }
        .filterValues { it.isNotEmpty() }

    @Test fun `the scan sees the sources`() {
        assertTrue(File(root, "core/HeadphoneModels.kt").isFile, "run from the app module directory (${File(".").absolutePath})")
        for (dir in neutral) assertTrue(File(root, dir).isDirectory, "no $dir package any more: update the neutral list")
    }

    @Test fun `the rule tells a vendor reference from a platform one`() {
        for (line in listOf(
            "import dev.earshot.protocol.qcultra2.ButtonId",
            "dev.earshot.protocol.sennheiser.SennheiserModel.fromName(name)",
            "import dev.earshot.protocol.BmapPacket",
            "import dev.earshot.virtual.VirtualQcUltra2",
            "import dev.earshot.service.QcUltra2Protocol",
            "import dev.earshot.bt.BmapConnection",
            "import dev.earshot.bt.SoundcoreConnection",
        )) assertTrue(vendorReference.containsMatchIn(line), line)
        for (line in listOf(
            "import dev.earshot.bt.DeviceFamily",
            "import dev.earshot.bt.DeviceFinder",
            "import dev.earshot.bt.ConnectionManager",
            "import dev.earshot.virtual.DemoDevice",
            "import dev.earshot.dsp.EqPreset",
            "import dev.earshot.core.HeadphoneState",
        )) assertFalse(vendorReference.containsMatchIn(line), line)
    }

    @Test fun `neutral layers reach no vendor stack beyond the baseline`() {
        val fresh = offenders().filterKeys { it !in BASELINE }
        assertTrue(fresh.isEmpty(), "Vendor code in a neutral layer; let the driver supply it instead:\n" +
            fresh.entries.joinToString("\n") { (file, lines) -> "$file\n  " + lines.joinToString("\n  ") })
    }

    @Test fun `the baseline only shrinks`() {
        val cleaned = BASELINE - offenders().keys
        assertEquals(emptySet(), cleaned, "No longer reaching a vendor stack: remove these from BASELINE")
    }

    /**
     * Core types that may still declare `wire` / `fromWire`. Only SpatialAudio: its code is also the
     * id saved in the automation rules (`spatialAudioWire`), and A7 replaces it with a stored id and
     * a migration. Remove it from here then; nothing may be added.
     */
    private val wireCodesAllowed = setOf("SpatialAudio")

    @Test fun `core declares no wire codes`() {
        val wire = Regex("""\b(?:val|var)\s+wire\b|\bfun\s+fromWire\b""")
        // A top-level declaration (no indent) names the type the following lines belong to.
        val declaration = Regex("""^(?:(?:enum|data|sealed|value|abstract|open|internal|private)\s+)*(?:class|interface|object)\s+(\w+)""")
        val offenders = kotlinFiles("core").flatMap { file ->
            var owner: String? = null
            file.readLines().mapIndexedNotNull { i, line ->
                declaration.find(line)?.let { owner = it.groupValues[1] }
                if (isComment(line) || !wire.containsMatchIn(line) || owner in wireCodesAllowed) null
                else "${file.relativeTo(root).invariantSeparatorsPath}:${i + 1} ($owner): ${line.trim()}"
            }
        }
        assertTrue(offenders.isEmpty(), "Wire codes belong to the driver (map through :protocol in its StateMapping):\n" + offenders.joinToString("\n"))
    }
}
