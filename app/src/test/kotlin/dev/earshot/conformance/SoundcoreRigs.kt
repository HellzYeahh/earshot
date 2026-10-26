package dev.earshot.conformance

import dev.earshot.bt.ByteTransport
import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.ManagedLink
import dev.earshot.bt.SoundcoreConnection
import dev.earshot.bt.SoundcoreLinkFactory
import dev.earshot.bt.SoundcoreModelTransport
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.soundcore.D1301State
import dev.earshot.protocol.soundcore.SoundModes
import dev.earshot.protocol.soundcore.SoundcoreCommands
import dev.earshot.protocol.soundcore.SoundcoreFrameBuffer
import dev.earshot.protocol.soundcore.SoundcoreModel
import dev.earshot.protocol.soundcore.SoundcorePacket
import dev.earshot.protocol.soundcore.SoundcoreReads
import dev.earshot.protocol.soundcore.SoundcoreState
import dev.earshot.protocol.soundcore.SoundcoreStateReader
import dev.earshot.protocol.soundcore.SoundcoreWriteRefused
import dev.earshot.protocol.soundcore.SoundcoreWrites
import dev.earshot.service.SoundcoreModelMapping
import dev.earshot.service.SoundcoreStateMapping
import dev.earshot.virtual.DemoDevice
import dev.earshot.virtual.VirtualSleepA30
import dev.earshot.virtual.VirtualSleepA30Transport
import dev.earshot.virtual.VirtualSoundcore
import dev.earshot.virtual.VirtualSoundcoreTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope

/**
 * What both Soundcore rigs share. **Their session steps are a commented copy of
 * `SoundcoreHeadphoneRepository`** (readState, applyPush, write / writeModel): that repository keeps
 * its protocol logic inside the Android-bound class and no Android-free Soundcore session exists, so a
 * passing row proves the copy, not the repository, and the copy can drift. Extracting a session
 * would touch the hardware-tested Sleep A30 path; that is left to A6 with a manual check on the
 * Sleep A30. The connection, framing, guard, state reader, write builders and mappings are the real ones.
 */
abstract class SoundcoreRigBase(test: TestScope, name: String) : RigBase<SoundcorePacket>(test, name) {
    override val family = DeviceFamily.SOUNDCORE
    override val factory = SoundcoreLinkFactory()

    protected var conn: SoundcoreConnection? = null
    override val connection: ManagedLink? get() = conn

    protected fun c(): SoundcoreConnection = checkNotNull(conn)

    /** Unpair, power off, find tone and an unknown write (SoundcoreEndToEndTest's list). */
    protected open val forbiddenCommands: List<Int> = listOf(0x0B83, 0x0189, 0x1089, 0x0681)

    override val forbiddenWrites: List<Pair<String, suspend () -> Unit>>
        get() = forbiddenCommands.map { command -> refused(SoundcorePacket.commandName(command)) { c().request(command, byteArrayOf(1)) } }

    override fun decode(bytes: ByteArray): List<Sent> = SoundcoreFrameBuffer().feed(bytes).map { p ->
        Sent(SoundcorePacket.commandName(p.command), !p.isWrite && SoundcoreReads.isRead(conn?.model, p.command))
    }

    /** The earbuds push state, battery, charging and modes by themselves: nothing to subscribe to. */
    override fun subscribed(): Boolean? = null

    /** The repository's refresh: `connectionManager.connection.value ?: throw`, then the state read. */
    override suspend fun refresh(): HeadphoneState {
        requireOpen()
        readState(c())
        return state
    }

    protected abstract suspend fun readState(conn: SoundcoreConnection)
}

/**
 * The Sleep A30 (D1301, tested on hardware): the original path, untouched. The demo transport
 * carries no model, so the adopted connection keeps the original allow-list and writes from the start.
 */
class SleepA30Rig(test: TestScope, override val demo: DemoDevice, private val unit: VirtualSleepA30 = VirtualSleepA30()) :
    SoundcoreRigBase(test, "Soundcore Sleep A30") {

    private var transport: VirtualSleepA30Transport? = null

    override suspend fun connect(): HeadphoneState {
        val t = VirtualSleepA30Transport(unit)
        val spy = SpyTransport(t)
        val c = factory.adopt(spy, test.backgroundScope)
        transport = t
        conn = c
        beginSession(spy)
        collectPushes(c.unsolicited)
        runCurrent()
        readState(c) // runSession: readState, no session init on the Sleep A30
        return state
    }

    // ---- Copy of SoundcoreHeadphoneRepository (Sleep A30 branch) ----------------------------

    override suspend fun readState(conn: SoundcoreConnection) {
        applyState(conn.request(SoundcoreCommands.REQUEST_STATE).body)
    }

    private fun applyState(body: ByteArray) {
        val parsed = D1301State.parse(body) ?: return
        publish(SoundcoreStateMapping.apply(state, parsed, now = 0))
    }

    override suspend fun applyPush(push: SoundcorePacket, current: HeadphoneState): HeadphoneState? = when (push.command) {
        SoundcoreCommands.REQUEST_STATE -> D1301State.parse(push.body)?.let { SoundcoreStateMapping.apply(current, it, now = 0) }
        SoundcoreCommands.PUSH_BATTERY -> D1301State.parseBattery(push.body)?.let { (l, r) -> SoundcoreStateMapping.withBattery(current, l, r) }
        else -> null
    }

    private suspend fun write(command: Int, value: Int): HeadphoneState {
        requireOpen()
        c().request(command, byteArrayOf(value.toByte()))
        readState(c())
        return state
    }

    override suspend fun setMode(index: Int): HeadphoneState = when (index) {
        SoundcoreStateMapping.MODE_ANC -> write(SoundcoreCommands.SET_ANC_D1301, 1)
        SoundcoreStateMapping.MODE_OFF -> write(SoundcoreCommands.SET_ANC_D1301, 0)
        else -> throw HeadphoneCommandException("The Sleep A30 has two modes: noise cancelling and off")
    }

    // ---- The virtual unit -------------------------------------------------------------------

    override fun deviceModeIndex(): Int = if (unit.ancOn) SoundcoreStateMapping.MODE_ANC else SoundcoreStateMapping.MODE_OFF

    /** The Sleep A30's 0..9 battery steps ((n + 1) × 10 %). */
    private fun raw(percent: Int): Int = (0..9).first { D1301State.battery(it) == percent }

    override suspend fun pushBattery(percent: Int): Boolean {
        val r = raw(percent)
        unit.leftBattery = r
        unit.rightBattery = r
        checkNotNull(transport).push(SoundcorePacket(SoundcoreCommands.PUSH_BATTERY, byteArrayOf(r.toByte(), r.toByte()), inbound = true))
        return true
    }

    override suspend fun pollBattery(percent: Int): HeadphoneState {
        val r = raw(percent)
        unit.leftBattery = r
        unit.rightBattery = r
        return refresh() // the repository's state poll is the battery read
    }

    override fun dropLink() = checkNotNull(transport).close()
}

/**
 * A Soundcore table model (Liberty 4 NC, Space One Pro): the vendor-service path with the model's
 * own allow-list, writes locked until a state matched the layout, then built by [SoundcoreWrites]
 * and confirmed by a read-back.
 */
class SoundcoreModelRig(test: TestScope, override val demo: DemoDevice, private val model: SoundcoreModel) :
    SoundcoreRigBase(test, model.displayName) {

    private val unit = VirtualSoundcore(model)
    private var transport: VirtualSoundcoreTransport? = null

    /** The last state that parsed, which writes are built from (the repository's `latest`). */
    private var latest: SoundcoreState? = null

    /** The spy must still say which model it plays, or adopt() falls back to the Sleep A30 rules. */
    private class ModelSpy(spy: SpyTransport, override val soundcoreModel: SoundcoreModel) : ByteTransport by spy, SoundcoreModelTransport

    override suspend fun connect(): HeadphoneState {
        val t = VirtualSoundcoreTransport(unit)
        val spy = SpyTransport(t)
        val c = factory.adopt(ModelSpy(spy, model), test.backgroundScope)
        check(c.model == model) { "adopt() lost the model" }
        transport = t
        conn = c
        latest = null
        beginSession(spy)
        collectPushes(c.unsolicited)
        runCurrent()
        readState(c)
        sessionInit(c)
        return state
    }

    // ---- Copy of SoundcoreHeadphoneRepository (table-model branch) ---------------------------

    override suspend fun readState(conn: SoundcoreConnection) {
        var reply = conn.request(SoundcoreCommands.REQUEST_STATE)
        // Some models answer a first request with a bare ack: ask again for the body.
        if (reply.body.isEmpty()) reply = conn.request(SoundcoreCommands.REQUEST_STATE)
        applyModelState(conn, reply.body)
    }

    private fun applyModelState(conn: SoundcoreConnection, body: ByteArray) {
        val parsed = SoundcoreStateReader.read(model, body)
        if (parsed == null) {
            latest = null
            conn.unlockWrites(false)
            publish(SoundcoreModelMapping.unreadable(state, model, body.size, now = 0))
            return
        }
        conn.unlockWrites(parsed.writable)
        latest = parsed
        publish(SoundcoreModelMapping.apply(state, parsed, now = 0))
    }

    private suspend fun sessionInit(conn: SoundcoreConnection) {
        if (model.layout?.sessionInit != true || !conn.writesUnlocked) return
        try {
            conn.request(SoundcoreCommands.SESSION_INFO, timeoutsMs = listOf(1_500L))
            delay(300L)
            conn.request(SoundcoreCommands.SESSION_INIT, timeoutsMs = listOf(1_000L))
        } catch (e: HeadphoneCommandException) {
            // Not answered: the repository logs it and carries on.
        }
    }

    override suspend fun applyPush(push: SoundcorePacket, current: HeadphoneState): HeadphoneState? {
        val before = latest
        val next: SoundcoreState? = when (push.command) {
            SoundcoreCommands.REQUEST_STATE -> {
                val c = conn?.takeIf { it.isOpen }
                if (c != null && push.body.isNotEmpty()) applyModelState(c, push.body)
                return null
            }
            SoundcoreCommands.PUSH_BATTERY -> before?.let { s ->
                SoundcoreStateReader.batteryPush(model, push.body)?.let { p -> s.withBattery(p.left, p.right, p.case, p.overall) }
            }
            SoundcoreCommands.PUSH_CHARGING -> before?.withCharging(SoundcoreStateReader.chargingPush(push.body))
            SoundcoreCommands.PUSH_SOUND_MODES -> {
                val codec = before?.modes?.codec
                if (before != null && codec != null && push.body.size == codec.size) before.withModeBlock(SoundModes(codec, push.body)) else null
            }
            else -> null
        }
        next ?: return null
        latest = next
        return SoundcoreModelMapping.apply(current, next, now = 0)
    }

    private suspend fun writeModel(build: (SoundcoreState) -> SoundcorePacket): HeadphoneState {
        requireOpen()
        val before = latest ?: throw HeadphoneCommandException("The earbuds haven't reported their state yet")
        val packet = try {
            build(before)
        } catch (e: SoundcoreWriteRefused) {
            throw HeadphoneCommandException(e.message ?: "unsupported", e)
        }
        c().request(packet.command, packet.body)
        readState(c())
        if (!took(before, packet)) {
            delay(500L)
            readState(c())
            if (!took(before, packet)) throw HeadphoneCommandException("The earbuds acknowledged that but didn't change")
        }
        return state
    }

    private fun took(before: SoundcoreState, packet: SoundcorePacket): Boolean =
        latest?.let { SoundcoreWrites.confirms(before, it, packet) } == true

    override suspend fun setMode(index: Int): HeadphoneState {
        val mode = SoundcoreModelMapping.ambientFor(index)
            ?: throw HeadphoneCommandException("These earbuds have three modes: noise cancelling, transparency and normal")
        return writeModel { SoundcoreWrites.setAmbient(it, mode) }
    }

    // ---- The virtual unit -------------------------------------------------------------------

    override fun deviceModeIndex(): Int? =
        SoundModes(checkNotNull(model.layout).codec, unit.modeBlock).ambient?.let(SoundcoreModelMapping::modeIndex)

    /** The model's own battery scale, found by asking the scale itself. */
    private fun raw(percent: Int): Int = (0..255).first { model.battery.percent(it) == percent }

    override suspend fun pushBattery(percent: Int): Boolean {
        val r = raw(percent)
        unit.leftBattery = r
        unit.rightBattery = r
        unit.caseBattery = r
        checkNotNull(transport).push(SoundcorePacket(SoundcoreCommands.PUSH_BATTERY, byteArrayOf(r.toByte(), r.toByte(), r.toByte()), inbound = true))
        return true
    }

    override suspend fun pollBattery(percent: Int): HeadphoneState {
        val r = raw(percent)
        unit.leftBattery = r
        unit.rightBattery = r
        unit.caseBattery = r
        return refresh()
    }

    /** SoundcoreModelsEndToEndTest's list (never sent, even unlocked), plus a mode write of the wrong size. */
    override val forbiddenCommands: List<Int> =
        listOf(0x0B83, 0x0B85, 0x0B81, 0x0B82, 0x0189, 0x0185, 0x1089, 0x0482, 0x1482, 0x0901, 0x0381, 0x0387, 0x1881, 0xFF81)

    override val forbiddenWrites: List<Pair<String, suspend () -> Unit>>
        get() = super.forbiddenWrites + refused("06 81 with the wrong size") {
            c().request(SoundcoreCommands.SET_SOUND_MODES, ByteArray(checkNotNull(model.layout).codec.size - 1))
        }

    override fun dropLink() = checkNotNull(transport).close()
}
