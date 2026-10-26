package dev.earshot.virtual

import dev.earshot.bt.BtLog
import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.SoundcoreConnection
import dev.earshot.bt.SoundcoreLinkFactory
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.soundcore.AmbientMode
import dev.earshot.protocol.soundcore.SoundcoreCommands
import dev.earshot.protocol.soundcore.SoundcoreModel
import dev.earshot.protocol.soundcore.SoundcorePacket
import dev.earshot.protocol.soundcore.SoundcoreSettings
import dev.earshot.protocol.soundcore.SoundcoreState
import dev.earshot.protocol.soundcore.SoundcoreStateReader
import dev.earshot.protocol.soundcore.SoundcoreWriteRefused
import dev.earshot.protocol.soundcore.SoundcoreWrites
import dev.earshot.service.SoundcoreModelMapping
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real Soundcore connection, framing, state reader, write builders and mapping against a
 * [VirtualSoundcore] (Liberty 4 NC earbuds and Space One Pro headphones, and read-only layouts).
 * The repository's steps (read, unlock, build, write, read back, confirm) are repeated here without Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoundcoreModelsEndToEndTest {

    @Before
    fun quiet() {
        BtLog.sink = { _, _, _ -> }
    }

    private class Session(val conn: SoundcoreConnection, val transport: VirtualSoundcoreTransport, val unit: VirtualSoundcore) {
        suspend fun read(): SoundcoreState {
            val body = conn.request(SoundcoreCommands.REQUEST_STATE).body
            val state = assertNotNull(SoundcoreStateReader.read(unit.model, body), "state of ${unit.model.code}")
            conn.unlockWrites(state.writable)
            return state
        }

        /** The repository's write: build from the last state, send, read back, confirm. */
        suspend fun write(before: SoundcoreState, packet: SoundcorePacket): SoundcoreState {
            conn.request(packet.command, packet.body)
            return read().also { assertTrue(SoundcoreWrites.confirms(before, it, packet), "${packet.command.toString(16)} took") }
        }
    }

    private fun TestScope.session(unit: VirtualSoundcore): Session {
        val transport = VirtualSoundcoreTransport(unit)
        val conn = SoundcoreConnection(transport, backgroundScope, minWriteGapMs = 0, model = unit.model)
        return Session(conn, transport, unit)
    }

    // ---- Liberty 4 NC ------------------------------------------------------------------------

    @Test fun `reads the Liberty 4 NC into three modes, per-bud and case battery, firmware and its controls`() = runTest {
        val s = session(VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC, leftBattery = 3, rightBattery = 5, caseBattery = 4))
        val state = s.read()
        assertTrue(state.writable)
        val mapped = SoundcoreModelMapping.apply(HeadphoneState(), state, now = 0)
        assertEquals(listOf("Noise cancelling", "Transparency", "Normal"), mapped.modes.map { it.name })
        assertEquals(SoundcoreModelMapping.MODE_NC, mapped.currentModeIndex)
        assertEquals(60, mapped.batteryPercent) // the lower bud: 3 on the 0..5 scale
        assertEquals(60, mapped.battery?.left?.percent)
        assertEquals(100, mapped.battery?.right?.percent)
        assertEquals(80, mapped.battery?.case?.percent)
        assertEquals("01.23", mapped.info?.firmwareVersion)
        assertEquals("Soundcore Liberty 4 NC", mapped.info?.productName)
        assertEquals(FormFactor.EARBUDS, mapped.capabilities.formFactor)
        assertTrue(mapped.capabilities.modesOnHome && mapped.capabilities.listeningModes)
        assertFalse(mapped.capabilities.verified)
        assertFalse(mapped.capabilities.noiseCancelling, "the Bose noise card stays hidden: the level is a vendor control")
        assertEquals(
            listOf("nc_type", "nc_level", "transparency_type", "wind", "touch_tone", "gaming_mode", "leak_compensation", "multipoint", "low_battery_prompt", "auto_off"),
            mapped.vendorControls.map { it.id },
        )
        assertEquals(listOf("Off", "10 min", "20 min", "30 min", "1 hour"), mapped.vendorControls.last().options)
        assertEquals(2, mapped.vendorControls.first { it.id == "nc_level" }.value, "manual level 3 is option index 2 of 1..5")
        s.conn.close()
    }

    @Test fun `no write leaves the phone before the state matched the layout`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC)
        val s = session(unit)
        // Nothing read yet: the connection is locked for writes.
        assertFalse(s.conn.writesUnlocked)
        assertFailsWith<HeadphoneCommandException> { s.conn.request(SoundcoreCommands.SET_SOUND_MODES, ByteArray(7)) }
        assertFailsWith<HeadphoneCommandException> { s.conn.request(SoundcoreCommands.SET_TOUCH_TONE, byteArrayOf(1)) }
        assertTrue(unit.received.isEmpty(), "nothing was written: ${unit.received}")
        // Reads work, and a matching state unlocks.
        s.read()
        assertTrue(s.conn.writesUnlocked)
        s.conn.unlockWrites(false)
        assertFailsWith<HeadphoneCommandException> { s.conn.request(SoundcoreCommands.SET_TOUCH_TONE, byteArrayOf(1)) }
        assertEquals(listOf(SoundcoreCommands.REQUEST_STATE), unit.received.map { it.command })
        s.conn.close()
    }

    @Test fun `every control round-trips through the earbuds' state`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC)
        val s = session(unit)
        var state = s.read()
        // Ambient modes in turn; the other mode fields ride along untouched.
        for (mode in listOf(AmbientMode.TRANSPARENCY, AmbientMode.NORMAL, AmbientMode.NOISE_CANCELLING)) {
            state = s.write(state, SoundcoreWrites.setAmbient(state, mode))
            assertEquals(mode, state.ambient)
            assertEquals(3, state.modes?.manualLevel)
        }
        // In noise cancelling: type, level, wind.
        state = s.write(state, SoundcoreWrites.control(state, "nc_level", 4))
        assertEquals(5, state.modes?.manualLevel)
        state = s.write(state, SoundcoreWrites.control(state, "wind", 1))
        assertEquals(true, state.modes?.windOn)
        state = s.write(state, SoundcoreWrites.control(state, "nc_type", 1))
        assertEquals(1, state.modes?.ncType)
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.control(state, "nc_level", 0) } // adaptive: no manual level
        state = s.write(state, SoundcoreWrites.control(state, "nc_type", 0))
        // In transparency: the type.
        state = s.write(state, SoundcoreWrites.setAmbient(state, AmbientMode.TRANSPARENCY))
        state = s.write(state, SoundcoreWrites.control(state, "transparency_type", 1))
        assertEquals(1, state.modes?.transparencyType)
        // The switches and the auto power-off choice.
        for (id in listOf("touch_tone", "gaming_mode", "leak_compensation", "multipoint", "low_battery_prompt")) {
            state = s.write(state, SoundcoreWrites.control(state, id, 1))
            assertEquals(1, state.settings[id], id)
            state = s.write(state, SoundcoreWrites.control(state, id, 0))
            assertEquals(0, state.settings[id], id)
        }
        state = s.write(state, SoundcoreWrites.control(state, "auto_off", 3))
        assertEquals(3, state.settings["auto_off"])
        state = s.write(state, SoundcoreWrites.control(state, "auto_off", 0))
        assertEquals(0, state.settings["auto_off"])
        assertEquals(2, unit.autoOffIndex, "switching it off keeps the duration")
        // Only reads and the documented writes reached the earbuds.
        val allowed = setOf(
            SoundcoreCommands.REQUEST_STATE, SoundcoreCommands.SET_SOUND_MODES, SoundcoreCommands.SET_TOUCH_TONE,
            SoundcoreCommands.SET_GAMING_MODE_10, SoundcoreCommands.SET_LEAK_COMPENSATION, SoundcoreCommands.SET_MULTIPOINT,
            SoundcoreCommands.SET_LOW_BATTERY_PROMPT, SoundcoreCommands.SET_AUTO_OFF,
        )
        assertTrue(unit.received.all { it.command in allowed }, "${unit.received.map { it.command.toString(16) }}")
        s.conn.close()
    }

    @Test fun `unpair, pairing, power off, find, resets and firmware ids never reach the earbuds, even unlocked`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC)
        val s = session(unit)
        s.read()
        assertTrue(s.conn.writesUnlocked)
        for (command in listOf(0x0B83, 0x0B85, 0x0B81, 0x0B82, 0x0189, 0x0185, 0x1089, 0x0482, 0x1482, 0x0901, 0x0381, 0x0387, 0x1881, 0xFF81)) {
            assertFailsWith<HeadphoneCommandException> { s.conn.request(command, byteArrayOf(1)) }
        }
        // A documented command with the wrong shape is refused too.
        assertFailsWith<HeadphoneCommandException> { s.conn.request(SoundcoreCommands.SET_SOUND_MODES, ByteArray(6)) }
        assertFailsWith<HeadphoneCommandException> { s.conn.request(SoundcoreCommands.SET_TOUCH_TONE, byteArrayOf(7)) }
        assertEquals(listOf(SoundcoreCommands.REQUEST_STATE), unit.received.map { it.command })
        s.conn.close()
    }

    @Test fun `an earbud that acknowledges and ignores a write is caught by the read-back`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC, ignoreWrites = true)
        val s = session(unit)
        val before = s.read()
        val packet = SoundcoreWrites.setAmbient(before, AmbientMode.NORMAL)
        s.conn.request(packet.command, packet.body)
        val after = s.read()
        assertEquals(AmbientMode.NOISE_CANCELLING, after.ambient)
        assertFalse(SoundcoreWrites.confirms(before, after, packet))
        val tone = SoundcoreWrites.setSetting(before, SoundcoreSettings.TOUCH_TONE, 1)
        s.conn.request(tone.command, tone.body)
        assertFalse(SoundcoreWrites.confirms(before, s.read(), tone))
        s.conn.close()
    }

    @Test fun `a bare ack to the first state request is answered by asking again`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC, bareAckFirst = true)
        val s = session(unit)
        assertEquals(0, s.conn.request(SoundcoreCommands.REQUEST_STATE).body.size)
        assertEquals(159, s.conn.request(SoundcoreCommands.REQUEST_STATE).body.size)
        s.conn.close()
    }

    @Test fun `pushes update battery, charging and the mode block without a re-read`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC)
        val s = session(unit)
        val pushes = mutableListOf<SoundcorePacket>()
        val job = backgroundScope.launch { s.conn.unsolicited.collect { pushes += it } }
        testScheduler.runCurrent()
        val state = s.read()
        s.transport.push(SoundcorePacket(SoundcoreCommands.PUSH_BATTERY, byteArrayOf(2, 5, 5), inbound = true))
        s.transport.push(SoundcorePacket(SoundcoreCommands.PUSH_CHARGING, byteArrayOf(1, 0), inbound = true))
        val block = state.modes!!.withAmbient(AmbientMode.TRANSPARENCY).raw
        s.transport.push(SoundcorePacket(SoundcoreCommands.PUSH_SOUND_MODES, block, inbound = true))
        testScheduler.runCurrent()
        assertEquals(listOf(SoundcoreCommands.PUSH_BATTERY, SoundcoreCommands.PUSH_CHARGING, SoundcoreCommands.PUSH_SOUND_MODES), pushes.map { it.command })
        val battery = SoundcoreStateReader.batteryPush(state.model, pushes[0].body)!!
        assertEquals(40 to 100, battery.left to battery.right)
        assertEquals(100, battery.case)
        assertEquals(true, SoundcoreStateReader.chargingPush(pushes[1].body))
        val updated = state.withBattery(battery.left, battery.right, battery.case, battery.overall).withCharging(true).withModeBlock(
            dev.earshot.protocol.soundcore.SoundModes(state.modes!!.codec, pushes[2].body),
        )
        val mapped = SoundcoreModelMapping.apply(HeadphoneState(), updated, now = 0)
        assertEquals(40, mapped.batteryPercent)
        assertEquals(true, mapped.isCharging)
        assertEquals(SoundcoreModelMapping.MODE_TRANSPARENCY, mapped.currentModeIndex)
        job.cancel()
        s.conn.close()
    }

    // ---- Headphones --------------------------------------------------------------------------

    @Test fun `Space One Pro headphones map one battery, the same three modes and their own controls`() = runTest {
        val s = session(VirtualSoundcore(SoundcoreModel.SPACE_ONE_PRO, leftBattery = 7))
        val state = s.read()
        assertTrue(state.writable)
        val mapped = SoundcoreModelMapping.apply(HeadphoneState(), state, now = 0)
        assertEquals(80, mapped.batteryPercent)
        assertEquals(80, mapped.battery?.overall?.percent)
        assertNull(mapped.battery?.left)
        assertEquals(FormFactor.HEADPHONES, mapped.capabilities.formFactor)
        assertEquals(listOf("nc_type", "nc_level", "wind", "multipoint", "auto_off"), mapped.vendorControls.map { it.id })
        assertEquals(listOf("Off", "30 min", "1 hour", "90 min", "2 hours"), mapped.vendorControls.last().options)
        // Space One Pro keeps byte 2 (a fixed 1) as it is when the mode changes.
        val unit = s.unit
        unit.modeBlock = byteArrayOf(0, 0x31, 1, 0, 0, 2)
        val after = s.write(s.read(), SoundcoreWrites.setAmbient(s.read(), AmbientMode.NORMAL))
        assertContentEquals(byteArrayOf(2, 0x31, 1, 0, 0, 2), unit.modeBlock)
        assertEquals(AmbientMode.NORMAL, after.ambient)
        s.conn.close()
    }

    @Test fun `Space 2 is read-only, its mode shows and every change is refused before it leaves the phone`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.SPACE_2)
        unit.modeBlock = byteArrayOf(1, 0x3F, 0xFF.toByte(), 0, 0, 1)
        val s = session(unit)
        val state = s.read()
        assertFalse(state.writable)
        val mapped = SoundcoreModelMapping.apply(HeadphoneState(), state, now = 0)
        assertEquals(SoundcoreModelMapping.MODE_TRANSPARENCY, mapped.currentModeIndex)
        assertTrue(mapped.vendorControls.isEmpty())
        assertTrue(mapped.capabilities.supportNote!!.contains("can't change"))
        assertFailsWith<SoundcoreWriteRefused> { SoundcoreWrites.setAmbient(state, AmbientMode.NORMAL) }
        assertFailsWith<HeadphoneCommandException> { s.conn.request(SoundcoreCommands.SET_SOUND_MODES, ByteArray(6)) }
        assertFailsWith<HeadphoneCommandException> { s.conn.request(SoundcoreCommands.SESSION_INIT) }
        assertEquals(listOf(SoundcoreCommands.REQUEST_STATE), unit.received.map { it.command })
        s.conn.close()
    }

    @Test fun `a model whose table asks for the session init gets it only once unlocked`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_5)
        val s = session(unit)
        // `05 01` is a read; `05 81` is a write and waits for the unlock.
        assertFailsWith<HeadphoneCommandException> { s.conn.request(SoundcoreCommands.SESSION_INIT, timeoutsMs = listOf(100)) }
        s.read()
        assertEquals(47, s.conn.request(SoundcoreCommands.SESSION_INFO, timeoutsMs = listOf(100)).body.size)
        s.conn.request(SoundcoreCommands.SESSION_INIT, timeoutsMs = listOf(100))
        assertEquals(
            listOf(SoundcoreCommands.REQUEST_STATE, SoundcoreCommands.SESSION_INFO, SoundcoreCommands.SESSION_INIT),
            unit.received.map { it.command },
        )
        s.conn.close()
    }

    @Test fun `an unreadable state keeps the name and says nothing is shown`() {
        val mapped = SoundcoreModelMapping.unreadable(HeadphoneState(), SoundcoreModel.P40I, 77, now = 0)
        assertEquals("Soundcore P40i", mapped.info?.productName)
        assertTrue(mapped.modes.isEmpty())
        assertFalse(mapped.capabilities.listeningModes)
        assertTrue(mapped.capabilities.supportNote!!.contains("77 bytes"))
    }

    // ---- Routing and demo --------------------------------------------------------------------

    @Test fun `names route to the Soundcore stack and the link factory hands the model to the connection`() = runTest {
        assertEquals(DeviceFamily.SOUNDCORE, DeviceFamily.forName("soundcore Liberty 4 NC"))
        assertEquals(DeviceFamily.SOUNDCORE, DeviceFamily.forName("Soundcore Space One Pro"))
        assertEquals(DeviceFamily.SOUNDCORE, DeviceFamily.forName("soundcore Sleep A30"))
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.forName("soundcore Boom 2"))
        assertEquals(DeviceFamily.JBL, DeviceFamily.forName("JBL Tour Pro 2"))
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.forName("JBL Tune 770NC"))
        val factory = SoundcoreLinkFactory()
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC)
        val adopted = factory.adopt(VirtualSoundcoreTransport(unit), backgroundScope)
        assertEquals(SoundcoreModel.LIBERTY_4_NC, adopted.model)
        assertFalse(adopted.writesUnlocked)
        // The original demo transport carries no model: the bare connection keeps its original behaviour.
        val legacy = factory.adopt(VirtualSleepA30Transport(VirtualSleepA30()), backgroundScope)
        assertNull(legacy.model)
        assertTrue(legacy.writesUnlocked)
        adopted.close()
        legacy.close()
    }

    @Test fun `the new demo devices play the models they are named after`() {
        assertEquals(SoundcoreModel.LIBERTY_4_NC, DemoDevice.SOUNDCORE_LIBERTY_4_NC.soundcoreModel())
        assertEquals(SoundcoreModel.SPACE_ONE_PRO, DemoDevice.SOUNDCORE_SPACE_ONE_PRO.soundcoreModel())
        assertNull(DemoDevice.SOUNDCORE_SLEEP_A30.soundcoreModel())
        for (d in listOf(DemoDevice.SOUNDCORE_LIBERTY_4_NC, DemoDevice.SOUNDCORE_SPACE_ONE_PRO)) {
            assertTrue(d.isSoundcore)
            assertNull(d.virtualProfile())
            assertEquals(SoundcoreModel.fromName(d.displayName), d.soundcoreModel(), d.displayName)
        }
    }

    @Test fun `every model with a layout can be emulated and read back by its own reader`() {
        for (model in SoundcoreModel.entries.filter { it.layout != null }) {
            val unit = VirtualSoundcore(model)
            val state = assertNotNull(SoundcoreStateReader.read(model, unit.stateBody()), model.code)
            assertEquals(model.canWrite, state.writable, model.code)
            assertEquals(AmbientMode.NOISE_CANCELLING, state.ambient, model.code)
            assertEquals(model.serialPrefix, state.serial?.take(4), model.code)
        }
    }
}
