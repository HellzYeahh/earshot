package dev.earshot.service

import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.qcultra2.QcUltra2
import dev.earshot.protocol.toHex
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `[4.5]`/`[4.6]` replies are taken only for the MAC that was asked (audit R28): every one of these
 * GETs has the same address, so a late reply for the previous device would otherwise name the next.
 */
class PairedDeviceReplyMatchTest {

    private val protocol = QcUltra2Protocol()
    private val fake = FakeRequester()
    private val phoneHex = "c40f08112233"
    private val laptopHex = "9445608505a7"

    @Before
    fun silenceLog() {
        ProtocolLog.sink = { _, _, _ -> }
    }

    private fun info(macHex: String, name: String) =
        FakeRequester.statusFrame(QcUltra2.DEVICE_INFO, macHex + "0001" + "03" + name.toByteArray().toHex())

    private fun extendedInfo(macHex: String) =
        FakeRequester.statusFrame(QcUltra2.DEVICE_EXTENDED_INFO, macHex + "07070000" + "0000")

    @Test
    fun `a late reply about another device does not name or describe this one`() = runTest {
        fake.status(QcUltra2.PAIRED_DEVICES, "03 $phoneHex $laptopHex")
        // The phone's [4.5]/[4.6] answer arrives late, while the laptop's request is the one pending.
        fake.on(QcUltra2.DEVICE_INFO, BmapOperator.GET) { listOf(info(phoneHex, "Pixel 9")) }
        fake.on(QcUltra2.DEVICE_EXTENDED_INFO, BmapOperator.GET) { listOf(extendedInfo(phoneHex)) }

        val s = protocol.refresh(fake, HeadphoneState())

        val (phone, laptop) = s.pairedDevices
        assertEquals("Pixel 9", phone.name)
        assertNotNull(phone.profiles)
        assertNull(laptop.name, "the phone's name must not land on the laptop")
        assertNull(laptop.profiles)
        assertEquals(laptopHex, fake.requestsTo(QcUltra2.DEVICE_INFO)[1].payload.toHex(), "the laptop was asked about")
        assertEquals(2, fake.requestsTo(QcUltra2.DEVICE_EXTENDED_INFO).size, "a mismatch is no answer, not a refusal: [4.6] keeps being asked")
    }

    @Test
    fun `a nameless device is asked again on the next refresh`() = runTest {
        fake.status(QcUltra2.PAIRED_DEVICES, "03 $phoneHex $laptopHex")
        fake.on(QcUltra2.DEVICE_INFO, BmapOperator.GET) { listOf(info(phoneHex, "Pixel 9")) }
        val first = protocol.refresh(fake, HeadphoneState())
        assertNull(first.pairedDevices[1].name)

        fake.on(QcUltra2.DEVICE_INFO, BmapOperator.GET) { req -> listOf(info(req.payload.toHex(), if (req.payload.toHex() == phoneHex) "Pixel 9" else "ThinkPad")) }
        val second = protocol.refresh(fake, first)

        assertEquals(listOf("Pixel 9", "ThinkPad"), second.pairedDevices.map { it.name })
        assertEquals(listOf(laptopHex), fake.requestsTo(QcUltra2.DEVICE_INFO).drop(2).map { it.payload.toHex() }, "only the unnamed one")
    }
}
