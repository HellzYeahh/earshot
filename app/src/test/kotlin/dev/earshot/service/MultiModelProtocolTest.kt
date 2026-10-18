package dev.earshot.service

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

        assertEquals("Ultra Open Earbuds", s.info?.modelName)
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

    @Test
    fun `firmware that refuses all of block 31 hides the modes and shows the 1_5 level read-only`() = runTest {
        fake.status(QcUltra2.PRODUCT_ID_VARIANT, "402401") // NC700 "goodyear" (bosectl #44): no profile
        for (f in listOf(QcUltra2.AUDIO_SETTINGS, QcUltra2.CURRENT_MODE, QcUltra2.MODE_CONFIG, QcUltra2.MODES_GET_ALL, QcUltra2.FAVORITES)) {
            fake.error(f, BmapError.FBLOCK_NOT_SUPPORTED)
        }
        fake.status(QcUltra2.CNC, "0b0303") // 11 steps, step 3, enabled
        protocol.onConnected(fake, HeadphoneState())
        // The unit still pushes [31.3] on connect.
        var s = protocol.onUnsolicited(FakeRequester.statusFrame(QcUltra2.CURRENT_MODE, "01"), HeadphoneState())!!
        s = protocol.refresh(fake, s)

        assertFalse(s.capabilities.listeningModes)
        assertFalse(s.capabilities.liveNoiseControl)
        assertFalse(s.capabilities.editableModes)
        assertTrue(s.modes.isEmpty())
        assertEquals(1, s.currentModeIndex)
        assertEquals(7, s.noise?.cancellingLevel) // step 3 of 0..10, step 0 = most cancelling
        assertEquals(1, fake.sent.count { it.address.block == 31 }, "one ERROR, then the block is skipped")

        protocol.refresh(fake, s)
        assertEquals(1, fake.sent.count { it.address.block == 31 }, "not asked again on this connection")
        for (command in listOf<suspend () -> Unit>(
            { protocol.setMode(fake, s, 0) },
            { protocol.setNoise(fake, s, NoiseSettings(cancellingLevel = 3)) },
            { protocol.saveCustomMode(fake, s, 4, "Mine", NoiseSettings()) },
            { protocol.setFavorites(fake, s, setOf(0)) },
        )) {
            val e = assertFailsWith<HeadphoneCommandException> { command() }
            assertEquals(QcUltra2Protocol.MODES_UNAVAILABLE_MESSAGE, e.message)
        }
        assertEquals(1, fake.sent.count { it.address.block == 31 })
    }
}
