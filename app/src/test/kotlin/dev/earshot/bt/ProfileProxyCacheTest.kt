package dev.earshot.bt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The held profile proxies behind DeviceFinder (ST2). The connect loop, the session-end check and
 * the presence service decide on these answers, so each test pins one rule that keeps them equal
 * to a fresh bind per lookup, the way DeviceFinder worked before 1.0.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileProxyCacheTest {

    private class FakeProxy(val id: Int) {
        override fun toString() = "proxy$id"
    }

    /** One `getProfileProxy` call the fake saw. */
    private class Open(val profile: Int, val events: ProfileProxyEvents<FakeProxy>, val proxy: FakeProxy)

    private class FakeSource : ProfileProxySource<FakeProxy> {
        val opens = mutableListOf<Open>()
        var openCalls = 0
        val closed = mutableListOf<FakeProxy>()

        /** Every proxy asked for its connected devices, in order. */
        val asked = mutableListOf<FakeProxy>()

        /** Answer onServiceConnected from inside getProfileProxy (most tests); false = later, by [connect]. */
        var answerAtOnce = true
        val refused = mutableSetOf<Int>()
        var throwOnOpen = false

        /** Some Android versions report onServiceDisconnected from inside closeProfileProxy. */
        var closeReportsDisconnect = false
        var answer: (FakeProxy) -> Set<String>? = { setOf(HEADSET_ADDRESS) }

        override fun open(profile: Int, events: ProfileProxyEvents<FakeProxy>): Boolean {
            openCalls++
            if (throwOnOpen) throw IllegalStateException("Bluetooth service not ready")
            if (profile in refused) return false
            val open = Open(profile, events, FakeProxy(opens.size))
            opens += open
            if (answerAtOnce) events.connected(open.proxy)
            return true
        }

        fun connect(index: Int) = opens[index].let { it.events.connected(it.proxy) }
        fun disconnect(index: Int) = opens[index].events.disconnected()

        override fun close(profile: Int, proxy: FakeProxy) {
            closed += proxy
            if (closeReportsDisconnect) opens.first { it.proxy === proxy }.events.disconnected()
        }

        override fun connectedAddresses(proxy: FakeProxy): Set<String>? {
            asked += proxy
            return answer(proxy)
        }
    }

    private val source = FakeSource()
    private val cache = ProfileProxyCache(source, TIMEOUT)

    @Test
    fun `a profile is bound once and the held proxy is asked on every lookup`() = runTest {
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(A2DP))
        source.answer = { emptySet() }
        assertEquals(emptySet<String>(), cache.connectedAddresses(A2DP), "every lookup reads the current state")
        assertEquals(1, source.opens.size)
        assertEquals(2, source.asked.size)
        assertTrue(source.closed.isEmpty(), "the proxy is kept, not closed after each lookup")
    }

    @Test
    fun `profiles are held separately`() = runTest {
        source.answer = { proxy -> if (proxy.id == 0) setOf("A") else setOf("B") }
        assertEquals(setOf("A"), cache.connectedAddresses(A2DP))
        assertEquals(setOf("B"), cache.connectedAddresses(HEADSET))
        assertEquals(setOf("A"), cache.connectedAddresses(A2DP))
        assertEquals(listOf(A2DP, HEADSET), source.opens.map { it.profile })
    }

    @Test
    fun `lookups during a slow bind share it`() = runTest {
        source.answerAtOnce = false
        val first = async { cache.connectedAddresses(A2DP) }
        val second = async { cache.connectedAddresses(A2DP) }
        runCurrent()
        advanceTimeBy(1_000)
        source.connect(0)
        assertEquals(setOf(HEADSET_ADDRESS), first.await())
        assertEquals(setOf(HEADSET_ADDRESS), second.await())
        assertEquals(1, source.opens.size)
    }

    @Test
    fun `a bind that does not answer in time gives null and the next lookup binds afresh`() = runTest {
        source.answerAtOnce = false
        assertNull(cache.connectedAddresses(A2DP))
        assertEquals(TIMEOUT, currentTime, "the first bind keeps the old per-lookup timeout")
        assertNull(cache.connectedAddresses(A2DP))
        assertEquals(2, source.opens.size, "as before 1.0, a timed-out lookup is followed by a fresh bind")
    }

    @Test
    fun `a late answer is kept and a spare bind is closed`() = runTest {
        source.answerAtOnce = false
        assertNull(cache.connectedAddresses(A2DP)) // bind 0 timed out
        val waiting = async { cache.connectedAddresses(A2DP) } // bind 1
        runCurrent()
        source.connect(0) // bind 0 answers late
        assertEquals(setOf(HEADSET_ADDRESS), waiting.await(), "the waiting lookup takes the late proxy")
        source.connect(1)
        assertEquals(listOf(source.opens[1].proxy), source.closed, "one proxy per profile")
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(A2DP))
        assertEquals(2, source.opens.size)
    }

    @Test
    fun `a refused profile is remembered until Bluetooth restarts`() = runTest {
        source.refused += LE_AUDIO
        assertNull(cache.connectedAddresses(LE_AUDIO))
        assertNull(cache.connectedAddresses(LE_AUDIO))
        assertEquals(1, source.openCalls)
        assertEquals(0L, currentTime, "a refusal never waits for the timeout")

        cache.bluetoothOn()
        source.refused.clear()
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(LE_AUDIO))
        assertEquals(2, source.openCalls)
    }

    @Test
    fun `Bluetooth off also forgets a refusal`() = runTest {
        source.refused += LE_AUDIO
        assertNull(cache.connectedAddresses(LE_AUDIO))
        cache.bluetoothOff()
        source.refused.clear()
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(LE_AUDIO))
    }

    @Test
    fun `a throw from getProfileProxy is not remembered`() = runTest {
        source.throwOnOpen = true
        assertNull(cache.connectedAddresses(A2DP))
        assertNull(cache.connectedAddresses(A2DP))
        assertEquals(2, source.openCalls, "tried again, as every lookup did")
        source.throwOnOpen = false
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(A2DP))
    }

    @Test
    fun `a service disconnect drops and closes the proxy and the next lookup binds afresh`() = runTest {
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(HEADSET))
        source.disconnect(0)
        assertEquals(listOf(source.opens[0].proxy), source.closed)
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(HEADSET))
        assertEquals(2, source.opens.size)
        assertEquals(source.opens.map { it.proxy }, source.asked, "the closed proxy is never asked again")
    }

    @Test
    fun `a closed proxy that calls back again is ignored`() = runTest {
        cache.connectedAddresses(A2DP)
        source.disconnect(0)
        source.connect(0) // a stack that rebinds a proxy we closed
        source.answer = { proxy -> if (proxy.id == 0) emptySet() else setOf(HEADSET_ADDRESS) }
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(A2DP))
        assertEquals(2, source.opens.size, "a fresh bind, not the closed proxy")
    }

    @Test
    fun `Bluetooth off closes held proxies and releases waiting lookups at once`() = runTest {
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(A2DP))
        source.answerAtOnce = false
        val waiting = async { cache.connectedAddresses(HEADSET) }
        runCurrent()
        cache.bluetoothOff()
        assertNull(waiting.await())
        assertEquals(0L, currentTime, "no wait for the timeout once Bluetooth is off")
        assertEquals(listOf(source.opens[0].proxy), source.closed)

        source.answerAtOnce = true
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(A2DP))
        assertEquals(3, source.opens.size, "A2DP is bound afresh after Bluetooth comes back")
    }

    @Test
    fun `a bind still out when Bluetooth went off is taken when it answers`() = runTest {
        source.answerAtOnce = false
        val waiting = async { cache.connectedAddresses(HEADSET) }
        runCurrent()
        cache.bluetoothOff()
        assertNull(waiting.await())
        source.connect(0) // Bluetooth is back and the old bind finally answers
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(HEADSET))
        assertEquals(1, source.opens.size)
    }

    @Test
    fun `a permission error answers null and keeps the proxy`() = runTest {
        source.answer = { null }
        assertNull(cache.connectedAddresses(A2DP))
        source.answer = { setOf(HEADSET_ADDRESS) }
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(A2DP))
        assertEquals(1, source.opens.size)
        assertTrue(source.closed.isEmpty())
    }

    @Test
    fun `a proxy that fails to answer is dropped and bound afresh`() = runTest {
        source.answer = { error("dead proxy") }
        assertNull(cache.connectedAddresses(A2DP))
        assertEquals(listOf(source.opens[0].proxy), source.closed)
        source.answer = { setOf(HEADSET_ADDRESS) }
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(A2DP))
        assertEquals(2, source.opens.size)
    }

    @Test
    fun `an answer from a proxy dropped while it was asked counts as unknown`() = runTest {
        cache.connectedAddresses(A2DP)
        source.answer = {
            cache.bluetoothOff() // the main thread drops the proxy mid-call
            emptySet() // what Android answers without a service
        }
        assertNull(cache.connectedAddresses(A2DP), "not a false \"nothing connected\"")
    }

    @Test
    fun `a disconnect before the bind answered gives null and the next lookup binds afresh`() = runTest {
        source.answerAtOnce = false
        val waiting = async { cache.connectedAddresses(A2DP) }
        runCurrent()
        source.disconnect(0)
        assertNull(waiting.await())
        assertEquals(0L, currentTime)
        source.answerAtOnce = true
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(A2DP))
        assertEquals(2, source.opens.size)
    }

    @Test
    fun `a close that reports a disconnect from inside is harmless`() = runTest {
        source.closeReportsDisconnect = true
        cache.connectedAddresses(A2DP)
        source.disconnect(0)
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(A2DP))
        cache.bluetoothOff()
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(A2DP))
        assertEquals(3, source.opens.size)
        assertEquals(2, source.closed.size)
    }

    @Test
    fun `prime starts the bind without waiting and a lookup then uses it`() = runTest {
        source.answerAtOnce = false
        cache.prime(A2DP)
        cache.prime(A2DP)
        assertEquals(1, source.opens.size, "one bind however often it is primed")
        source.connect(0)
        assertEquals(setOf(HEADSET_ADDRESS), cache.connectedAddresses(A2DP))
        assertEquals(1, source.opens.size)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `prime does not retry a refused profile`() = runTest {
        source.refused += LE_AUDIO
        cache.prime(LE_AUDIO)
        cache.prime(LE_AUDIO)
        assertNull(cache.connectedAddresses(LE_AUDIO))
        assertEquals(1, source.openCalls)
        assertFalse(source.opens.any { it.profile == LE_AUDIO })
    }

    private companion object {
        const val TIMEOUT = 1_500L
        const val A2DP = 2
        const val HEADSET = 1
        const val LE_AUDIO = 22
        const val HEADSET_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
