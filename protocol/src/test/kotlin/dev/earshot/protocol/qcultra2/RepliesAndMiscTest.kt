package dev.earshot.protocol.qcultra2

import dev.earshot.protocol.BmapError
import dev.earshot.protocol.BmapException
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.hexToBytes
import dev.earshot.protocol.toHex
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepliesAndMiscTest {
    private val battery = BmapPacket(2, 2, BmapOperator.STATUS, "50ffff00".hexToBytes())
    private val processing = BmapPacket(31, 3, BmapOperator.PROCESSING)
    private val result = BmapPacket(31, 3, BmapOperator.RESULT, byteArrayOf(1))
    private val error = BmapPacket(1, 5, BmapOperator.ERROR, byteArrayOf(5))

    @Test
    fun `pick skips PROCESSING and unrelated STATUS`() {
        assertEquals(result, Replies.pick(listOf(battery, processing, result), QcUltra2.CURRENT_MODE))
        assertNull(Replies.pick(listOf(battery, processing), QcUltra2.CURRENT_MODE))
        assertNull(Replies.pick(emptyList(), QcUltra2.CURRENT_MODE))
        assertTrue(Replies.isProcessing(listOf(processing), QcUltra2.CURRENT_MODE))
        assertEquals(listOf(battery), Replies.unsolicited(listOf(battery, result), QcUltra2.CURRENT_MODE))
    }

    @Test
    fun `pick throws a typed exception on ERROR`() {
        val ex = assertFailsWith<BmapException> { Replies.pick(listOf(battery, error), QcUltra2.CNC) }
        assertEquals(BmapError.OP_NOT_SUPPORTED, ex.error)
        assertTrue(ex.operatorNotSupported)
        assertEquals(QcUltra2.CNC, ex.address)
        // an error on a different address is not ours to raise
        assertEquals(result, Replies.pick(listOf(error, result), QcUltra2.CURRENT_MODE))
        val missing = assertFailsWith<BmapException> { Replies.require(emptyList(), QcUltra2.BATTERY) }
        assertNull(missing.error)
    }

    @Test
    fun `collectModeConfigs keeps 31_6 STATUS frames keyed by slot`() {
        fun slot(index: Int, name: String): BmapPacket {
            val p = ByteArray(48)
            p[0] = index.toByte(); p[3] = if (index > 3) 1 else 0
            name.toByteArray().copyInto(p, 6)
            p[41] = 0x1d; p[45] = 1; p[47] = 1
            return BmapPacket(31, 6, BmapOperator.STATUS, p)
        }
        val frames = listOf(
            BmapPacket(31, 1, BmapOperator.PROCESSING),
            slot(1, "Aware"), battery, slot(0, "Quiet"), slot(5, "None"),
            BmapPacket(31, 6, BmapOperator.STATUS, ByteArray(10)),   // undersized: ignored
            slot(5, "Focus"),                                        // later frame for slot 5 wins
        )
        val configs = Replies.collectModeConfigs(frames)
        assertEquals(listOf(0, 1, 5), configs.keys.toList())
        assertEquals("Focus", configs[5]!!.name)
        assertEquals(true, configs[5]!!.editable)
        assertEquals("Quiet", configs[0]!!.name)
        assertTrue(Replies.collectModeConfigs(listOf(battery)).isEmpty())
        assertFailsWith<BmapException> {
            Replies.collectModeConfigs(listOf(BmapPacket(31, 6, BmapOperator.ERROR, byteArrayOf(8))))
        }
    }

    @Test
    fun `routing payload and reply handling`() {
        assertEquals("82c40f08112233", Routing.startPayload("c4:0f:08:11:22:33").toHex())
        val funcNotSupp = BmapPacket(4, 12, BmapOperator.ERROR, byteArrayOf(4))
        assertTrue(Routing.isTableEmpty(funcNotSupp))
        assertFalse(Routing.canRoute(funcNotSupp))
        assertFalse(Routing.canRoute(null))
        val ok = BmapPacket(4, 12, BmapOperator.RESULT, "00c40f08112233".hexToBytes())
        assertTrue(Routing.canRoute(ok))
        assertEquals("C4:0F:08:11:22:33", Routing.parseReply(ok))
        val status = BmapPacket(4, 12, BmapOperator.STATUS, "0000c40f08112233".hexToBytes())
        assertEquals("C4:0F:08:11:22:33", Routing.parseReply(status))
        assertNull(Routing.parseReply(funcNotSupp))
    }

    @Test
    fun `device info request carries the MAC and the reply name follows 0x03`() {
        val req = DeviceInfoQuery.request("94:45:60:85:05:A7")
        assertEquals("04050106 9445608505a7".replace(" ", ""), req.encode().toHex())
        assertEquals(BmapOperator.GET, req.operator)
        val info = DeviceInfoQuery.parse(("9445608505a7" + "0001" + "03" + "Pixel 9".toByteArray().toHex()).hexToBytes())!!
        assertEquals("94:45:60:85:05:A7", info.mac)
        assertEquals("Pixel 9", info.name)
        assertNull(DeviceInfoQuery.parse("9445608505a7".hexToBytes())!!.name)
    }

    @Test
    fun `mac helpers`() {
        assertEquals("C4:0F:08:AA:BB:CC", Mac.format("c40f08aabbcc".hexToBytes()))
        assertEquals("c40f08aabbcc", Mac.toBytes("C4:0F:08:AA:BB:CC").toHex())
        assertEquals("c40f08aabbcc", Mac.toBytes("c4-0f-08-aa-bb-cc").toHex())
        assertTrue(Mac.isValid("00:11:22:33:44:55"))
        assertFalse(Mac.isValid("00:11:22:33:44"))
        assertTrue(Mac.isZero(ByteArray(6)))
    }

    @Test
    fun `start payloads for power pairing and transport`() {
        assertEquals("00", Power.startPayload(false).toHex())
        assertEquals("01", PairingMode.startPayload(true).toHex())
        assertEquals("03", AudioControl.startPayload(TransportControl.TRACK_FORWARD).toHex())
    }

    @Test
    fun `catalogue constants match the notes`() {
        assertEquals(2, QcUltra2.RFCOMM_CHANNEL)
        assertEquals(0x4082, QcUltra2.PRODUCT_ID)
        assertEquals("wolverine", QcUltra2.CODENAME)
        assertEquals("00001101-0000-1000-8000-00805F9B34FB", QcUltra2.SPP_UUID)
        assertEquals("00000000-deca-fade-deca-deafdecacaff", QcUltra2.BMAP_UUID)
        assertEquals(FunctionAddress(31, 10), QcUltra2.AUDIO_SETTINGS)
        assertEquals("[31.10]", QcUltra2.AUDIO_SETTINGS.toString())
        assertEquals(4..10, QcUltra2.EDITABLE_MODE_SLOTS)
        assertEquals("AudioModes.SettingsConfig [31.10]", FunctionNames.label(QcUltra2.AUDIO_SETTINGS))
        assertEquals("DeviceManagement.Routing [4.12]", FunctionNames.label(QcUltra2.ROUTING))
        // every catalogue address that the notes say answers GET is on the scan list
        for (a in listOf(QcUltra2.BATTERY, QcUltra2.EQ, QcUltra2.CURRENT_MODE, QcUltra2.FAVORITES, QcUltra2.AUDIO_SETTINGS,
            QcUltra2.PAIRED_DEVICES, QcUltra2.AUDIO_SOURCE, QcUltra2.AUTH_PRODUCT_NAME)) {
            assertTrue(FunctionNames.isKnownReadable(a), "$a")
        }
        assertFalse(FunctionNames.isKnownReadable(QcUltra2.MODE_CONFIG))
        assertFalse(FunctionNames.isKnownReadable(QcUltra2.DEVICE_INFO))
        assertEquals(FunctionNames.knownReadableFunctions().size, FunctionNames.knownReadableFunctions().toSet().size)
    }
}
