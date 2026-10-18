package dev.earshot.bt

import dev.earshot.bt.BmapCandidate.Channel
import dev.earshot.bt.BmapCandidate.SdpBmap
import dev.earshot.bt.BmapCandidate.SdpSpp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The walk over BMAP channel candidates within one connect attempt, with fake sockets per channel. */
@OptIn(ExperimentalCoroutinesApi::class)
class BmapChannelSearchTest {

    @Before
    fun silenceLog() {
        BtLog.sink = { _, _, _ -> }
    }

    private val all = BmapCandidates.order(null, null) // 2, 8, 9, SDP SPP, SDP BMAP
    private val opened = LinkedHashMap<BmapCandidate, ScriptedTransport>()

    private fun TestScope.search(budgetMs: Long = BmapChannelSearch.BUDGET_MS) = BmapChannelSearch(
        budgetMs = budgetMs,
        clock = { testScheduler.currentTime },
        connect = { t, s -> BmapConnection(t, s, minWriteGapMs = 0) },
    )

    /** [sockets] says what each candidate is; a missing one refuses the connection. */
    private suspend fun TestScope.run(sockets: Map<BmapCandidate, () -> ScriptedTransport>, budgetMs: Long = BmapChannelSearch.BUDGET_MS) =
        search(budgetMs).find(all, backgroundScope) { candidate ->
            val make = sockets[candidate] ?: throw RfcommConnectException("$candidate: connection refused", looksBusy = false)
            make().also { opened[candidate] = it }
        }

    @Test
    fun `a prince unit with BMAP on channel 9 is found past a beacon and a refusal`() = runTest {
        val found = run(mapOf(
            Channel(2) to { ScriptedTransport(answer = { ScriptedTransport.BEACON }, chatter = ScriptedTransport.BEACON) },
            Channel(9) to { ScriptedTransport.bmap() },
        ))
        assertEquals(Channel(9), found.candidate)
        assertTrue(found.connection.isOpen)
        assertFalse(opened.getValue(Channel(2)).isConnected, "the beacon socket is closed, not leaked")
        assertTrue(Channel(8) !in opened)
        assertTrue(SdpSpp !in opened, "stops at the first that answers")
    }

    @Test
    fun `the first candidate wins straight away when it answers`() = runTest {
        val found = run(mapOf(Channel(2) to { ScriptedTransport.bmap() }, Channel(8) to { ScriptedTransport.bmap() }))
        assertEquals(Channel(2), found.candidate)
        assertEquals(listOf<BmapCandidate>(Channel(2)), opened.keys.toList())
    }

    @Test
    fun `SDP is a candidate like any other and must pass the probe`() = runTest {
        val found = run(mapOf(
            SdpSpp to { ScriptedTransport(chatter = ScriptedTransport.LOG) }, // MIUI-style wrong channel
            SdpBmap to { ScriptedTransport.bmap() },
        ))
        assertEquals(SdpBmap, found.candidate)
    }

    @Test
    fun `opened but nobody answers BMAP is the busy channel`() = runTest {
        // The Bose app holds channel 2: it opens and stays silent; everything else refuses.
        val e = assertFailsWith<RfcommConnectException> { run(mapOf(Channel(2) to { ScriptedTransport() })) }
        assertTrue(e.looksBusy)
        assertTrue(opened.values.none { it.isConnected })
        assertEquals(LivenessProbe.TIMEOUT_MS, testScheduler.currentTime, "one probe for the one socket that opened")
        assertEquals(ConnectionManager.MESSAGE_CHANNEL_BUSY, SessionEnd.connectFailureMessage(e, linked = true))
    }

    @Test
    fun `the HFP channel is closed and skipped on the way to BMAP`() = runTest {
        val found = run(mapOf(
            Channel(2) to { ScriptedTransport(chatter = ScriptedTransport.HFP_OPENER) },
            Channel(9) to { ScriptedTransport.bmap() },
        ))
        assertEquals(Channel(9), found.candidate)
        assertFalse(opened.getValue(Channel(2)).isConnected, "the HFP socket is not held")
    }

    @Test
    fun `only the HFP channel opening is not reported as the Bose app holding BMAP`() = runTest {
        val e = assertFailsWith<RfcommConnectException> { run(mapOf(Channel(8) to { ScriptedTransport(chatter = ScriptedTransport.HFP_OPENER) })) }
        assertFalse(e.looksBusy)
        assertTrue("HFP" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `nothing opens at all is an ordinary failure`() = runTest {
        val e = assertFailsWith<RfcommConnectException> { run(emptyMap()) }
        assertFalse(e.looksBusy)
        assertTrue("SDP BMAP" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `a socket that drops during its probe is not the busy signature`() = runTest {
        val e = assertFailsWith<IOException> {
            run(mapOf(Channel(8) to {
                lateinit var t: ScriptedTransport
                t = ScriptedTransport(answer = { t.close(); null })
                t
            }))
        }
        assertFalse(e is RfcommConnectException && e.looksBusy, e.toString())
    }

    @Test
    fun `permission refusals everywhere surface as SecurityException`() = runTest {
        assertFailsWith<SecurityException> {
            search().find(all, backgroundScope) { throw SecurityException("BLUETOOTH_CONNECT") }
        }
    }

    @Test
    fun `the time budget stops the walk`() = runTest {
        val e = assertFailsWith<RfcommConnectException> {
            run(all.associateWith { { ScriptedTransport() } }, budgetMs = 2 * LivenessProbe.TIMEOUT_MS)
        }
        assertTrue(e.looksBusy)
        assertEquals(listOf<BmapCandidate>(Channel(2), Channel(8)), opened.keys.toList())
    }
}
