package dev.earshot.conformance

import dev.earshot.bt.BmapConnection
import dev.earshot.bt.BoseLinkFactory
import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.ManagedLink
import dev.earshot.bt.ReplyMode
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.BmapException
import dev.earshot.protocol.BmapFrameBuffer
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.qcultra2.QcUltra2
import dev.earshot.service.QcUltra2Protocol
import dev.earshot.service.toCommandException
import dev.earshot.virtual.DemoDevice
import dev.earshot.virtual.VirtualProfile
import dev.earshot.virtual.VirtualQcUltra2
import dev.earshot.virtual.VirtualTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlin.random.Random

/**
 * A Bose BMAP product: the real [BmapConnection] over a [VirtualTransport] to one [VirtualQcUltra2]
 * playing [profile], and one [QcUltra2Protocol] kept across connections (AppContainer keeps a single
 * one; `onConnected` resets the per-connection facts). The steps are `ServiceHeadphoneRepository`'s:
 * `onConnected` then `refresh`, each a command; pushes parked while a command runs and merged after
 * it; a [BmapException] turned into the command exception the repository throws.
 *
 * Determinism: the transport gets a seeded [Random] and the unit and the connection the test clock.
 * The unit has no battery setter (and none is added: it backs demo mode), so a battery change comes
 * from its own drain, made fast enough to step through with the virtual clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoseRig(
    test: TestScope,
    override val demo: DemoDevice,
    private val profile: VirtualProfile,
    private val seed: Int,
) : RigBase<BmapPacket>(test, profile.productName) {

    override val family = DeviceFamily.BOSE
    override val factory = BoseLinkFactory()

    val device = VirtualQcUltra2(
        clock = { test.testScheduler.currentTime },
        batteryDrainIntervalMs = DRAIN_MS,
        batteryPushIntervalMs = PUSH_MS,
        profile = profile,
    )

    private val protocol = QcUltra2Protocol()

    private var transport: VirtualTransport? = null
    private var conn: BmapConnection? = null
    override val connection: ManagedLink? get() = conn
    override val parksPushesDuringCommands = true

    override val notApplicable: Map<Check, String> =
        if (profile.legacyOnly) mapOf(Check.BATTERY_PUSH to "Bose Connect era: no [9.2] subscription, the repository polls [2.2] (livePushEnabled = false)")
        else emptyMap()

    override suspend fun connect(): HeadphoneState {
        device.powerOn() // what VirtualTransport.factory does for each demo connection
        val t = VirtualTransport(device, replyDelayMs = 5L..15L, chunkGapMs = 10L, tickIntervalMs = TICK_MS, random = Random(seed))
        val spy = SpyTransport(t)
        val c = BmapConnection(spy, test.backgroundScope, minWriteGapMs = 0, clock = { test.testScheduler.currentTime })
        transport = t
        conn = c
        beginSession(spy)
        collectPushes(c.unsolicited)
        runCurrent()
        bose { s -> protocol.onConnected(c, s) }
        return bose { s -> protocol.refresh(c, s) }
    }

    private fun c(): BmapConnection = checkNotNull(conn)

    /** The repository's command wrapper: ERROR frames become the user-readable exception. */
    private suspend fun bose(block: suspend (HeadphoneState) -> HeadphoneState): HeadphoneState = command { s ->
        try {
            block(s)
        } catch (e: BmapException) {
            throw e.toCommandException()
        }
    }

    override suspend fun applyPush(push: BmapPacket, current: HeadphoneState): HeadphoneState? = protocol.onUnsolicited(push, current)

    override suspend fun refresh() = bose { s -> protocol.refresh(c(), s) }

    override suspend fun setMode(index: Int) = bose { s -> protocol.setMode(c(), s, index) }

    override fun deviceModeIndex(): Int? = device.currentMode.takeIf { device.slotCount > 0 }

    /**
     * Steps the unit's own drain one interval at a time until the pushed `[2.2]` brings the state
     * to [percent]: the headline is the unit's figure (headphones) or the combined bud record
     * (earbuds), so the rig follows the state rather than predicting the record.
     */
    override suspend fun pushBattery(percent: Int): Boolean {
        if (!state.livePushEnabled) return false
        repeat(MAX_DRAIN_STEPS) {
            if (state.batteryPercent == percent) return true
            test.testScheduler.advanceTimeBy(DRAIN_MS)
            settle()
        }
        return true
    }

    override suspend fun pollBattery(percent: Int): HeadphoneState {
        repeat(MAX_DRAIN_STEPS) {
            bose { s -> protocol.pollBattery(c(), s) }
            if (state.batteryPercent == percent) return state
            test.testScheduler.advanceTimeBy(DRAIN_MS)
            runCurrent()
        }
        return state
    }

    /** bt/WriteGuard's refusals: firmware update and authentication blocks, factory default, ship mode, reset, clear device list. */
    override val forbiddenWrites: List<Pair<String, suspend () -> Unit>> = listOf(
        BmapPacket.start(FunctionAddress(3, 2)),
        BmapPacket.setGet(FunctionAddress(3, 4), byteArrayOf(0)),
        BmapPacket.start(FunctionAddress(18, 19)),
        BmapPacket.start(FunctionAddress(7, 5)),
        BmapPacket.start(FunctionAddress(7, 6)),
        BmapPacket.start(FunctionAddress(7, 11)),
        BmapPacket.start(FunctionAddress(4, 7)),
    ).map { p -> refused("$p") { c().request(p, ReplyMode.SINGLE, 1_000L) } } +
        refused("[7.5] through send()") { c().send(BmapPacket.start(FunctionAddress(7, 5))) }

    /**
     * GET is a read. So is START with no payload on `[31.6]` and `[31.1]`: the refresh dumps the mode
     * slots that way (QcUltra2Protocol.readModeConfigs), and neither changes anything.
     */
    override fun decode(bytes: ByteArray): List<Sent> = BmapFrameBuffer().feed(bytes).map { p ->
        val dump = p.operator == BmapOperator.START && p.payload.isEmpty() &&
            (p.matches(QcUltra2.MODE_CONFIG) || p.matches(QcUltra2.MODES_GET_ALL))
        Sent("$p", p.operator == BmapOperator.GET || dump)
    }

    override fun dropLink() = checkNotNull(transport).close()

    /** `[9.2]` was written on this connection and the unit (which forgets it per session) has it again. */
    override fun subscribed(): Boolean? {
        if (profile.legacyOnly) return null
        val written = BmapFrameBuffer().feed(spy.all()).any { it.matches(QcUltra2.NOTIFY_BY_FBLOCK) && it.operator == BmapOperator.SET_GET }
        return written && device.notifyBlocks.isNotEmpty()
    }

    companion object {
        const val TICK_MS = 250L

        /** One percent a minute: two refreshes take a few virtual seconds, so they never see a drain step. */
        const val DRAIN_MS = 60_000L
        const val PUSH_MS = 1_000L
        private const val MAX_DRAIN_STEPS = 60
    }
}
