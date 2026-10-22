package dev.earshot.protocol.soundcore

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A `01 01` body for [model], laid out from its own table (the tests prove reader and table agree). */
internal fun testStateBody(
    model: SoundcoreModel,
    block: ByteArray? = null,
    left: Int = 4,
    right: Int = 5,
    serial: String = model.serialPrefix + "A1B2C3D4E5F6",
    edit: (ByteArray) -> Unit = {},
): ByteArray {
    val layout = checkNotNull(model.layout)
    val b = ByteArray(checkNotNull(layout.stateSize))
    when (model.header) {
        SoundcoreHeader.G2T -> {
            b[1] = 1; b[2] = left.toByte(); b[3] = right.toByte()
            "01.23".toByteArray().copyInto(b, 6); "01.23".toByteArray().copyInto(b, 11)
            serial.toByteArray().copyInto(b, 16)
        }
        SoundcoreHeader.G2H -> {
            b[0] = left.toByte()
            "02.45".toByteArray().copyInto(b, 2)
            serial.toByteArray().copyInto(b, 7)
        }
        else -> error("no test header for ${model.header}")
    }
    layout.lengthByteAt?.let { b[it] = (layout.modeBlockAt - it).toByte() }
    (block ?: ByteArray(layout.codec.size).also { it[1] = 0x31; it[2] = 0; it[3] = 0 }).copyInto(b, layout.modeBlockAt)
    layout.caseBatteryAt?.let { b[it] = 3 }
    layout.caseFirmwareAt?.let { "03.04".toByteArray().copyInto(b, it) }
    edit(b)
    return b
}

class SoundcoreStateTest {

    private fun read(model: SoundcoreModel, body: ByteArray) = assertNotNull(SoundcoreStateReader.read(model, body), "state of ${model.code}")

    // ---- Reading ---------------------------------------------------------------------------

    @Test fun `Liberty 4 NC reads battery, case, firmware, serial and the mode block`() {
        val s = read(SoundcoreModel.LIBERTY_4_NC, testStateBody(SoundcoreModel.LIBERTY_4_NC))
        assertEquals(80, s.leftPercent) // 4 on the 0..5 scale
        assertEquals(100, s.rightPercent)
        assertEquals(60, s.casePercent)
        assertEquals("01.23", s.firmware)
        assertEquals("3947A1B2C3D4E5F6", s.serial)
        assertEquals(AmbientMode.NOISE_CANCELLING, s.ambient)
        assertEquals(3, s.modes?.manualLevel)
        assertEquals(1, s.modes?.adaptiveLevel)
        assertEquals(0, s.modes?.ncType)
        assertEquals(80, s.headlinePercent)
        assertTrue(s.writable)
        assertNull(s.note)
        assertEquals(setOf("touch_tone", "gaming_mode", "leak_compensation", "multipoint", "low_battery_prompt", "auto_off"), s.settings.keys)
    }

    @Test fun `a state of another size keeps the read-only view and says so`() {
        val body = testStateBody(SoundcoreModel.LIBERTY_5).copyOf(160) // firmware 03.11 grew the state in the OSCQ issue
        val s = read(SoundcoreModel.LIBERTY_5, body)
        assertEquals(50, s.leftPercent) // the header and battery still read
        assertNull(s.casePercent, "the case offset is firmware specific too")
        assertFalse(s.writable)
        assertNotNull(s.note)
        assertTrue(s.settings.isEmpty(), "offsets are firmware specific: no setting is read from a state of another size")
        // The length-byte rule still points at the block, so the mode may be shown, never written.
        assertEquals(AmbientMode.NOISE_CANCELLING, s.ambient)
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setAmbient(s, AmbientMode.NORMAL) }
    }

    @Test fun `a serial that is another model's blocks writes`() {
        val body = testStateBody(SoundcoreModel.P40I, serial = "3957A1B2C3D4E5F6") // a Liberty 5's digits on a P40i state
        val s = read(SoundcoreModel.P40I, body)
        assertFalse(s.writable)
        assertEquals(SoundcoreModel.LIBERTY_5, SoundcoreModel.fromSerial(s.serial))
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setWind(s, true) }
    }

    @Test fun `a length byte that points elsewhere blocks writes`() {
        val body = testStateBody(SoundcoreModel.LIBERTY_4_NC) { it[108] = 17 }
        val s = read(SoundcoreModel.LIBERTY_4_NC, body)
        assertFalse(s.writable)
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setAmbient(s, AmbientMode.TRANSPARENCY) }
    }

    @Test fun `junk that is no header reads as nothing`() {
        assertNull(SoundcoreStateReader.read(SoundcoreModel.LIBERTY_4_NC, ByteArray(159) { (it * 7 + 3).toByte() }))
        assertNull(SoundcoreStateReader.read(SoundcoreModel.LIBERTY_4_NC, ByteArray(10)))
        assertNull(SoundcoreStateReader.read(SoundcoreModel.LIBERTY_4_NC, ByteArray(0)))
    }

    @Test fun `a model without a layout still reads battery and firmware`() {
        val b = ByteArray(120)
        b[1] = 1; b[2] = 5; b[3] = 0xFF.toByte()
        "00.99".toByteArray().copyInto(b, 6); "00.99".toByteArray().copyInto(b, 11)
        "3968F49D8AC20334".toByteArray().copyInto(b, 16)
        val s = read(SoundcoreModel.SPORT_X20, b)
        assertEquals(100, s.leftPercent)
        assertNull(s.rightPercent, "255 = bud absent")
        assertEquals("00.99", s.firmware)
        assertNull(s.modes)
        assertFalse(s.writable)
        assertTrue(s.note!!.contains("battery and firmware"))
    }

    @Test fun `the header is found by its content when the table guessed wrong`() {
        // A sleep-header body (firmware at 4, serial at 14) handed to a model that expects the G2T header.
        val b = ByteArray(150)
        b[2] = 4; b[3] = 4
        "01.91".toByteArray().copyInto(b, 4); "01.91".toByteArray().copyInto(b, 9)
        "6611A1B2C3D4E5F6".toByteArray().copyInto(b, 14)
        val s = read(SoundcoreModel.SLEEP_A20, b)
        assertEquals("01.91", s.firmware)
        assertEquals(SoundcoreModel.SLEEP_A20, SoundcoreModel.fromSerial(s.serial))
        val mismatched = read(SoundcoreModel.C30I, b)
        assertEquals("01.91", mismatched.firmware)
        assertFalse(mismatched.writable)
    }

    @Test fun `Space One Pro headphones read one battery and are confirmed by size and serial`() {
        val s = read(SoundcoreModel.SPACE_ONE_PRO, testStateBody(SoundcoreModel.SPACE_ONE_PRO, left = 6))
        assertEquals(70, s.overallPercent) // 6 on the (n+1)*10 scale
        assertNull(s.leftPercent)
        assertEquals(70, s.headlinePercent)
        assertEquals("02.45", s.firmware)
        assertTrue(s.writable)
        assertEquals(setOf("multipoint", "auto_off"), s.settings.keys)
    }

    @Test fun `Space 2 shows its mode and stays read-only`() {
        val block = byteArrayOf(1, 0x3F, 0xFF.toByte(), 0, 0, 1)
        val s = read(SoundcoreModel.SPACE_2, testStateBody(SoundcoreModel.SPACE_2, block))
        assertEquals(AmbientMode.TRANSPARENCY, s.ambient)
        assertEquals(3, s.modes?.manualLevel)
        assertFalse(s.writable)
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setAmbient(s, AmbientMode.NORMAL) }
    }

    @Test fun `Liberty 4 Pro and Liberty 4 are read-only, with the mode shown`() {
        val pro = read(SoundcoreModel.LIBERTY_4_PRO, testStateBody(SoundcoreModel.LIBERTY_4_PRO, byteArrayOf(2, 6, 0, 0), left = 55))
        assertEquals(AmbientMode.NORMAL, pro.ambient)
        assertEquals(55, pro.leftPercent)
        assertFalse(pro.writable)
        // Liberty 4: no documented state size, so only the length rule can place the block.
        val b = ByteArray(150)
        b[1] = 1; b[2] = 3; b[3] = 3
        "01.20".toByteArray().copyInto(b, 6); "01.20".toByteArray().copyInto(b, 11)
        "3953A1B2C3D4E5F6".toByteArray().copyInto(b, 16)
        b[103] = 18
        b[121] = 1
        val l4 = read(SoundcoreModel.LIBERTY_4, b)
        assertEquals(AmbientMode.TRANSPARENCY, l4.ambient)
        assertFalse(l4.writable)
    }

    @Test fun `the TLV generation reads battery, case, firmware and mode and never writes`() {
        fun tlv(tag: Int, vararg value: Int) = byteArrayOf(tag.toByte(), value.size.toByte()) + value.map { it.toByte() }.toByteArray()
        val body = tlv(1, 0) + tlv(2, 1) + tlv(3, 0, 80) + tlv(4, 1, 65) +
            byteArrayOf(5, 5) + "01.02".toByteArray() + byteArrayOf(6, 5) + "01.02".toByteArray() +
            byteArrayOf(7, 17) + "1204A1B2C3D4E5F6".toByteArray() + byteArrayOf(0) +
            tlv(8, 0x80, 90) + tlv(36, 3, 0, 0)
        val s = read(SoundcoreModel.LIBERTY_5_PRO_MAX, body)
        assertEquals(80, s.leftPercent)
        assertEquals(65, s.rightPercent)
        assertEquals(90, s.casePercent)
        assertEquals(true, s.charging)
        assertEquals("01.02", s.firmware)
        assertEquals("1204A1B2C3D4E5F6", s.serial)
        assertEquals(AmbientMode.NOISE_CANCELLING, s.ambient) // 3 = adaptive ANC
        assertFalse(s.writable)
        assertNull(SoundcoreStateReader.read(SoundcoreModel.LIBERTY_5_PRO_MAX, byteArrayOf(9, 9, 9)))
    }

    @Test fun `charging bytes of the earbuds`() {
        val charging = read(SoundcoreModel.LIBERTY_4_NC, testStateBody(SoundcoreModel.LIBERTY_4_NC) { it[5] = 1 })
        assertEquals(true, charging.charging)
        assertEquals(false, read(SoundcoreModel.LIBERTY_4_NC, testStateBody(SoundcoreModel.LIBERTY_4_NC)).charging)
        // R50i NC / A30i put FF FF there: nothing reported.
        val none = read(SoundcoreModel.R50I_NC, testStateBody(SoundcoreModel.R50I_NC) { it[4] = 0xFF.toByte(); it[5] = 0xFF.toByte() })
        assertNull(none.charging)
        assertEquals(true, SoundcoreStateReader.chargingPush(byteArrayOf(0, 1)))
        assertEquals(false, SoundcoreStateReader.chargingPush(byteArrayOf(0, 0)))
        assertNull(SoundcoreStateReader.chargingPush(byteArrayOf()))
    }

    @Test fun `battery pushes use the model's scale`() {
        assertEquals(SoundcoreBatteryPush(40, 100, null, null), SoundcoreStateReader.batteryPush(SoundcoreModel.LIBERTY_4_NC, byteArrayOf(2, 5)))
        assertEquals(SoundcoreBatteryPush(40, 100, 60, null), SoundcoreStateReader.batteryPush(SoundcoreModel.LIBERTY_4_NC, byteArrayOf(2, 5, 3)))
        assertEquals(SoundcoreBatteryPush(null, null, null, 30), SoundcoreStateReader.batteryPush(SoundcoreModel.SPACE_ONE_PRO, byteArrayOf(2)))
        assertNull(SoundcoreStateReader.batteryPush(SoundcoreModel.LIBERTY_4_NC, byteArrayOf(2)))
        assertNull(SoundcoreStateReader.batteryPush(SoundcoreModel.LIBERTY_5_PRO, byteArrayOf(2, 2)))
    }

    @Test fun `settings read as switches and the auto-off option index`() {
        val body = testStateBody(SoundcoreModel.P40I) {
            it[133] = 1 // touch tone
            it[134] = 0 // multipoint
            it[138] = 1; it[139] = 2 // auto-off on, third duration (30 min)
            it[141] = 0x07 // junk: not a switch value
        }
        val s = read(SoundcoreModel.P40I, body)
        assertEquals(1, s.settings["touch_tone"])
        assertEquals(0, s.settings["multipoint"])
        assertEquals(3, s.settings["auto_off"])
        assertNull(s.settings["low_battery_prompt"])
        assertEquals(2, s.autoOffIndex["auto_off"])
        val off = read(SoundcoreModel.P40I, testStateBody(SoundcoreModel.P40I) { it[138] = 0; it[139] = 3 })
        assertEquals(0, off.settings["auto_off"])
        assertEquals(3, off.autoOffIndex["auto_off"])
    }

    // ---- Writing ---------------------------------------------------------------------------

    private fun writable(model: SoundcoreModel, block: ByteArray? = null, edit: (ByteArray) -> Unit = {}): SoundcoreState =
        read(model, testStateBody(model, block, edit = edit)).also { assertTrue(it.writable, model.code) }

    @Test fun `a mode change writes the whole block with only the ambient byte changed`() {
        val block = byteArrayOf(0, 0x31, 1, 0, 0x02, 0x55, 0x03) // Liberty 4 NC: NC, manual 3 / adaptive 1, wind detected, scene bytes
        val s = writable(SoundcoreModel.LIBERTY_4_NC, block)
        val p = SoundcoreWrites.setAmbient(s, AmbientMode.TRANSPARENCY)
        assertEquals(SoundcoreCommands.SET_SOUND_MODES, p.command)
        assertContentEquals(byteArrayOf(1, 0x31, 1, 0, 0x02, 0x55, 0x03), p.body)
        assertContentEquals(byteArrayOf(0x08, 0xEE.toByte(), 0, 0, 0, 0x06, 0x81.toByte()), p.encode().copyOfRange(0, 7))
        // And the guard lets exactly that through.
        assertTrue(SoundcoreWriteGuard.isAllowed(SoundcoreModel.LIBERTY_4_NC, p))
    }

    @Test fun `byte 2 follows the ambient mode only where the table says it repeats it`() {
        val mirror = writable(SoundcoreModel.R50I_NC, byteArrayOf(0, 0x31, 0, 0, 0, 0, 0))
        assertContentEquals(byteArrayOf(2, 0x31, 2, 0, 0, 0, 0), SoundcoreWrites.setAmbient(mirror, AmbientMode.NORMAL).body)
        val pro = writable(SoundcoreModel.SPACE_ONE_PRO, byteArrayOf(0, 0x31, 1, 0, 0, 2)) // byte 2 is a fixed 1
        assertContentEquals(byteArrayOf(2, 0x31, 1, 0, 0, 2), SoundcoreWrites.setAmbient(pro, AmbientMode.NORMAL).body)
    }

    @Test fun `type, level, transparency and wind follow the official app's transitions`() {
        val nc = writable(SoundcoreModel.LIBERTY_5, byteArrayOf(0, 0x32, 0, 0, 0, 0, 0))
        assertContentEquals(byteArrayOf(0, 0x32, 0, 1, 0, 0, 0), SoundcoreWrites.setNcType(nc, 1).body)
        assertContentEquals(byteArrayOf(0, 0x52, 0, 0, 0, 0, 0), SoundcoreWrites.setManualLevel(nc, 5).body)
        assertContentEquals(byteArrayOf(0, 0x32, 0, 0, 1, 0, 0), SoundcoreWrites.setWind(nc, true).body)
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setManualLevel(nc, 6) }
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setNcType(nc, 3) }
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setTransparencyType(nc, 1) } // not in transparency

        val adaptive = writable(SoundcoreModel.LIBERTY_5, byteArrayOf(0, 0x32, 0, 1, 0, 0, 0))
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setManualLevel(adaptive, 2) }

        val transparency = writable(SoundcoreModel.LIBERTY_5, byteArrayOf(1, 0x32, 0, 0, 0, 0, 0))
        assertContentEquals(byteArrayOf(1, 0x32, 1, 0, 0, 0, 0), SoundcoreWrites.setTransparencyType(transparency, 1).body)
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setNcType(transparency, 1) }
        assertEquals(1, SoundcoreWrites.setWind(transparency, true).body[4].toInt())

        val normal = writable(SoundcoreModel.LIBERTY_5, byteArrayOf(2, 0x32, 0, 0, 0, 0, 0))
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setWind(normal, true) }
    }

    @Test fun `the wind switch never touches the detected bit`() {
        val s = writable(SoundcoreModel.P40I, byteArrayOf(0, 0x31, 0, 0, 0x03, 0, 1)) // on and detected
        assertEquals(0x02, SoundcoreWrites.setWind(s, false).body[4].toInt())
        val off = writable(SoundcoreModel.P40I, byteArrayOf(0, 0x31, 0, 0, 0x02, 0, 1)) // detected only
        assertEquals(0x03, SoundcoreWrites.setWind(off, true).body[4].toInt())
    }

    @Test fun `the mode controls map the app's indices to wire values`() {
        val s = writable(SoundcoreModel.SPACE_A40, byteArrayOf(0, 0x21, 1, 0, 0, 0)) // manual level range is 1..3 here
        assertEquals(0x31, SoundcoreWrites.control(s, SoundcoreWrites.NC_LEVEL, 2).body[1].toInt() and 0xFF) // option 2 = level 3
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.control(s, SoundcoreWrites.NC_LEVEL, 3) }
        assertEquals(1, SoundcoreWrites.control(s, SoundcoreWrites.NC_TYPE, 1).body[3].toInt())
    }

    @Test fun `settings write one byte, auto power-off writes enabled and index`() {
        val s = writable(SoundcoreModel.LIBERTY_4_NC) { it[154] = 0; it[155] = 2 }
        val tone = SoundcoreWrites.setSetting(s, SoundcoreSettings.TOUCH_TONE, 1)
        assertEquals(SoundcoreCommands.SET_TOUCH_TONE, tone.command)
        assertContentEquals(byteArrayOf(1), tone.body)
        assertEquals(SoundcoreCommands.SET_MULTIPOINT, SoundcoreWrites.setSetting(s, SoundcoreSettings.MULTIPOINT, 0).command)
        assertEquals(SoundcoreCommands.SET_GAMING_MODE_10, SoundcoreWrites.setSetting(s, SoundcoreSettings.GAMING_MODE, 1).command)
        // Auto-off: option 3 = third duration = index 2; "off" keeps the index the earbuds hold.
        assertContentEquals(byteArrayOf(1, 2), SoundcoreWrites.setSetting(s, SoundcoreSettings.AUTO_OFF, 3).body)
        assertContentEquals(byteArrayOf(0, 2), SoundcoreWrites.setSetting(s, SoundcoreSettings.AUTO_OFF, 0).body)
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setSetting(s, SoundcoreSettings.AUTO_OFF, 5) }
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setSetting(s, "power_off", 1) }
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setSetting(s, SoundcoreSettings.SURROUND, 1) } // the A30i's, not this model's
        for (p in listOf(tone, SoundcoreWrites.setSetting(s, SoundcoreSettings.AUTO_OFF, 3))) assertTrue(SoundcoreWriteGuard.isAllowed(SoundcoreModel.LIBERTY_4_NC, p))
    }

    @Test fun `every writable model builds packets its own guard accepts`() {
        for (model in SoundcoreModel.entries.filter { it.canWrite }) {
            val layout = checkNotNull(model.layout)
            val s = writable(model)
            for (mode in AmbientMode.entries) assertTrue(SoundcoreWriteGuard.isAllowed(model, SoundcoreWrites.setAmbient(s, mode)), "${model.code} $mode")
            for (setting in layout.settings) {
                assertTrue(SoundcoreWriteGuard.isAllowed(model, SoundcoreWrites.setSetting(s, setting.id, 1)), "${model.code} ${setting.id}")
            }
        }
    }

    @Test fun `a read-only state refuses every change with a sentence`() {
        val s = read(SoundcoreModel.LIBERTY_4_PRO, testStateBody(SoundcoreModel.LIBERTY_4_PRO, byteArrayOf(0, 6, 0, 0)))
        val e = assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setAmbient(s, AmbientMode.NORMAL) }
        assertTrue(e.message!!.isNotBlank())
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setSetting(s, SoundcoreSettings.TOUCH_TONE, 1) }
    }

    @Test fun `applied updates the mode block the way the earbuds will report it`() {
        val s = writable(SoundcoreModel.P31I)
        val p = SoundcoreWrites.setAmbient(s, AmbientMode.NORMAL)
        assertEquals(AmbientMode.NORMAL, SoundcoreWrites.applied(s, p).ambient)
        assertEquals(s, SoundcoreWrites.applied(s, SoundcorePacket(SoundcoreCommands.SET_TOUCH_TONE, byteArrayOf(1))))
    }

    // ---- The guard -------------------------------------------------------------------------

    @Test fun `the per-model allow-list takes id and body shape, and the deny-list wins`() {
        val m = SoundcoreModel.LIBERTY_4_NC
        assertTrue(SoundcoreWriteGuard.isAllowed(m, SoundcorePacket(SoundcoreCommands.REQUEST_STATE)))
        assertTrue(SoundcoreWriteGuard.isAllowed(m, SoundcorePacket(SoundcoreCommands.REQUEST_INFO)))
        assertTrue(SoundcoreWriteGuard.isAllowed(m, SoundcorePacket(0x0681, ByteArray(7))))
        assertFalse(SoundcoreWriteGuard.isAllowed(m, SoundcorePacket(0x0681, ByteArray(6))), "a block of the wrong size")
        assertFalse(SoundcoreWriteGuard.isAllowed(m, SoundcorePacket(0x0681)))
        assertFalse(SoundcoreWriteGuard.isAllowed(m, SoundcorePacket(0x0183, byteArrayOf(2))), "a switch takes 0 or 1")
        assertFalse(SoundcoreWriteGuard.isAllowed(m, SoundcorePacket(0x0183, byteArrayOf(1, 1))))
        assertFalse(SoundcoreWriteGuard.isAllowed(m, SoundcorePacket(0x0186, byteArrayOf(1, 9))), "an auto-off index the model lacks")
        assertTrue(SoundcoreWriteGuard.isAllowed(m, SoundcorePacket(0x0186, byteArrayOf(1, 3))))
        // Not this model's: the A30i's surround, the Sleep A30's ids, the R50i NC's gaming id.
        for (other in listOf(0x0286, 0x1094, 0x0687, 0x0187, 0x01A9)) assertFalse(SoundcoreWriteGuard.isAllowed(m, SoundcorePacket(other, byteArrayOf(1))), SoundcorePacket.commandName(other))
        // Session init only where the table asks for it.
        assertFalse(SoundcoreWriteGuard.isAllowed(m, SoundcorePacket(SoundcoreCommands.SESSION_INFO)))
        assertFalse(SoundcoreWriteGuard.isAllowed(m, SoundcorePacket(SoundcoreCommands.SESSION_INIT)))
        assertTrue(SoundcoreWriteGuard.isAllowed(SoundcoreModel.LIBERTY_5, SoundcorePacket(SoundcoreCommands.SESSION_INFO)))
        assertTrue(SoundcoreWriteGuard.isAllowed(SoundcoreModel.LIBERTY_5, SoundcorePacket(SoundcoreCommands.SESSION_INIT)))
        assertFalse(SoundcoreWriteGuard.isAllowed(SoundcoreModel.LIBERTY_5, SoundcorePacket(SoundcoreCommands.SESSION_INIT, byteArrayOf(1))))
    }

    @Test fun `unpair, pairing, hosts, power off, find, fit test, resets and firmware never leave the phone, on any model`() {
        val refused = listOf(
            0x0B83, 0x0B85, 0x0B81, 0x0B82, 0x0185, 0x0189, 0x1089, 0x0482, 0x1482, 0x0901,
            // Unknown write ids, including the families where a firmware update or a factory reset might hide.
            0x0381, 0x0386, 0x0387, 0x0002, 0x0580, 0x1881, 0x18AA, 0xFF81, 0x0B80,
        )
        for (model in SoundcoreModel.entries) {
            for (command in refused) {
                for (body in listOf(ByteArray(0), byteArrayOf(1), byteArrayOf(1, 0), ByteArray(7), ByteArray(20))) {
                    assertFalse(SoundcoreWriteGuard.isAllowed(model, SoundcorePacket(command, body)), "${model.code} ${SoundcorePacket.commandName(command)}")
                }
            }
        }
        for (command in SoundcoreWriteGuard.NEVER) assertFalse(SoundcoreWriteGuard.isAllowed(SoundcoreModel.LIBERTY_5, SoundcorePacket(command)))
    }

    @Test fun `read-only models are allowed their reads and nothing else`() {
        for (model in SoundcoreModel.entries.filter { !it.canWrite && it != SoundcoreModel.SLEEP_A30 }) {
            assertTrue(SoundcoreWriteGuard.isAllowed(model, SoundcorePacket(SoundcoreCommands.REQUEST_STATE)), model.code)
            assertFalse(SoundcoreWriteGuard.isAllowed(model, SoundcorePacket(SoundcoreCommands.SET_SOUND_MODES, ByteArray(6))), model.code)
            assertFalse(SoundcoreWriteGuard.isAllowed(model, SoundcorePacket(SoundcoreCommands.SET_TOUCH_TONE, byteArrayOf(1))), model.code)
            assertFalse(SoundcoreWriteGuard.isAllowed(model, SoundcorePacket(SoundcoreCommands.SESSION_INIT)), model.code)
            assertFalse(SoundcoreWriteGuard.isAllowed(model, SoundcorePacket(SoundcoreCommands.SESSION_INFO)), model.code)
        }
    }

    @Test fun `the Sleep A30 and a model-less connection keep the original list exactly`() {
        for (model in listOf(null, SoundcoreModel.SLEEP_A30)) {
            assertTrue(SoundcoreWriteGuard.isAllowed(model, SoundcorePacket(SoundcoreCommands.SET_ANC_D1301, byteArrayOf(1))))
            assertTrue(SoundcoreWriteGuard.isAllowed(model, SoundcorePacket(SoundcoreCommands.REQUEST_STATE)))
            assertFalse(SoundcoreWriteGuard.isAllowed(model, SoundcorePacket(0x0681, byteArrayOf(1))))
            assertFalse(SoundcoreWriteGuard.isAllowed(model, SoundcorePacket(0x0B84, byteArrayOf(1))))
            assertFalse(SoundcoreWriteGuard.isAllowed(model, SoundcorePacket(0x0189)))
        }
        // The original allow-list is untouched: 4 reads, 11 Sleep A30 writes, the two session-init ids.
        assertEquals(17, SoundcoreWriteGuard.ALLOWED.size)
    }
}
