package dev.earshot.bt

import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals

/** One-line failure text for logs and the connection report; Earshot's own classes print no (obfuscated) name. */
class DescribeForReportTest {

    @Test
    fun `a platform exception keeps its class name`() {
        assertEquals("IOException: read failed, socket might closed", IOException("read failed, socket might closed").describeForReport())
        assertEquals("SecurityException: no message", SecurityException().describeForReport())
    }

    @Test
    fun `Earshot's own exceptions print the message only`() {
        assertEquals("Could not open", RfcommConnectException("Could not open", looksBusy = false).describeForReport())
        assertEquals("silent", UnresponsiveLinkException("silent").describeForReport())
    }
}
