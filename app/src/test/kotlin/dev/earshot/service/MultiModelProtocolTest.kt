package dev.earshot.service

import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.protocol.BmapError
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.qcultra2.QcUltra2
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [QcUltra2Protocol] on the models added after the QC Ultra 2 (layouts from bosectl). */
class MultiModelProtocolTest {

    private val protocol = QcUltra2Protocol()
    private val fake = FakeRequester()

    @Before
    fun silenceLog() {
        ProtocolLog.sink = { _, _, _ -> }
    }

    /** A 47-byte `[31.6]` STATUS (QC45 / QC Headphones): no ANC byte, wind at 46. */
    private fun compactStatus(index: Int, name: String, cncWire: Int, wind: Boolean = false, editable: Boolean = index >= 2): BmapPacket {
        val p = ByteArray(47)
        p[0] = index.toByte()
        p[3] = if (editable) 1 else 0
        p[4] = 1
        name.toByteArray().copyInto(p, 6)
        p[41] = if (editable) 0x0d else 0x00
        p[42] = cncWire.toByte()
        p[46] = if (wind) 1 else 0
        return BmapPacket(31, 6, BmapOperator.STATUS, p)
    }

    /** A QC45 on custom slot 2 ("Commute", level 7 = wire 3). */
    private fun scriptQc45(currentMode: Int = 2) {
        fake.status(QcUltra2.PRODUCT_ID_VARIANT, "403901")
        fake.status(QcUltra2.CURRENT_MODE, "%02x".format(currentMode))
        fake.on(QcUltra2.MODES_GET_ALL, BmapOperator.START) {
            listOf(
                compactStatus(0, "Quiet", 0),
                compactStatus(1, "Aware", 10),
                compactStatus(2, "Commute", 3),
                compactStatus(3, "None", 0),
            )
        }
        // The 39-byte write echoes as a 39-byte STATUS-less echo; return it as STATUS like the unit.
        fake.echo(QcUltra2.MODE_CONFIG)
    }

    @Test
    fun `QC45 refresh reads 47-byte modes, skips 31_10 and takes noise from the active slot`() = runTest {
        scriptQc45()
        val s = protocol.refresh(fake, HeadphoneState())

        assertEquals("QuietComfort 45 / QC SE", s.info?.modelName)
        assertEquals(4, s.modes.size)
        assertTrue(s.modes[0].isPreset)
        assertFalse(s.modes[0].isEditable)
        assertTrue(s.modes[2].isEditable)
        assertEquals("Commute", s.modes[2].name)
        assertFalse(s.modes[3].isConfigured) // "None" = free slot
        assertEquals(7, s.noise?.cancellingLevel)
        assertTrue(s.noise!!.ancEnabled)
        assertFalse(s.capabilities.ancToggle)
        assertFalse(s.capabilities.spatialAudio)
        assertFalse(s.capabilities.liveNoiseControl)
        assertTrue(fake.requestsTo(QcUltra2.AUDIO_SETTINGS).isEmpty(), "[31.10] does not exist on the QC45")
        assertTrue(fake.requestsTo(QcUltra2.LE_AUDIO_CONTROL).isEmpty())
    }

    @Test
    fun `QC45 noise changes rewrite the active custom slot with the 39-byte write`() = runTest {
        scriptQc45()
        val before = protocol.refresh(fake, HeadphoneState())
        val after = protocol.setNoise(fake, before, NoiseSettings(cancellingLevel = 2, ancEnabled = true, windBlock = false))

        val write = fake.requestsTo(QcUltra2.MODE_CONFIG).single { it.operator == BmapOperator.SET_GET }
        assertEquals(39, write.payload.size)
        assertEquals(2, write.payload[0].toInt())
        assertEquals(8, write.payload[35].toInt()) // level 2 → wire 8
        assertEquals(2, after.noise?.cancellingLevel)
        assertEquals("Commute", after.modes[2].name)
    }

    @Test
    fun `QC45 noise changes on a built-in mode explain what to do`() = runTest {
        scriptQc45(currentMode = 0)
        val before = protocol.refresh(fake, HeadphoneState())
        val e = assertFailsWith<HeadphoneCommandException> {
            protocol.setNoise(fake, before, NoiseSettings(cancellingLevel = 5))
        }
        assertTrue("custom mode" in e.message.orEmpty())
        assertTrue(fake.requestsTo(QcUltra2.MODE_CONFIG).none { it.operator == BmapOperator.SET_GET })
    }

    @Test
    fun `QC45 custom modes save to slots 2-3 and presets 0-1 are refused`() = runTest {
        scriptQc45()
        val before = protocol.refresh(fake, HeadphoneState())
        val saved = protocol.saveCustomMode(fake, before, 3, "Office", NoiseSettings(cancellingLevel = 10))
        assertEquals("Office", saved.modes[3].name)
        assertEquals(39, fake.requestsTo(QcUltra2.MODE_CONFIG).last { it.operator == BmapOperator.SET_GET }.payload.size)

        assertFailsWith<HeadphoneCommandException> { protocol.saveCustomMode(fake, before, 1, "Mine", NoiseSettings()) }
    }

    @Test
    fun `an unknown product id named Ultra Open is treated as the Ultra Open Earbuds`() = runTest {
        fake.status(QcUltra2.PRODUCT_ID_VARIANT, "40aa00")
        fake.status(QcUltra2.PRODUCT_NAME, "426f736520556c747261204f70656e20322045420000") // "Bose Ultra Open 2 EB"
        val s = protocol.refresh(fake, HeadphoneState())

        // 0.4.0: "Ultra Open 2" names the 2nd Gen (aubrey), which has its own entry now.
        assertEquals("Ultra Open Earbuds (2nd Gen)", s.info?.modelName)
        assertEquals("Ultra Open Earbuds", s.info?.family)
        assertEquals("2nd Gen", s.info?.generation)
        assertEquals(FormFactor.OPEN_EARBUDS, s.capabilities.formFactor)
        assertFalse(s.capabilities.noiseCancelling)
        assertFalse(s.capabilities.editableModes)
        assertFalse(s.capabilities.verified)
        assertTrue(s.modes.none { it.isEditable })
        assertFailsWith<HeadphoneCommandException> { protocol.saveCustomMode(fake, s, 4, "Run", NoiseSettings()) }
    }

    @Test
    fun `reads refused with FuncNotSupp are not repeated on the next refresh of the same connection`() = runTest {
        fake.status(QcUltra2.PRODUCT_ID_VARIANT, "408201")
        protocol.onConnected(fake, HeadphoneState())
        val first = protocol.refresh(fake, HeadphoneState())
        protocol.refresh(fake, first)
        // [1.24] is unscripted → FuncNotSupp once, then skipped.
        assertEquals(1, fake.requestsTo(QcUltra2.AUTO_PLAY_PAUSE).size)
        // [0.3] is read once per connection, not per refresh.
        assertEquals(1, fake.requestsTo(QcUltra2.PRODUCT_ID_VARIANT).size)

        // A new connection asks again.
        protocol.onConnected(fake, first)
        protocol.refresh(fake, first)
        assertEquals(2, fake.requestsTo(QcUltra2.AUTO_PLAY_PAUSE).size)
    }

    @Test
    fun `blocks missing from 0_2 are not asked at all`() = runTest {
        fake.status(QcUltra2.PRODUCT_ID_VARIANT, "403901")
        fake.status(QcUltra2.ALL_FUNCTION_BLOCKS, "80000007") // blocks 0, 1, 2 and 31
        protocol.onConnected(fake, HeadphoneState())
        protocol.refresh(fake, HeadphoneState())
        assertTrue(fake.sent.none { it.address.block in setOf(4, 5) }, fake.sent.map { it.address }.toString())
    }

    @Test
    fun `the QC Ultra 2 keeps its 11 slots and live noise control`() = runTest {
        fake.status(QcUltra2.PRODUCT_ID_VARIANT, "408201")
        fake.status(QcUltra2.AUDIO_SETTINGS, "0500000001")
        val s = protocol.refresh(fake, HeadphoneState())
        assertEquals(QcUltra2.MODE_SLOT_COUNT, s.modes.size)
        assertTrue(s.capabilities.liveNoiseControl)
        assertTrue(s.capabilities.ancToggle)
        assertTrue(s.capabilities.listeningModes)
        assertEquals(1, fake.requestsTo(QcUltra2.AUDIO_SETTINGS).size)
    }

    // ── Runtime capabilities: the catalogue is a first guess ─────────────────────────────

    /** A QC Ultra 1st Gen (lonestarr) whose firmware has no [31.10]: 48-byte slots, custom slot 4 active. */
    private fun scriptUltra1WithoutAudioSettings(audioSettingsError: BmapError = BmapError.FUNC_NOT_SUPPORTED) {
        fake.status(QcUltra2.PRODUCT_ID_VARIANT, "406601")
        fake.error(QcUltra2.AUDIO_SETTINGS, audioSettingsError)
        fake.status(QcUltra2.CURRENT_MODE, "04")
        fake.on(QcUltra2.MODE_CONFIG, BmapOperator.START) {
            listOf(
                FakeRequester.modeStatus(0, "Quiet", cncWire = 0, editable = false),
                FakeRequester.modeStatus(1, "Aware", cncWire = 10, editable = false),
                FakeRequester.modeStatus(2, "Immersion", cncWire = 0, spatial = 2, editable = false),
                FakeRequester.modeStatus(3, "None", cncWire = 10, editable = true),
                FakeRequester.modeStatus(4, "Commute", cncWire = 3, wind = false, editable = true),
            )
        }
        fake.echo(QcUltra2.MODE_CONFIG)
    }

    @Test
    fun `a refused 31_10 drops live noise control for the connection and noise edits rewrite the 40-byte slot`() = runTest {
        scriptUltra1WithoutAudioSettings()
        val s = protocol.refresh(fake, HeadphoneState())
        assertEquals("QuietComfort Ultra Headphones", s.info?.modelName)
        assertFalse(s.capabilities.liveNoiseControl)
        assertTrue(s.capabilities.ancToggle, "48-byte slots carry the ANC byte")
        assertEquals(7, s.noise?.cancellingLevel) // the active slot's settings

        val after = protocol.setNoise(fake, s, NoiseSettings(cancellingLevel = 2, ancEnabled = false, windBlock = false))
        assertTrue(fake.requestsTo(QcUltra2.AUDIO_SETTINGS).none { it.operator == BmapOperator.SET_GET })
        val write = fake.requestsTo(QcUltra2.MODE_CONFIG).single { it.operator == BmapOperator.SET_GET }
        assertEquals(40, write.payload.size)
        assertEquals(4, write.payload[0].toInt())
        assertEquals(8, write.payload[35].toInt()) // level 2 → wire 8
        assertEquals(0, write.payload[39].toInt()) // ANC off is honoured on the 40-byte layout
        assertEquals(2, after.noise?.cancellingLevel)
        assertEquals("Commute", after.modes[4].name)

        // [31.10] is not asked again on this connection.
        protocol.refresh(fake, after)
        assertEquals(1, fake.requestsTo(QcUltra2.AUDIO_SETTINGS).size)
    }

    @Test
    fun `a silent 31_10 also falls back to the slot write`() = runTest {
        scriptUltra1WithoutAudioSettings()
        fake.silent(QcUltra2.AUDIO_SETTINGS)
        val s = protocol.refresh(fake, HeadphoneState())
        assertFalse(s.capabilities.liveNoiseControl)
        protocol.setNoise(fake, s, NoiseSettings(cancellingLevel = 5))
        assertEquals(1, fake.requestsTo(QcUltra2.MODE_CONFIG).count { it.operator == BmapOperator.SET_GET })
    }

    @Test
    fun `the 31_6 write follows the 47-byte frames actually seen, not the catalogue`() = runTest {
        // A QC Ultra 1st Gen catalogued as STANDARD that turns out to send prince-style 47-byte slots.
        fake.status(QcUltra2.PRODUCT_ID_VARIANT, "406601")
        fake.status(QcUltra2.CURRENT_MODE, "04")
        fake.on(QcUltra2.MODE_CONFIG, BmapOperator.START) {
            listOf(compactStatus(0, "Quiet", 0, editable = false), compactStatus(4, "Commute", 3, editable = true))
        }
        fake.echo(QcUltra2.MODE_CONFIG)
        val s = protocol.refresh(fake, HeadphoneState())
        assertFalse(s.capabilities.ancToggle)
        assertTrue(s.capabilities.windExcludesCancelling)
        protocol.saveCustomMode(fake, s, 4, "Office", NoiseSettings(cancellingLevel = 10))
        assertEquals(39, fake.requestsTo(QcUltra2.MODE_CONFIG).single { it.operator == BmapOperator.SET_GET }.payload.size)
    }

    /** A unit (bosectl #44's id is goodyear's, 0x4024) that refuses every block-31 request; [1.5] at step 3. */
    private fun scriptRefusedBlock31(productIdHex: String) {
        fake.status(QcUltra2.PRODUCT_ID_VARIANT, productIdHex)
        for (f in listOf(QcUltra2.AUDIO_SETTINGS, QcUltra2.MODES_CAPABILITIES, QcUltra2.CURRENT_MODE, QcUltra2.MODE_CONFIG,
            QcUltra2.MODES_GET_ALL, QcUltra2.FAVORITES)) {
            fake.error(f, BmapError.FBLOCK_NOT_SUPPORTED)
        }
        fake.status(QcUltra2.CNC, "0b0303") // 11 steps, step 3, enabled, the user may not switch it off
    }

    @Test
    fun `firmware that refuses all of block 31 hides the modes and tries the 1_5 write`() = runTest {
        // Changed in 0.4.0: without modes the level goes through [1.5] SET_GET (docs/bose-models.md
        // §E2); only when that is refused too does it become read-only, the 0.3.1 behaviour.
        scriptRefusedBlock31("40ab01") // not in the catalogue: QC Ultra 2 defaults
        protocol.onConnected(fake, HeadphoneState())
        // The unit still pushes [31.3] on connect.
        var s = protocol.onUnsolicited(FakeRequester.statusFrame(QcUltra2.CURRENT_MODE, "01"), HeadphoneState())!!
        s = protocol.refresh(fake, s)

        assertFalse(s.capabilities.listeningModes)
        assertTrue(s.capabilities.liveNoiseControl, "[1.5] write not tried yet")
        assertFalse(s.capabilities.windBlock)
        assertFalse(s.capabilities.spatialAudio)
        assertFalse(s.capabilities.ancToggle, "[1.5] flags bit 1 set")
        assertFalse(s.capabilities.editableModes)
        assertTrue(s.modes.isEmpty())
        assertEquals(1, s.currentModeIndex)
        assertEquals(7, s.noise?.cancellingLevel) // step 3 of 0..10, step 0 = most cancelling
        assertEquals(1, fake.sent.count { it.address.block == 31 }, "one ERROR, then the block is skipped")

        protocol.refresh(fake, s)
        assertEquals(1, fake.sent.count { it.address.block == 31 }, "not asked again on this connection")
        for (command in listOf<suspend () -> Unit>(
            { protocol.setMode(fake, s, 0) },
            { protocol.saveCustomMode(fake, s, 4, "Mine", NoiseSettings()) },
            { protocol.setFavorites(fake, s, setOf(0)) },
        )) {
            val e = assertFailsWith<HeadphoneCommandException> { command() }
            assertEquals(QcUltra2Protocol.MODES_UNAVAILABLE_MESSAGE, e.message)
        }
        // [1.5] SET_GET is unscripted here: FuncNotSupp. The level is read-only from now on.
        val e = assertFailsWith<HeadphoneCommandException> { protocol.setNoise(fake, s, NoiseSettings(cancellingLevel = 3)) }
        assertEquals(QcUltra2Protocol.NOISE_READ_ONLY_MESSAGE, e.message)
        s = protocol.refresh(fake, s)
        assertFalse(s.capabilities.liveNoiseControl)
        assertEquals(7, s.noise?.cancellingLevel, "still shown")
        assertFailsWith<HeadphoneCommandException> { protocol.setNoise(fake, s, NoiseSettings(cancellingLevel = 3)) }
        assertEquals(1, fake.requestsTo(QcUltra2.CNC).count { it.operator == BmapOperator.SET_GET }, "not retried")
        assertEquals(1, fake.sent.count { it.address.block == 31 })
    }

    @Test
    fun `pushes keep the battery detail, the 1_5 level and auto-off current`() = runTest {
        scriptRefusedBlock31("402401") // NC 700: direct path
        fake.status(QcUltra2.STANDBY_TIMER, "14")
        var s = protocol.refresh(fake, protocol.onConnected(fake, HeadphoneState()))
        assertEquals(20, s.autoOffMinutes)

        s = protocol.onUnsolicited(FakeRequester.statusFrame(QcUltra2.CNC, "0b0a03"), s)!!
        assertEquals(0, s.noise?.cancellingLevel, "step 10 = least cancelling")
        s = protocol.onUnsolicited(FakeRequester.statusFrame(QcUltra2.STANDBY_TIMER, "a00005"), s)!!
        assertEquals(1440, s.autoOffMinutes)
        s = protocol.onUnsolicited(FakeRequester.statusFrame(QcUltra2.BATTERY, "3c00b401 3affff02 50ffff03"), s)!!
        assertEquals(58, s.batteryPercent, "no combined record: the lower bud")
        assertEquals(180, s.battery?.right?.minutesLeft)
        assertEquals(80, s.battery?.case?.percent)
        assertNull(s.battery?.overall)
    }

    @Test
    fun `a pid-less unit with an unknown name keeps the defaults and is named from 0_15`() = runTest {
        fake.status(QcUltra2.PRODUCT_ID_VARIANT, "40ab01")
        fake.status(QcUltra2.PRODUCT_NAME, "426f736520466f6f2045617262756473") // "Bose Foo Earbuds"
        val s = protocol.refresh(fake, protocol.onConnected(fake, HeadphoneState()))
        assertNull(s.info?.modelName)
        assertEquals("Foo Earbuds", s.info?.family)
        assertNull(s.info?.generation)
        assertEquals(FormFactor.EARBUDS, s.capabilities.formFactor)
        assertNull(s.capabilities.supportNote)
        assertEquals(QcUltra2.MODE_SLOT_COUNT, s.modes.size, "QC Ultra 2 defaults")
    }

    @Test
    fun `the NC 700 writes the level with 1_5 SET_GET and is marked basic`() = runTest {
        scriptRefusedBlock31("402401")
        fake.status(QcUltra2.CNC, "0b0301") // the user may switch noise cancelling off
        fake.on(QcUltra2.CNC, BmapOperator.SET_GET) { listOf(FakeRequester.statusFrame(QcUltra2.CNC, "0b%02x%02x".format(it.payload[0], it.payload[1]))) }
        var s = protocol.refresh(fake, protocol.onConnected(fake, HeadphoneState()))

        assertEquals("Noise Cancelling Headphones 700", s.info?.modelName)
        assertEquals(FormFactor.HEADPHONES, s.capabilities.formFactor)
        assertTrue(s.capabilities.supportNote.orEmpty().startsWith("Basic support"))
        assertTrue(s.capabilities.liveNoiseControl)
        assertTrue(s.capabilities.ancToggle)
        assertTrue(fake.requestsTo(QcUltra2.AUDIO_SETTINGS).isEmpty(), "goodyear has no [31.10]")

        s = protocol.setNoise(fake, s, NoiseSettings(cancellingLevel = 8, ancEnabled = false))
        val write = fake.requestsTo(QcUltra2.CNC).single { it.operator == BmapOperator.SET_GET }
        assertEquals("0200", write.payload.joinToString("") { "%02x".format(it) }) // level 8 → step 2, ANC off
        assertEquals(8, s.noise?.cancellingLevel)
        assertFalse(s.noise!!.ancEnabled)
    }
}
