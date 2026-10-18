package dev.earshot.service

import dev.earshot.bt.ReplyMode
import dev.earshot.core.ButtonAction
import dev.earshot.core.ButtonAssignment
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.core.NoiseSettings
import dev.earshot.core.PairedDevice
import dev.earshot.core.Sidetone
import dev.earshot.core.SpatialAudio
import dev.earshot.core.VoicePrompts
import dev.earshot.protocol.BmapError
import dev.earshot.protocol.BmapException
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.qcultra2.ModeConfigs
import dev.earshot.protocol.qcultra2.QcUltra2
import dev.earshot.protocol.toHex
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QcUltra2ProtocolTest {

    private val protocol = QcUltra2Protocol()
    private val fake = FakeRequester()
    private val phone = "C4:0F:08:11:22:33"
    private val laptop = "94:45:60:85:05:A7"

    @Before
    fun silenceLog() {
        ProtocolLog.sink = { _, _, _ -> }
    }

    /** The reference unit, as the bosectl captures describe it. */
    private fun scriptFullDevice() = with(fake) {
        status(QcUltra2.BATTERY, "50ffff00")
        status(QcUltra2.CHARGER_DETECT, "00")
        status(QcUltra2.PRODUCT_ID_VARIANT, "408201")
        status(QcUltra2.FIRMWARE_VERSION, "8.2.20+g34cf029".toByteArray().toHex())
        status(QcUltra2.SERIAL_NUMBER, "SN123".toByteArray().toHex())
        status(QcUltra2.PRODUCT_NAME, "Bose QC Ultra 2 HP".toByteArray().toHex())
        status(QcUltra2.MAC_ADDRESS, "c40f08aabbcc")
        status(QcUltra2.CUSTOM_NAME, "00" + "Fargo".toByteArray().toHex())
        status(QcUltra2.EQ, "f60a0000 f60afe01 f60afa02")
        status(QcUltra2.SIDETONE, "01020f")
        status(QcUltra2.MULTIPOINT, "07")
        status(QcUltra2.AUTO_PLAY_PAUSE, "01")
        status(QcUltra2.AUTO_ANSWER, "00")
        status(QcUltra2.VOICE_PROMPTS, "41 00008102 0000")
        status(QcUltra2.BUTTONS, "80090e00094002")
        status(QcUltra2.AUDIO_SETTINGS, "0500000001")
        status(QcUltra2.CURRENT_MODE, "00")
        on(QcUltra2.MODE_CONFIG, BmapOperator.START) {
            listOf(
                BmapPacket(31, 6, BmapOperator.PROCESSING),
                FakeRequester.modeStatus(0, "Quiet", cncWire = 0),
                FakeRequester.modeStatus(1, "Aware", cncWire = 10),
                FakeRequester.modeStatus(2, "Immersion", cncWire = 0, spatial = 2),
                FakeRequester.modeStatus(3, "Cinema", cncWire = 0, spatial = 1),
                FakeRequester.modeStatus(4, "Home", cncWire = 0, wind = true),
            ) + (5..10).map { FakeRequester.modeStatus(it, "None", cncWire = 10) }
        }
        status(QcUltra2.FAVORITES, "0b0003")
        status(QcUltra2.PAIRED_DEVICES, "03 c40f08112233 9445608505a7")
        on(QcUltra2.DEVICE_INFO, BmapOperator.GET) { req ->
            val name = if (req.payload.toHex() == "c40f08112233") "Pixel 9" else "ThinkPad"
            listOf(FakeRequester.statusFrame(QcUltra2.DEVICE_INFO, req.payload.toHex() + "0001" + "03" + name.toByteArray().toHex()))
        }
        status(QcUltra2.AUDIO_SOURCE, "000f01 c40f08112233")
    }

    // ── refresh ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `refresh reads the whole device over canned replies`() = runTest {
        scriptFullDevice()
        val s = protocol.refresh(fake, HeadphoneState())

        assertEquals(80, s.batteryPercent)
        assertEquals(false, s.isCharging)                          // [2.5] ChargerDetect (not the old [2.3])
        assertTrue(fake.requestsTo(QcUltra2.AUX_CABLE_DETECTION).isEmpty())
        assertNull(s.micMuted)                                     // [2.21] unscripted → FuncNotSupp: tolerated
        assertNull(s.latencyMs)
        assertEquals(0x4082, s.info?.productId)
        assertEquals("8.2.20+g34cf029", s.info?.firmwareVersion)
        assertEquals("SN123", s.info?.serialNumber)
        assertEquals("Bose QC Ultra 2 HP", s.info?.productName)
        assertEquals("C4:0F:08:AA:BB:CC", s.info?.bluetoothAddress)
        assertEquals("Fargo", s.info?.customName)
        assertEquals(HeadphoneEq(0, -2, -6), s.eq)
        assertEquals(Sidetone.MEDIUM, s.sidetone)
        assertEquals(true, s.sidetonePersistent)
        assertEquals(true, s.multipointEnabled)
        assertEquals(true, s.autoPlayPause)
        assertEquals(false, s.autoAnswer)
        assertEquals(VoicePrompts(enabled = false, languageId = 1), s.voicePrompts)
        assertEquals(ButtonAction.DISABLED, s.shortcutButton?.action)
        assertEquals(0x80, s.shortcutButton?.buttonId)
        assertEquals(NoiseSettings(cancellingLevel = 5), s.noise)
        assertEquals(0, s.currentModeIndex)

        assertEquals(11, s.modes.size)
        assertEquals(listOf("Quiet", "Aware", "Immersion", "Cinema", "Home"), s.modes.take(5).map { it.name })
        assertEquals(listOf(0, 1), s.modes.filter { it.isFavorite }.map { it.index })
        assertEquals(SpatialAudio.HEAD, s.modes[2].settings?.spatialAudio)
        assertEquals(NoiseSettings(cancellingLevel = 10, windBlock = true), s.modes[4].settings)
        assertTrue((5..10).all { !s.modes[it].isConfigured })

        // [4.4] header 03 = both connected; [4.9] and [4.6] unscripted (FuncNotSupp) → no "this phone", no profiles
        assertEquals(listOf(PairedDevice(phone, "Pixel 9", isActiveSource = true, isConnected = true, isBoseProduct = false),
            PairedDevice(laptop, "ThinkPad", isConnected = true, isBoseProduct = false)), s.pairedDevices)
        // [4.6] is asked once, then skipped for the rest after the refusal
        assertEquals(1, fake.requestsTo(QcUltra2.DEVICE_EXTENDED_INFO).size)

        // The dump went out as a START on [31.6] in MULTI mode.
        val dumpIndex = fake.sent.indexOfFirst { it.matches(QcUltra2.MODE_CONFIG) }
        assertEquals(BmapOperator.START, fake.sent[dumpIndex].operator)
        assertEquals(0, fake.sent[dumpIndex].payload.size)
        assertEquals(ReplyMode.MULTI, fake.requestModes[dumpIndex])
        // No fallback to [31.1] when [31.6] delivered.
        assertTrue(fake.requestsTo(QcUltra2.MODES_GET_ALL).isEmpty())
    }

    @Test
    fun `refresh skips device-info lookups for devices already named`() = runTest {
        scriptFullDevice()
        val current = HeadphoneState(pairedDevices = listOf(PairedDevice(phone, "Pixel 9")))
        val s = protocol.refresh(fake, current)
        assertEquals(listOf("9445608505a7"), fake.requestsTo(QcUltra2.DEVICE_INFO).map { it.payload.toHex() })
        assertEquals("Pixel 9", s.pairedDevices[0].name)
        assertEquals("ThinkPad", s.pairedDevices[1].name)
    }

    @Test
    fun `refresh falls back to GetAll when the mode dump is empty`() = runTest {
        scriptFullDevice()
        fake.on(QcUltra2.MODE_CONFIG, BmapOperator.START) { listOf(BmapPacket(31, 6, BmapOperator.RESULT)) }
        fake.on(QcUltra2.MODES_GET_ALL, BmapOperator.START) {
            listOf(BmapPacket(31, 1, BmapOperator.PROCESSING), FakeRequester.modeStatus(4, "Home", cncWire = 2))
        }
        val s = protocol.refresh(fake, HeadphoneState())
        assertEquals(1, fake.requestsTo(QcUltra2.MODES_GET_ALL).size)
        assertEquals("Home", s.modes[4].name)
        assertEquals(8, s.modes[4].settings?.cancellingLevel)
        assertEquals("Quiet", s.modes[0].name) // placeholder preset
    }

    @Test
    fun `a silent read leaves its field alone and the rest of the refresh proceeds`() = runTest {
        scriptFullDevice()
        fake.silent(QcUltra2.EQ)
        val current = HeadphoneState(eq = HeadphoneEq(1, 2, 3))
        val s = protocol.refresh(fake, current)
        assertEquals(HeadphoneEq(1, 2, 3), s.eq)
        assertEquals(80, s.batteryPercent)
        assertEquals(Sidetone.MEDIUM, s.sidetone)
        assertEquals(11, s.modes.size)
    }

    @Test
    fun `refresh gives up after three consecutive silent reads when 0_1 is silent too`() = runTest {
        scriptFullDevice()
        fake.silent(QcUltra2.FIRMWARE_VERSION)
        fake.silent(QcUltra2.SERIAL_NUMBER)
        fake.silent(QcUltra2.PRODUCT_NAME)
        fake.silent(QcUltra2.BMAP_VERSION) // the known-good check: nobody is listening at all
        val e = runCatching { protocol.refresh(fake, HeadphoneState()) }.exceptionOrNull()
        assertTrue(e is HeadphoneCommandException, "$e")
        assertTrue("stopped answering" in e!!.message.orEmpty())
        assertTrue(fake.requestsTo(QcUltra2.MAC_ADDRESS).isEmpty())
    }

    @Test
    fun `three silent reads while 0_1 still answers are skipped as absent and the refresh carries on`() = runTest {
        scriptFullDevice()
        fake.silent(QcUltra2.FIRMWARE_VERSION)
        fake.silent(QcUltra2.SERIAL_NUMBER)
        fake.silent(QcUltra2.PRODUCT_NAME)
        val s = protocol.refresh(fake, HeadphoneState())
        // Older firmware ignores functions it lacks: the link is fine, the rest was read.
        assertEquals(1, fake.requestsTo(QcUltra2.MAC_ADDRESS).size)
        assertTrue(fake.requestsTo(QcUltra2.BMAP_VERSION).isNotEmpty())
        assertEquals(80, s.batteryPercent)

        val before = fake.sent.size
        protocol.refresh(fake, s)
        val second = fake.sent.drop(before).map { it.address }
        assertTrue(QcUltra2.FIRMWARE_VERSION !in second && QcUltra2.SERIAL_NUMBER !in second && QcUltra2.PRODUCT_NAME !in second, second.toString())
    }

    @Test
    fun `refresh rethrows when the link is gone`() = runTest {
        scriptFullDevice()
        fake.on(QcUltra2.FIRMWARE_VERSION) { fake.isOpen = false; throw HeadphoneCommandException("Connection to the headphones was lost") }
        val e = runCatching { protocol.refresh(fake, HeadphoneState()) }.exceptionOrNull()
        assertTrue(e is HeadphoneCommandException)
        assertTrue("lost" in e!!.message.orEmpty())
    }

    // ── noise / modes ────────────────────────────────────────────────────────────────────

    @Test
    fun `setNoise writes the five inverted bytes and reads the echo back`() = runTest {
        fake.status(QcUltra2.AUDIO_SETTINGS, "0a00020101") // seed: most ambient, head, wind on, anc on
        fake.echo(QcUltra2.AUDIO_SETTINGS)
        val s = protocol.setNoise(fake, HeadphoneState(), NoiseSettings(cancellingLevel = 7, ancEnabled = true, windBlock = false, spatialAudio = SpatialAudio.OFF))

        assertEquals(listOf(BmapOperator.GET, BmapOperator.SET_GET), fake.sent.map { it.operator })
        assertEquals("0300000001", fake.sent[1].payload.toHex())              // cnc wire 3, autoCnc 0, spatial 0, wind 0, anc 1
        assertEquals("1f0a0205 0300000001".replace(" ", ""), fake.sent[1].encode().toHex())
        assertEquals(NoiseSettings(cancellingLevel = 7), s.noise)
    }

    @Test
    fun `setNoise drops the mode highlight once the settings leave the active slot`() = runTest {
        fake.status(QcUltra2.AUDIO_SETTINGS, "0000000001") // live = Quiet's saved settings
        fake.echo(QcUltra2.AUDIO_SETTINGS)
        val quiet = ModeConfigs.parse(FakeRequester.modeStatus(0, "Quiet", cncWire = 0).payload)!!
        val modes = buildModeList(mapOf(0 to quiet), null, emptyList())
        val onQuiet = HeadphoneState(currentModeIndex = 0, modes = modes)

        // Same as the slot: still Quiet.
        assertEquals(0, protocol.setNoise(fake, onQuiet, NoiseSettings(cancellingLevel = 10)).currentModeIndex)
        // Different: no saved mode, even if the firmware never pushes [31.3] = 0xFF.
        assertEquals(QcUltra2.NO_SAVED_MODE, protocol.setNoise(fake, onQuiet, NoiseSettings(cancellingLevel = 4)).currentModeIndex)
        // Slot settings unknown (placeholder) or index unknown: left alone.
        assertEquals(1, protocol.setNoise(fake, HeadphoneState(currentModeIndex = 1, modes = modes), NoiseSettings(cancellingLevel = 4)).currentModeIndex)
        assertNull(protocol.setNoise(fake, HeadphoneState(), NoiseSettings(cancellingLevel = 4)).currentModeIndex)
    }

    @Test
    fun `setNoise surfaces a firmware rejection as a BmapException`() = runTest {
        fake.status(QcUltra2.AUDIO_SETTINGS, "0500000001")
        fake.error(QcUltra2.AUDIO_SETTINGS, BmapError.RUNTIME, BmapOperator.SET_GET)
        val e = runCatching { protocol.setNoise(fake, HeadphoneState(), NoiseSettings()) }.exceptionOrNull()
        assertTrue(e is BmapException, "$e")
        assertEquals(BmapError.RUNTIME, (e as BmapException).error)
    }

    @Test
    fun `setMode sends the START frame and re-reads the live settings`() = runTest {
        fake.on(QcUltra2.CURRENT_MODE, BmapOperator.START) { listOf(BmapPacket(31, 3, BmapOperator.RESULT, byteArrayOf(2))) }
        fake.status(QcUltra2.AUDIO_SETTINGS, "0000020001")
        val s = protocol.setMode(fake, HeadphoneState(), 2)

        assertEquals("1f0305020200", fake.sent[0].encode().toHex())           // [31, 3, START, len 2, idx 2, announce 0]
        assertEquals(QcUltra2.AUDIO_SETTINGS, fake.sent[1].address)
        assertEquals(2, s.currentModeIndex)
        assertEquals(NoiseSettings(cancellingLevel = 10, spatialAudio = SpatialAudio.HEAD), s.noise)
    }

    @Test
    fun `setMode verifies with a GET when the START produced no RESULT`() = runTest {
        fake.silent(QcUltra2.CURRENT_MODE, BmapOperator.START)
        fake.status(QcUltra2.CURRENT_MODE, "04")
        fake.status(QcUltra2.AUDIO_SETTINGS, "0000000101")
        val s = protocol.setMode(fake, HeadphoneState(), 4)
        assertEquals(4, s.currentModeIndex)
        assertEquals(listOf(BmapOperator.START, BmapOperator.GET, BmapOperator.GET), fake.sent.map { it.operator })

        fake.status(QcUltra2.CURRENT_MODE, "00") // headphones stayed on Quiet
        val e = runCatching { protocol.setMode(fake, HeadphoneState(), 4) }.exceptionOrNull()
        assertTrue(e is HeadphoneCommandException)
        assertTrue("did not switch" in e!!.message.orEmpty())
    }

    @Test
    fun `setMode refuses empty and impossible slots without talking to the headphones`() = runTest {
        val modes = buildModeList(emptyMap(), null, emptyList())
        val empty = runCatching { protocol.setMode(fake, HeadphoneState(modes = modes), 6) }.exceptionOrNull()
        assertTrue(empty is HeadphoneCommandException && "empty" in empty.message.orEmpty(), "$empty")
        val oob = runCatching { protocol.setMode(fake, HeadphoneState(), 0xFF) }.exceptionOrNull()
        assertTrue(oob is HeadphoneCommandException)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `saveCustomMode writes a 40-byte slot and refuses presets`() = runTest {
        fake.echo(QcUltra2.MODE_CONFIG)
        val before = HeadphoneState(modes = buildModeList(emptyMap(), null, emptyList()).withFavorites(setOf(5)))
        val s = protocol.saveCustomMode(fake, before, 5, "Focus", NoiseSettings(cancellingLevel = 8, windBlock = true, spatialAudio = SpatialAudio.ROOM))

        val write = fake.sent.single()
        assertEquals(BmapOperator.SET_GET, write.operator)
        assertEquals(40, write.payload.size)
        assertEquals(5, write.payload[0].toInt())
        assertEquals("Focus", String(write.payload, 3, 5))
        assertEquals("0200010101", write.payload.copyOfRange(35, 40).toHex()) // cnc wire 2, auto 0, room, wind, anc
        val slot = s.modes[5]
        assertEquals(ListeningMode(5, "Focus", isPreset = false, isEditable = true, isConfigured = true, isFavorite = true,
            settings = NoiseSettings(cancellingLevel = 8, windBlock = true, spatialAudio = SpatialAudio.ROOM)), slot)

        val preset = runCatching { protocol.saveCustomMode(fake, before, 1, "x", NoiseSettings()) }.exceptionOrNull()
        assertTrue(preset is HeadphoneCommandException && "Aware" in preset.message.orEmpty(), "$preset")
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun `saveCustomMode re-dumps the slots when the echo is not the slot`() = runTest {
        fake.on(QcUltra2.MODE_CONFIG, BmapOperator.SET_GET) { listOf(BmapPacket(31, 6, BmapOperator.STATUS, byteArrayOf(5))) }
        fake.on(QcUltra2.MODE_CONFIG, BmapOperator.START) { listOf(FakeRequester.modeStatus(5, "Focus", cncWire = 0)) }
        val s = protocol.saveCustomMode(fake, HeadphoneState(), 5, "Focus", NoiseSettings())
        assertEquals("Focus", s.modes[5].name)
        assertEquals(listOf(BmapOperator.SET_GET, BmapOperator.START), fake.sent.map { it.operator })
    }

    @Test
    fun `setFavorites builds the mask and maps the unrepresentable slots to a readable error`() = runTest {
        fake.echo(QcUltra2.FAVORITES)
        val before = HeadphoneState(modes = buildModeList(emptyMap(), null, emptyList()))
        val s = protocol.setFavorites(fake, before, setOf(0, 1, 4))
        assertEquals("0b0013", fake.sent.single().payload.toHex())
        assertEquals(listOf(0, 1, 4), s.modes.filter { it.isFavorite }.map { it.index })

        // Slots 8-10 are now representable: the two-byte big-endian mask puts them in the first byte.
        val high = protocol.setFavorites(fake, before, setOf(0, 8))
        assertEquals("0b0101", fake.sent.last().payload.toHex())
        assertEquals(listOf(0, 8), high.modes.filter { it.isFavorite }.map { it.index })
        val e = runCatching { protocol.setFavorites(fake, before, setOf(11)) }.exceptionOrNull()
        assertTrue(e is HeadphoneCommandException, "$e")
        assertEquals(2, fake.sent.size)
    }

    // ── settings ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `setEq writes only the bands that changed`() = runTest {
        fake.echoWith(QcUltra2.EQ, "f60a0300 f60afe01 f60afa02")
        val s = protocol.setEq(fake, HeadphoneState(eq = HeadphoneEq(0, -2, -6)), HeadphoneEq(3, -2, -6))
        assertEquals("0300", fake.sent.single().payload.toHex())
        assertEquals(HeadphoneEq(3, -2, -6), s.eq)

        fake.sent.clear()
        protocol.setEq(fake, HeadphoneState(eq = null), HeadphoneEq(-1, 0, 1)) // unknown baseline: all three
        assertEquals(listOf("ff00", "0001", "0102"), fake.sent.map { it.payload.toHex() })

        fake.sent.clear()
        assertEquals(s, protocol.setEq(fake, s, HeadphoneEq(3, -2, -6)))
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `sidetone toggles and voice prompts round trip through the echo`() = runTest {
        fake.echo(QcUltra2.SIDETONE)
        fake.echo(QcUltra2.AUTO_PLAY_PAUSE)
        fake.echo(QcUltra2.AUTO_ANSWER)
        fake.echo(QcUltra2.VOICE_PROMPTS)
        var s = protocol.setSidetone(fake, HeadphoneState(), Sidetone.LOW, persistent = true)
        assertEquals("0103", fake.sent.last().payload.toHex())
        assertEquals(Sidetone.LOW, s.sidetone)
        assertEquals(true, s.sidetonePersistent)

        s = protocol.setAutoPlayPause(fake, s, false)
        assertEquals("00", fake.sent.last().payload.toHex())
        assertEquals(false, s.autoPlayPause)
        s = protocol.setAutoAnswer(fake, s, true)
        assertEquals(QcUltra2.AUTO_ANSWER, fake.sent.last().address)
        assertEquals(true, s.autoAnswer)

        s = protocol.setVoicePrompts(fake, s, VoicePrompts(enabled = true, languageId = 4))
        assertEquals("24", fake.sent.last().payload.toHex()) // (1 << 5) | 4
        assertEquals(VoicePrompts(true, 4), s.voicePrompts)
    }

    @Test
    fun `setShortcutButton keeps the reported button and event and detects an ignored write`() = runTest {
        fake.echo(QcUltra2.BUTTONS)
        val current = HeadphoneState(shortcutButton = ButtonAssignment(0x80, 9, ButtonAction.DISABLED, emptyList()))
        val s = protocol.setShortcutButton(fake, current, ButtonAction.SWITCH_DEVICE)
        assertEquals("800908", fake.sent.single().payload.toHex())
        assertEquals(ButtonAction.SWITCH_DEVICE, s.shortcutButton?.action)

        fake.echoWith(QcUltra2.BUTTONS, "80090e00094002") // firmware echoes but keeps Disabled
        val e = runCatching { protocol.setShortcutButton(fake, current, ButtonAction.TRACK_BACK) }.exceptionOrNull()
        assertTrue(e is HeadphoneCommandException && "ignored" in e.message.orEmpty(), "$e")
    }

    @Test
    fun `setDeviceName propagates error 5 as not-supported-by-firmware and reads the name back`() = runTest {
        fake.error(QcUltra2.CUSTOM_NAME, BmapError.OP_NOT_SUPPORTED, BmapOperator.SET_GET)
        val e = runCatching { protocol.setDeviceName(fake, HeadphoneState(), "Fargo") }.exceptionOrNull()
        assertTrue(e is BmapException && e.operatorNotSupported, "$e")
        val mapped = (e as BmapException).toCommandException()
        assertTrue(mapped.notSupportedByFirmware)
        assertEquals("The headphones' firmware doesn't support changing this", mapped.message)
        assertTrue("Bose" !in mapped.message.orEmpty())

        fake.echo(QcUltra2.CUSTOM_NAME)
        fake.status(QcUltra2.CUSTOM_NAME, "00" + "Earshot".toByteArray().toHex())
        val s = protocol.setDeviceName(fake, HeadphoneState(), " Earshot ")
        assertEquals("Earshot", String(fake.sent[1].payload))
        assertEquals("Earshot", s.info?.customName)

        val bad = runCatching { protocol.setDeviceName(fake, HeadphoneState(), "   ") }.exceptionOrNull()
        assertTrue(bad is HeadphoneCommandException)
    }

    @Test
    fun `setMultipoint tolerates the link dropping but not a refusal`() = runTest {
        fake.silent(QcUltra2.MULTIPOINT, BmapOperator.SET_GET)
        val s = protocol.setMultipoint(fake, HeadphoneState(multipointEnabled = false), true)
        assertEquals(true, s.multipointEnabled)
        assertEquals("01", fake.sent.single().payload.toHex())

        fake.error(QcUltra2.MULTIPOINT, BmapError.OP_NOT_SUPPORTED, BmapOperator.SET_GET)
        val e = runCatching { protocol.setMultipoint(fake, HeadphoneState(), false) }.exceptionOrNull()
        assertTrue(e is BmapException, "$e")
    }

    // ── devices / power ──────────────────────────────────────────────────────────────────

    @Test
    fun `switchToDevice retries while the routing table is empty`() = runTest {
        var calls = 0
        fake.on(QcUltra2.ROUTING, BmapOperator.START) {
            calls++
            if (calls < 3) listOf(FakeRequester.errorFrame(QcUltra2.ROUTING, BmapError.FUNC_NOT_SUPPORTED))
            else listOf(BmapPacket(4, 12, BmapOperator.RESULT, byteArrayOf(0) + "9445608505a7".let { h -> ByteArray(6) { i -> h.substring(2 * i, 2 * i + 2).toInt(16).toByte() } }))
        }
        val current = HeadphoneState(pairedDevices = listOf(PairedDevice(phone, "Pixel 9", isActiveSource = true), PairedDevice(laptop, "ThinkPad")))
        val s = protocol.switchToDevice(fake, current, laptop)
        assertEquals(3, calls)
        assertEquals("829445608505a7", fake.sent.last().payload.toHex())
        assertEquals(listOf(false, true), s.pairedDevices.map { it.isActiveSource })
    }

    @Test
    fun `switchToDevice gives up with a hint after the retry budget`() = runTest {
        fake.error(QcUltra2.ROUTING, BmapError.FUNC_NOT_SUPPORTED)
        val e = runCatching { protocol.switchToDevice(fake, HeadphoneState(), laptop) }.exceptionOrNull()
        assertTrue(e is HeadphoneCommandException && "Start playing" in e.message.orEmpty(), "$e")
        assertEquals(5, fake.sent.size)
        assertEquals(0L, currentTime % 500L) // waited RETRY_DELAY_MS between attempts (virtual time)
        assertTrue(currentTime >= 2_000L)
    }

    @Test
    fun `pairing mode and power off are fire-and-forget STARTs`() = runTest {
        val s0 = HeadphoneState(batteryPercent = 42)
        assertEquals(s0, protocol.enterPairingMode(fake, s0))
        assertEquals(s0, protocol.powerOff(fake, s0))
        assertEquals(listOf("0408050101", "0704050100"), fake.sent.map { it.encode().toHex() })
        assertTrue(fake.requestModes.isEmpty()) // send(), not request()
    }

    // ── pushes ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `unsolicited frames update the matching field only`() {
        val modes = buildModeList(emptyMap(), null, emptyList()).withFavorites(setOf(4))
        val current = HeadphoneState(batteryPercent = 80, currentModeIndex = 0, modes = modes)

        assertEquals(55, protocol.onUnsolicited(FakeRequester.statusFrame(QcUltra2.BATTERY, "37ffff00"), current)?.batteryPercent)
        assertEquals(0xFF, protocol.onUnsolicited(FakeRequester.statusFrame(QcUltra2.CURRENT_MODE, "ff"), current)?.currentModeIndex)
        assertEquals(NoiseSettings(cancellingLevel = 10, windBlock = true), protocol.onUnsolicited(FakeRequester.statusFrame(QcUltra2.AUDIO_SETTINGS, "0000000101"), current)?.noise)

        val slot = protocol.onUnsolicited(FakeRequester.modeStatus(4, "Office", cncWire = 4), current)!!.modes[4]
        assertEquals("Office", slot.name)
        assertEquals(6, slot.settings?.cancellingLevel)
        assertTrue(slot.isFavorite)

        assertEquals(listOf(1, 2), protocol.onUnsolicited(FakeRequester.statusFrame(QcUltra2.FAVORITES, "0b0006"), current)?.modes?.filter { it.isFavorite }?.map { it.index })
        assertEquals(HeadphoneEq(0, -2, -6), protocol.onUnsolicited(FakeRequester.statusFrame(QcUltra2.EQ, "f60a0000 f60afe01 f60afa02"), current)?.eq)

        assertNull(protocol.onUnsolicited(BmapPacket(2, 2, BmapOperator.RESULT, byteArrayOf(1)), current))
        assertNull(protocol.onUnsolicited(FakeRequester.statusFrame(QcUltra2.AUTH_CAPABILITIES, "0339083e07"), current))
        assertNull(protocol.onUnsolicited(FakeRequester.statusFrame(QcUltra2.BATTERY, ""), current))
        assertFalse(protocol.onUnsolicited(FakeRequester.statusFrame(QcUltra2.BATTERY, "37ffff00"), current)!!.isBusy)
    }
}
