package dev.earshot.protocol.bragi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Lab's Bragi reads: exactly the read-only calls on the allow-list, never a configure, set or handshake call. */
class BragiReadsTest {

    private val writes = setOf(
        BragiServices.TIME_SET, BragiServices.MODE_CONFIGURE, BragiServices.PEQ_CONFIGURE, BragiServices.PEQ_ENABLE,
        BragiServices.PEQ_DISABLE, BragiServices.SIDETONE_CONFIGURE, BragiServices.SPATIAL_MODE_SET, BragiServices.CRUSHER_STATE_SET,
    )
    private val handshake = setOf(
        BragiServices.AUTH_HELLO, BragiServices.AUTH_AUTHENTICATE, BragiServices.AUTH_TRUST_START, BragiServices.AUTH_TRUST_COMMIT,
        BragiServices.AUTH_TRUST_NONCE, BragiServices.AUTH_TRUST_CONFIRM, BragiServices.AUTH_TRUST_ABORT,
    )

    @Test fun `the read list is exactly the allow-listed reads`() {
        assertEquals(BragiServices.READS, BragiReads.ALL.map { it.address }.toSet())
        assertEquals(BragiReads.ALL.size, BragiServices.READS.size, "no duplicates")
        assertTrue(BragiServices.ALLOWED.containsAll(BragiServices.READS))
        for (read in BragiReads.ALL) assertTrue(BragiServices.isRead(read.address), read.name)
    }

    @Test fun `no write, handshake or firmware call is a read`() {
        for (a in writes + handshake) assertFalse(BragiServices.isRead(a), "$a")
        assertTrue(BragiReads.ALL.none { it.address.service == BragiServices.AUTHENTICATED_ACCESS })
        assertTrue(BragiReads.ALL.all { it.address.service in BragiServices.ALLOWED_SERVICES })
        // Nothing outside the allow-list is a read, whatever its number (FOTA lives in other services).
        for (service in 0..40) for (method in 0..20) {
            val a = BragiServices.Address(service, method)
            if (BragiServices.isRead(a)) assertTrue(a in BragiServices.ALLOWED && a !in writes && a !in handshake)
        }
    }

    @Test fun `streaming reads go without the subscribe flag`() {
        for (read in BragiReads.ALL.filter { it.streaming }) assertEquals(0, read.body.size, read.name)
        // dev_info is the one read with a body: gender UNSPECIFIED, as the Skullcandy app sends it.
        assertEquals(listOf(0x08, 0x00), BragiReads.find(BragiServices.DEV_INFO)!!.body.map { it.toInt() })
    }

    @Test fun `a Method earbud is never asked about the Crusher bass or spatial audio`() {
        val method = BragiReads.forFeatures(BragiFeatures.METHOD)
        assertTrue(method.none { it.address.service == BragiServices.CRUSHER || it.address.service == BragiServices.SPATIAL_AUDIO })
        assertEquals(12, method.size)
        assertEquals(15, BragiReads.forFeatures(BragiFeatures.CRUSHER).size)
        assertEquals(BragiReads.ALL.map { it.address.service }.toSet(), BragiReads.SERVICE_NAMES.keys)
    }
}
