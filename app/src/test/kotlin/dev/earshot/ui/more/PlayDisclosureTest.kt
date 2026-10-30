package dev.earshot.ui.more

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tripwires for two Play policy points (docs/research/play-readiness.md B4 and C). Gradle runs
 * unit tests with the module directory as the working directory.
 */
class PlayDisclosureTest {

    @Test fun `the privacy policy link points at the repository's PRIVACY file over https`() {
        assertTrue(PRIVACY_POLICY_URL.startsWith("https://github.com/HellzYeahh/earshot/"), PRIVACY_POLICY_URL)
        assertTrue(PRIVACY_POLICY_URL.endsWith("/PRIVACY.md"), PRIVACY_POLICY_URL)
        // Renaming or moving the file would turn the in-app link into a 404.
        assertTrue(File("../PRIVACY.md").isFile, "PRIVACY.md must stay at the repository root")
    }

    @Test fun `the Wi-Fi trigger asks for location only from a tap`() {
        // Prominent disclosure: the banner's text must be on screen before Android's dialog, and the
        // dialog must follow the user's own tap. A request inside an effect runs on its own.
        val source = File("src/main/kotlin/dev/earshot/ui/more/AutomationEditorScreen.kt").readText()
        val effects = effectBodies(source)
        val offenders = effects.filter { "WifiLocationAsk.PERMISSIONS" in it || "launcher.launch" in it }
        assertTrue(offenders.isEmpty(), "Location is requested from an effect: $offenders")
        assertTrue("launcher.launch(WifiLocationAsk.PERMISSIONS)" in source, "The banner's button no longer asks")
    }

    /** The brace-balanced bodies of every LaunchedEffect / DisposableEffect / SideEffect / LifecycleResumeEffect. */
    private fun effectBodies(source: String): List<String> =
        // A call, not an import line: the name is followed by its arguments or its lambda.
        Regex("""\b(LaunchedEffect|DisposableEffect|SideEffect|LifecycleResumeEffect|LifecycleStartEffect)\s*[({]""")
            .findAll(source)
            .mapNotNull { match ->
                val open = source.indexOf('{', match.range.last)
                if (open < 0) return@mapNotNull null
                var depth = 0
                var i = open
                while (i < source.length) {
                    when (source[i]) {
                        '{' -> depth++
                        '}' -> if (--depth == 0) return@mapNotNull source.substring(open, i + 1)
                    }
                    i++
                }
                null
            }
            .toList()
}
