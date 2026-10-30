package dev.earshot.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The text an Experimental headset's "Send a report" hands to the share menu. */
class SupportReportTest {

    private val prompt = "What works on this headset, and what doesn't? Write it here, above the report:"
    private val report = "Earshot connection report\nApp: 1.0.0 (foss, dev.earshot.foss)\nRecent log:\n"

    @Test fun `the prompt comes first, with room to answer, then the whole report`() {
        val text = SupportReport.text(prompt, report)
        assertTrue(text.startsWith("$prompt\n\n"), text)
        assertTrue(text.contains(report.trimEnd()), "the report is shared whole")
        assertEquals(prompt, text.lineSequence().first())
        // Blank lines between the prompt and the report: the owner writes there.
        assertTrue(text.substringAfter(prompt).substringBefore("Earshot connection report").isBlank())
    }

    @Test fun `the text ends with exactly one newline`() {
        assertTrue(SupportReport.text(prompt, report + "\n\n").endsWith("Recent log:\n"))
        assertTrue(SupportReport.text(prompt, "no newline").endsWith("no newline\n"))
    }
}
