package dev.earshot.bt

import dev.earshot.service.GenericHeadphoneRepository
import dev.earshot.service.QcUltra2Protocol
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Since 0.75 Earshot is a companion for any Bluetooth headset, so the text a user sees must not
 * assume Bose. The maker's app is named at run time ([LinkFactory.competingApp]) and reaches the
 * text through a format argument; these tests keep a hard-coded name from creeping back.
 */
class BrandNeutralWordingTest {

    @Test fun `the vendor-neutral busy text names no vendor`() {
        assertEquals("Close the headphone maker's app, then try again", ConnectionManager.MESSAGE_CHANNEL_BUSY)
        assertFalse(ConnectionManager.MESSAGE_CHANNEL_BUSY.contains("Bose", ignoreCase = true))
    }

    @Test fun `each factory names its own app in the busy text`() {
        val bose = BoseLinkFactory()
        assertEquals("Bose", bose.competingApp)
        assertEquals("Close the Bose app, then try again", bose.busyMessage)

        val bragi = BragiLinkFactory()
        assertEquals("Skullcandy", bragi.competingApp)
        assertTrue("Skullcandy" in bragi.busyMessage)
        assertFalse("Bose" in bragi.busyMessage)
    }

    @Test fun `messages shown for a headset Earshot cannot drive name no vendor`() {
        assertFalse(GenericHeadphoneRepository.UNSUPPORTED.contains("Bose", ignoreCase = true))
        assertFalse(QcUltra2Protocol.LE_AUDIO_ABSENT_MESSAGE.contains("Bose", ignoreCase = true))
    }

    @Test fun `strings do not hard-code the Bose app for every headset`() {
        // Gradle runs unit tests with the module directory as the working directory.
        val strings = File("src/main/res/values/strings.xml").readText()
        val hardCoded = Regex("the Bose app|Bose calls|Bose firmware", RegexOption.IGNORE_CASE)
        val offenders = Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(strings)
            .filter { hardCoded.containsMatchIn(it.groupValues[2]) }
            .map { it.groupValues[1] }
            .toList()
        assertTrue(offenders.isEmpty(), "Name the maker's app through a format argument, not in the text: $offenders")
    }
}
