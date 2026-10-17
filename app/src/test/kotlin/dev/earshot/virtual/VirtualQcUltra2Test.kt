package dev.earshot.virtual

import dev.earshot.protocol.BmapError
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.hexToBytes
import dev.earshot.protocol.qcultra2.DeviceInfoQuery
import dev.earshot.protocol.qcultra2.ModeConfigWrite
import dev.earshot.protocol.qcultra2.ModeConfigs
import dev.earshot.protocol.qcultra2.Notifications
import dev.earshot.protocol.qcultra2.QcUltra2
import dev.earshot.protocol.qcultra2.Routing
import dev.earshot.protocol.toHex
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A scripted session against [VirtualQcUltra2.handle], in the order the app would run it. */
class VirtualQcUltra2Test {

    private var now = 0L
    private val device = VirtualQcUltra2(
        clock = { now },
        batteryDrainIntervalMs = 1_000,
        batteryPushIntervalMs = 500,
        pushDelayMs = 100,
    )

    private fun get(a: FunctionAddress) = device.handle(BmapPacket.get(a)).single()
    private fun setGet(a: FunctionAddress, hex: String) = device.handle(BmapPacket.setGet(a, hex.hexToBytes())).single()
    private fun start(a: FunctionAddress, hex: String = "") = device.handle(BmapPacket.start(a, hex.hexToBytes()))

    private fun BmapPacket.assertError(e: BmapError) {
        assertEquals(BmapOperator.ERROR, operator, "expected ERROR, got $this")
        assertEquals(e, error, "wrong error code in $this")
    }

    /** What the app does right after connecting: [9.2] Enable for the wolverine blocks. */
    @Before
    fun subscribe() {
        device.onSessionStart()
        val reply = setGet(QcUltra2.NOTIFY_BY_FBLOCK, Notifications.byFblockPayload(Notifications.WOLVERINE_BLOCKS).toHex())
        assertEquals("800422b7", reply.payload.toHex())
    }

    @Test
    fun `nothing is pushed before the 9_2 subscription, as on fw 10_12`() {
        device.onSessionStart()
        assertEquals("00000000", get(QcUltra2.NOTIFY_BY_FBLOCK).payload.toHex())
        setGet(QcUltra2.AUDIO_SETTINGS, "0500000001")
        now = 10_000
        assertTrue(device.tick().isEmpty())
        // overwrite with block 2 only, then disable it again
        assertEquals("00000004", setGet(QcUltra2.NOTIFY_BY_FBLOCK, "000004").payload.toHex())
        assertEquals("00000000", setGet(QcUltra2.NOTIFY_BY_FBLOCK, "020004").payload.toHex())
    }

    @Test
    fun `fw 10_12 functions answer with the scan values`() {
        assertEquals("01", get(QcUltra2.LE_AUDIO_SUPPORTED).payload.toHex())
        assertEquals("01", get(QcUltra2.LE_AUDIO_CONTROL).payload.toHex())
        assertEquals("01", get(QcUltra2.DISABLE_CAPTOUCH).payload.toHex())
        assertEquals("00", get(QcUltra2.CHARGER_DETECT).payload.toHex())
        assertEquals("01", get(QcUltra2.OPERATION_MODE).payload.toHex())
        assertEquals("01", get(QcUltra2.MIC_MUTE).payload.toHex())
        assertEquals("1f16", get(QcUltra2.VOLUME).payload.toHex())
        assertEquals("0000020800d2", get(QcUltra2.LATENCY).payload.toHex())
        assertEquals("01ffff", get(QcUltra2.AUDIO_STATUS).payload.toHex())
        assertEquals("1f", get(QcUltra2.AUDIO_CONTROL).payload.toHex())
        assertEquals("0873", get(QcUltra2.CONTROL_GET_ALL).payload.toHex())
        assertEquals("ff83", get(QcUltra2.DATA_COLLECTION_GET_ALL).payload.toHex())
        assertEquals("c40f08112233", get(QcUltra2.APP_ADDRESS).payload.toHex())
        assertEquals("01", get(QcUltra2.AVAILABLE_TO_CONNECT).payload.toHex())
        assertEquals("1.2.0", String(get(QcUltra2.BMAP_VERSION).payload))
        get(QcUltra2.LE_AUDIO_CHECK).assertError(BmapError.OP_NOT_SUPPORTED)
        get(QcUltra2.MODES_RESET).assertError(BmapError.OP_NOT_SUPPORTED)
        get(QcUltra2.ROUTING).assertError(BmapError.FUNC_NOT_SUPPORTED)
        get(FunctionAddress(22, 0)).assertError(BmapError.SECURE_SESSION_REQUIRED)
        get(QcUltra2.MODE_CONFIG).assertError(BmapError.INVALID_DATA)
        assertEquals("Home", ModeConfigs.parse(device.handle(BmapPacket(31, 6, BmapOperator.GET, byteArrayOf(4))).single().payload)!!.name)
    }

    @Test
    fun `new writes - touch, volume, latency, transport, LE audio, reset`() {
        assertEquals("00", setGet(QcUltra2.DISABLE_CAPTOUCH, "00").payload.toHex())
        assertEquals("1f10", setGet(QcUltra2.VOLUME, "10").payload.toHex())
        setGet(QcUltra2.VOLUME, "20").assertError(BmapError.INVALID_DATA)
        assertEquals("0000020800c8", setGet(QcUltra2.LATENCY, "00c8").payload.toHex())
        assertEquals("000002080208", setGet(QcUltra2.LATENCY, "7fff").payload.toHex()) // clamped to max

        val pause = start(QcUltra2.AUDIO_CONTROL, "02").single()
        assertEquals(BmapOperator.RESULT, pause.operator)
        assertEquals("02ffff", get(QcUltra2.AUDIO_STATUS).payload.toHex())
        now += 100
        assertTrue(device.tick().any { it.matches(QcUltra2.AUDIO_STATUS) && it.payload.toHex() == "02ffff" })

        val check = start(QcUltra2.LE_AUDIO_CHECK)
        assertEquals(listOf(BmapOperator.PROCESSING, BmapOperator.RESULT), check.map { it.operator })

        setGet(QcUltra2.MODE_CONFIG, ModeConfigWrite(index = 6, name = "Gym", cncWire = 0, spatialCode = 0, windBlock = true, ancEnabled = true).build().toHex())
        assertEquals("Gym", device.slotName(6))
        assertEquals(BmapOperator.RESULT, start(QcUltra2.MODES_RESET, "06").single().operator)
        assertEquals("None", device.slotName(6))
        start(QcUltra2.MODES_RESET, "01").single().assertError(BmapError.RUNTIME)

        // LE Audio: the reply goes out, then the unit restarts (powered off until the next connect).
        assertEquals("00", setGet(QcUltra2.LE_AUDIO_CONTROL, "00").payload.toHex())
        assertFalse(device.isPoweredOn)
        device.powerOn()
        assertEquals("00", get(QcUltra2.LE_AUDIO_CONTROL).payload.toHex())
    }

    @Test
    fun `button presses and status changes are pushed`() {
        device.pressShortcutButton()
        device.setOnCharger(true)
        device.setMicMuted(false)
        device.swipeVolume(+3)
        val pushed = device.drainDeferred()
        assertEquals("80090e", pushed.single { it.matches(QcUltra2.STATUS_BUTTON) }.payload.toHex())
        assertEquals("01", pushed.single { it.matches(QcUltra2.CHARGER_DETECT) }.payload.toHex())
        assertEquals("00", pushed.single { it.matches(QcUltra2.MIC_MUTE) }.payload.toHex())
        assertEquals("1f19", pushed.single { it.matches(QcUltra2.VOLUME) }.payload.toHex())
    }

    @Test
    fun `print console verbs echo and output answers RESULT`() {
        assertEquals("uart", String(setGet(QcUltra2.PRINT_BIND, "uart".toByteArray().toHex()).payload))
        assertEquals(BmapOperator.RESULT, start(QcUltra2.PRINT_OUTPUT).single().operator)
    }

    @Test
    fun `battery GET answers the headphones record`() {
        val reply = get(QcUltra2.BATTERY)
        assertEquals(BmapOperator.STATUS, reply.operator)
        assertEquals("48ffff00", reply.payload.toHex())
    }

    @Test
    fun `identity reads match the reference unit`() {
        assertEquals("408201", get(QcUltra2.PRODUCT_ID_VARIANT).payload.toHex())
        assertEquals("10.12.12+ga927c9a", String(get(QcUltra2.FIRMWARE_VERSION).payload))
        assertEquals("Bose QC Ultra 2 HP", String(get(QcUltra2.PRODUCT_NAME).payload))
        assertEquals("00" + "Fargo".toByteArray().toHex(), get(QcUltra2.CUSTOM_NAME).payload.toHex())
        assertEquals("68f21f123456", get(QcUltra2.MAC_ADDRESS).payload.toHex())
        assertEquals("41000081020000", get(QcUltra2.VOICE_PROMPTS).payload.toHex())
        assertEquals("80090e00094002", get(QcUltra2.BUTTONS).payload.toHex())
        assertEquals("01020f", get(QcUltra2.SIDETONE).payload.toHex())
        assertEquals("07", get(QcUltra2.MULTIPOINT).payload.toHex())
        assertEquals("0000000001", get(QcUltra2.AUDIO_SETTINGS).payload.toHex()) // Quiet: wire cnc 0, ANC on
        assertEquals("0b0003", get(QcUltra2.CNC).payload.toHex())
    }

    @Test
    fun `EQ SET_GET echoes the full 12-byte form`() {
        assertEquals("f60a0000f60afe01f60afa02", get(QcUltra2.EQ).payload.toHex())
        val echo = setGet(QcUltra2.EQ, "0500")
        assertEquals(BmapOperator.STATUS, echo.operator)
        assertEquals("f60a0500f60afe01f60afa02", echo.payload.toHex())
        setGet(QcUltra2.EQ, "f702")
        assertEquals("f60a0500f60afe01f60af702", get(QcUltra2.EQ).payload.toHex())
    }

    @Test
    fun `sidetone and toggles echo what was written`() {
        assertEquals("00030f", setGet(QcUltra2.SIDETONE, "0003").payload.toHex())
        assertEquals("00", setGet(QcUltra2.AUTO_PLAY_PAUSE, "00").payload.toHex())
        assertEquals("05", setGet(QcUltra2.MULTIPOINT, "00").payload.toHex())
        assertEquals("07", setGet(QcUltra2.MULTIPOINT, "01").payload.toHex())
    }

    @Test
    fun `rename is auth-gated like the real firmware`() {
        setGet(QcUltra2.CUSTOM_NAME, "Desk".toByteArray().toHex()).assertError(BmapError.OP_NOT_SUPPORTED)
        assertEquals("00" + "Fargo".toByteArray().toHex(), get(QcUltra2.CUSTOM_NAME).payload.toHex())
    }

    @Test
    fun `audio settings reject autoCnc and otherwise echo`() {
        setGet(QcUltra2.AUDIO_SETTINGS, "0501000001").assertError(BmapError.RUNTIME)
        assertEquals(0, device.currentMode)
        val echo = setGet(QcUltra2.AUDIO_SETTINGS, "0500000001")
        assertEquals("0500000001", echo.payload.toHex())
        // Diverged from Quiet: current mode becomes 0xFF and the unit pushes it a moment later.
        assertEquals(QcUltra2.NO_SAVED_MODE, device.currentMode)
        assertTrue(device.tick().isEmpty(), "push must not be due yet")
        now += 100
        val pushes = device.tick()
        assertEquals(listOf(VirtualQcUltra2.status(QcUltra2.CURRENT_MODE, byteArrayOf(0xFF.toByte()))), pushes)
    }

    @Test
    fun `mode dump is PROCESSING then eleven 48-byte STATUS frames`() {
        val frames = start(QcUltra2.MODE_CONFIG)
        assertEquals(12, frames.size)
        assertEquals(BmapOperator.PROCESSING, frames[0].operator)
        val slots = frames.drop(1)
        slots.forEachIndexed { i, f ->
            assertEquals(QcUltra2.MODE_CONFIG, f.address)
            assertEquals(BmapOperator.STATUS, f.operator)
            assertEquals(ModeConfigs.STATUS_SIZE, f.payload.size, "slot $i length")
            assertEquals(i, f.payload[0].toInt())
        }
        val names = slots.map { ModeConfigs.parse(it.payload)!!.name }
        assertEquals(listOf("Quiet", "Aware", "Immersion", "Cinema", "Home") + List(6) { "None" }, names)
        val configs = slots.map { ModeConfigs.parse(it.payload)!! }
        assertEquals(false, configs[0].editable)
        assertEquals(true, configs[4].editable)
        assertEquals(true, configs[4].configured)
        assertEquals(false, configs[5].configured)
        assertEquals(10, configs[1].cncWire)   // Aware = most ambient
        assertEquals(2, configs[2].spatialCode) // Immersion = head
        assertEquals(true, configs[4].windBlock)       // byte 46
        assertEquals(1, configs[4].flags45)            // byte 45 is a flag on every user slot
        assertEquals(false, configs[5].windBlock)      // empty slot tail 1d 0a 00 00 01 00 01
        assertEquals(1, configs[5].flags45)
        assertEquals(0x001d, configs[5].unknownWord40)
        assertEquals(0x1d, configs[5].changeableFlags)
    }

    @Test
    fun `GetAll streams the slots plus the block state`() {
        val frames = start(QcUltra2.MODES_GET_ALL)
        assertEquals(BmapOperator.PROCESSING, frames[0].operator)
        assertEquals(QcUltra2.MODES_GET_ALL, frames[0].address)
        assertEquals(11, frames.count { it.matches(QcUltra2.MODE_CONFIG) })
        assertTrue(frames.any { it.matches(QcUltra2.CURRENT_MODE) })
        assertTrue(frames.any { it.matches(QcUltra2.AUDIO_SETTINGS) })
    }

    @Test
    fun `preset write is rejected, slot 5 write is echoed and shows in the dump`() {
        val presetPayload = ModeConfigWrite(5, "Hack").build().also { it[0] = 1 }
        device.handle(BmapPacket.setGet(QcUltra2.MODE_CONFIG, presetPayload)).single().assertError(BmapError.RUNTIME)
        assertEquals("Aware", device.slotName(1))

        val write = ModeConfigWrite(index = 5, name = "Office", cncWire = 3, spatialCode = 1, windBlock = false, ancEnabled = true)
        val echo = device.handle(BmapPacket.setGet(QcUltra2.MODE_CONFIG, write.build())).single()
        assertEquals(BmapOperator.STATUS, echo.operator)
        assertEquals(ModeConfigs.STATUS_SIZE, echo.payload.size)
        val parsed = ModeConfigs.parse(echo.payload)!!
        assertEquals(5, parsed.index)
        assertEquals("Office", parsed.name)
        assertEquals(3, parsed.cncWire)
        assertEquals(1, parsed.spatialCode)
        assertEquals(false, parsed.windBlock)
        assertEquals(true, parsed.editable)
        assertEquals(true, parsed.configured)

        val dump = start(QcUltra2.MODE_CONFIG).drop(1)
        assertEquals("Office", ModeConfigs.parse(dump[5].payload)!!.name)
        assertEquals("None", ModeConfigs.parse(dump[6].payload)!!.name)
    }

    @Test
    fun `wrong-size or out-of-range mode writes answer Length and InvalidData`() {
        device.handle(BmapPacket.setGet(QcUltra2.MODE_CONFIG, ByteArray(10))).single().assertError(BmapError.LENGTH)
        device.handle(BmapPacket.setGet(QcUltra2.MODE_CONFIG, ModeConfigWrite(5, "X").build().also { it[0] = 11 })).single()
            .assertError(BmapError.INVALID_DATA)
    }

    @Test
    fun `favourites read and write`() {
        assertEquals("0b0003", get(QcUltra2.FAVORITES).payload.toHex())
        assertEquals("0b0017", setGet(QcUltra2.FAVORITES, "0b0017").payload.toHex())
        assertEquals("0b0017", get(QcUltra2.FAVORITES).payload.toHex())
    }

    @Test
    fun `mode switch answers RESULT, applies the slot and pushes live settings`() {
        val reply = start(QcUltra2.CURRENT_MODE, "0400").single()
        assertEquals(BmapOperator.RESULT, reply.operator)
        assertEquals("04", reply.payload.toHex())
        assertEquals("04", get(QcUltra2.CURRENT_MODE).payload.toHex())
        assertEquals("0000000101", get(QcUltra2.AUDIO_SETTINGS).payload.toHex()) // Home: cnc 0, wind on, anc on
        now += 100
        assertEquals(listOf(VirtualQcUltra2.status(QcUltra2.AUDIO_SETTINGS, "0000000101".hexToBytes())), device.tick())
        start(QcUltra2.CURRENT_MODE, "0b00").single().assertError(BmapError.INVALID_DATA)
    }

    @Test
    fun `paired devices, names and active source`() {
        assertEquals("03c40f081122339445608505a7", get(QcUltra2.PAIRED_DEVICES).payload.toHex())
        val info = device.handle(DeviceInfoQuery.request("94:45:60:85:05:A7")).single()
        assertEquals("ThinkPad", DeviceInfoQuery.parse(info.payload)!!.name)
        device.handle(DeviceInfoQuery.request("00:11:22:33:44:55")).single().assertError(BmapError.INVALID_DATA)
        assertEquals("000f01c40f08112233", get(QcUltra2.AUDIO_SOURCE).payload.toHex())
    }

    @Test
    fun `routing fails once with an empty table and then succeeds`() {
        val packet = BmapPacket.start(QcUltra2.ROUTING, Routing.startPayload("94:45:60:85:05:A7"))
        val first = device.handle(packet).single()
        assertTrue(Routing.isTableEmpty(first))
        val second = device.handle(packet).single()
        assertEquals(BmapOperator.RESULT, second.operator)
        assertEquals("94:45:60:85:05:A7", Routing.parseReply(second))
        assertEquals("000f019445608505a7", get(QcUltra2.AUDIO_SOURCE).payload.toHex())
        now += 100
        assertTrue(device.tick().any { it.matches(QcUltra2.AUDIO_SOURCE) })
    }

    @Test
    fun `operators and addresses the firmware refuses`() {
        device.handle(BmapPacket(1, 7, BmapOperator.SET, "0500".hexToBytes())).single().assertError(BmapError.OP_NOT_SUPPORTED)
        get(FunctionAddress(1, 99)).assertError(BmapError.FUNC_NOT_SUPPORTED)
        get(FunctionAddress(12, 1)).assertError(BmapError.FBLOCK_NOT_SUPPORTED)
        get(QcUltra2.AUX_CABLE_DETECTION).assertError(BmapError.FUNC_NOT_SUPPORTED)
        setGet(QcUltra2.CNC, "0501").assertError(BmapError.OP_NOT_SUPPORTED)
        setGet(QcUltra2.CURRENT_MODE, "0400").assertError(BmapError.OP_NOT_SUPPORTED)
        start(QcUltra2.MODES_RESET).single().assertError(BmapError.INVALID_DATA)
        // fw 10.12 has no [31.4] DefaultMode at all
        device.handle(BmapPacket.setGet(QcUltra2.DEFAULT_MODE, byteArrayOf(1))).single().assertError(BmapError.FUNC_NOT_SUPPORTED)
    }

    @Test
    fun `pairing and power`() {
        assertEquals("01", start(QcUltra2.PAIRING_MODE, "01").single().payload.toHex())
        assertEquals("01", get(QcUltra2.PAIRING_MODE).payload.toHex())
        val off = start(QcUltra2.POWER, "00").single()
        assertEquals(BmapOperator.RESULT, off.operator)
        assertFalse(device.isPoweredOn)
        assertTrue(device.handle(BmapPacket.get(QcUltra2.BATTERY)).isEmpty(), "nothing answers while off")
        assertTrue(device.tick().isEmpty())
        device.powerOn()
        assertEquals("48ffff00", get(QcUltra2.BATTERY).payload.toHex())
    }

    @Test
    fun `greeting is two valid STATUS frames`() {
        val frames = BmapPacket.decodeAll(device.greeting())
        assertEquals(2, frames.size)
        assertEquals(QcUltra2.BATTERY, frames[0].address)
        assertEquals(QcUltra2.CURRENT_MODE, frames[1].address)
        assertTrue(frames.all { it.operator == BmapOperator.STATUS })
    }

    @Test
    fun `battery drains and is pushed on the timer`() {
        assertTrue(device.tick().isEmpty())
        now = 500
        val push = device.tick().single()
        assertEquals(QcUltra2.BATTERY, push.address)
        assertEquals(72, push.payload[0].toInt())
        now = 2_100
        val later = device.tick().single()
        assertEquals(70, later.payload[0].toInt())
        assertEquals(70, device.batteryPercent)
    }
}
