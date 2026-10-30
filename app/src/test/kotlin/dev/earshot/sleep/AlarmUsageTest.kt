package dev.earshot.sleep

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wake alarm must stay on USAGE_ALARM: at target 37 Android 17 silences a background
 * service's media playback, and only alarm streams are exempt (with exact-alarm access). A
 * source scan, like VendorBoundaryTest, because AudioAttributes can't be built in a JVM test.
 */
class AlarmUsageTest {

    // Gradle runs unit tests with the module directory as the working directory.
    private val player = File("src/main/kotlin/dev/earshot/sleep/AlarmPlayer.kt")

    private fun code(): List<String> = player.readLines().filterNot { it.trim().let { l -> l.startsWith("*") || l.startsWith("//") || l.startsWith("/*") } }

    @Test fun `the alarm player plays alarm usage only`() {
        assertTrue(player.isFile, "run from the app module directory")
        val lines = code()
        assertTrue(lines.any { "USAGE_ALARM" in it })
        val other = lines.filter { Regex("""USAGE_(?!ALARM\b)\w+""").containsMatchIn(it) }
        assertTrue(other.isEmpty(), "the alarm must never use another usage: $other")
        assertFalse(lines.any { "STREAM_MUSIC" in it }, "no media stream for the alarm")
    }
}
