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
 * The six files that offended when this test arrived (the form-factor labels, the Lab export,
 * the auto-off list, the demo presses, the button names) were moved onto the drivers in 0.8, so
 * there is no baseline any more: every offender fails. A text scan, so a typealias or reflection
 * can slip past it, and it runs only with `:app:testDebugUnitTest` (no CI before 0.9).
 */
class VendorBoundaryTest {

    // Gradle runs unit tests with the module directory as the working directory.
    private val root = File("src/main/kotlin/dev/earshot")

    private val neutral = listOf("ui", "extras", "shortcut", "call", "core", "audio", "mic", "codec", "find", "sleep")

    private val vendorReference = Regex(
        """dev\.earshot\.protocol\.(?:(?:qcultra2|bragi|soundcore|sennheiser|sony|gaia|airoha|apple|jbl)\b|Bmap\w*|FunctionAddress\b|DecodeResult\b)""" +
            """|dev\.earshot\.virtual\.Virtual\w*""" +
            """|dev\.earshot\.service\.\w+""" +
            """|dev\.earshot\.bt\.(?:Bmap|Bragi|Soundcore|Sennheiser|Sony|Jbl|Race|Airoha|AndroidBragi|BoseLinkFactory|LivenessProbe|WriteGuard)\w*""" +
            // A wildcard import makes every later use unqualified, so it can't be checked: refused.
            """|import\s+dev\.earshot\.(?:service|virtual|bt|protocol)(?:\.\w+)*\.\*""",
    )

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
            "import dev.earshot.protocol.FunctionAddress",
            "import dev.earshot.protocol.jbl.JblModel",
            "import dev.earshot.bt.JblConnection",
            "import dev.earshot.service.*",
            "import dev.earshot.protocol.qcultra2.*",
            "import dev.earshot.bt.*",
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

    @Test fun `neutral layers reach no vendor stack`() {
        val found = offenders()
        assertTrue(found.isEmpty(), "Vendor code in a neutral layer; let the driver supply it instead:\n" +
            found.entries.joinToString("\n") { (file, lines) -> "$file\n  " + lines.joinToString("\n  ") })
    }

    /**
     * Core types that may still declare `wire` / `fromWire`. Only SpatialAudio: StateMapping still
     * maps the Bose `[31.10]` byte through it (A7 gave the rules their own `storedId`). Remove it
     * from here once the Bose driver maps that byte itself; nothing may be added.
     */
    private val wireCodesAllowed = setOf("SpatialAudio")

    private val wire = Regex("""\b(?:val|var)\s+(?:[\w.]+\.)?wire\w*\b|\bfun\s+(?:[\w.]+\.)?fromWire\w*\b""")

    // A top-level declaration (no indent, annotations allowed) names the type the following lines
    // belong to; any other top-level declaration ends the previous type.
    private val declaration = Regex(
        """^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:enum|data|sealed|value|abstract|open|internal|private|inline)\s+)*(class|interface|object|fun|val|var|typealias)\s+([\w.]+)""",
    )

    @Test fun `the wire check sees extensions and annotated types`() {
        for (line in listOf("val wire: Int", "val Sidetone.wire: Int get() = 0", "fun ButtonAction.Companion.fromWire(c: Int)", "val wireCode = 3")) {
            assertTrue(wire.containsMatchIn(line), line)
        }
        assertEquals("Mode", ownerOf("@Serializable enum class Mode(val id: Int)"))
        assertEquals(null, ownerOf("fun helper() = 1"))
    }

    /** The type a top-level line declares, null for a top-level function or property (it ends the previous type). */
    private fun ownerOf(line: String): String? = declaration.find(line)?.let { m ->
        if (m.groupValues[1] in setOf("class", "interface", "object")) m.groupValues[2] else null
    }

    @Test fun `core declares no wire codes`() {
        val offenders = kotlinFiles("core").flatMap { file ->
            var owner: String? = null
            file.readLines().mapIndexedNotNull { i, line ->
                if (declaration.containsMatchIn(line)) owner = ownerOf(line)
                // An extension (`val SpatialAudio.wire`) belongs to its receiver, wherever it sits.
                val receiver = Regex("""\b(?:val|var|fun)\s+(\w+)\.""").find(line)?.groupValues?.get(1)
                if (isComment(line) || !wire.containsMatchIn(line) || (receiver ?: owner) in wireCodesAllowed) null
                else "${file.relativeTo(root).invariantSeparatorsPath}:${i + 1} ($owner): ${line.trim()}"
            }
        }
        assertTrue(offenders.isEmpty(), "Wire codes belong to the driver (map through :protocol in its StateMapping):\n" + offenders.joinToString("\n"))
    }
}
