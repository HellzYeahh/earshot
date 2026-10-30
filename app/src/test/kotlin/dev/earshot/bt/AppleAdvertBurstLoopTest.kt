package dev.earshot.bt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The AirPods / Beats advert scanner's loop ([scanInBursts]). The scanner is started once, when
 * the headset connects; the Home banner asks for the location grant (Android 9 to 11) after that.
 * The grant must start the bursts on its own, without the headset reconnecting (integration
 * review X03), and nothing may be scanned while the access is missing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppleAdvertBurstLoopTest {

    @Test fun `a grant given after the start is picked up without a restart`() = runTest {
        var missing: String? = "the precise location permission (Android 10 and 11)"
        var bursts = 0
        val results = mutableListOf<Int>()
        val logs = mutableListOf<String>()
        val loop = launch {
            scanInBursts(
                missingAccess = { missing },
                burst = { bursts++; delay(SCAN_MS); bursts },
                onResult = { results += it },
                log = { logs += it },
                pauseMs = PAUSE_MS,
                recheckMs = RECHECK_MS,
            )
        }

        testScheduler.advanceTimeBy(45_000) // access looked at at 0, 10, 20, 30 and 40 s
        assertEquals(0, bursts, "no scan while the permission is missing")
        assertEquals(1, logs.size, "the missing permission is logged once, not at every check: $logs")

        missing = null // the user granted it from the banner
        testScheduler.advanceTimeBy(10_000) // the check at 50 s starts a burst
        assertEquals(1, bursts)
        testScheduler.advanceTimeBy(2_000) // the burst ends at 56 s
        assertEquals(listOf(1), results)
        assertEquals(2, logs.size, "the grant is logged: $logs")

        testScheduler.advanceTimeBy(PAUSE_MS + SCAN_MS) // the next burst, one period later
        assertEquals(listOf(1, 2), results)
        loop.cancel()
    }

    @Test fun `access lost again stops the bursts and each new reason is logged once`() = runTest {
        var missing: String? = null
        var bursts = 0
        val logs = mutableListOf<String>()
        val loop = launch {
            scanInBursts<Int>(
                missingAccess = { missing },
                burst = { bursts++; delay(SCAN_MS); null },
                onResult = {},
                log = { logs += it },
                pauseMs = PAUSE_MS,
                recheckMs = RECHECK_MS,
            )
        }

        testScheduler.advanceTimeBy(1_000)
        assertEquals(1, bursts)
        assertEquals(0, logs.size)

        missing = "location turned on (Android 11 and older)" // switched off from the shade
        testScheduler.advanceTimeBy(10 * PAUSE_MS)
        assertEquals(1, bursts, "no scan while location is off")
        assertEquals(1, logs.size, "logged once: $logs")

        missing = "the location permission (Android 9)"
        testScheduler.advanceTimeBy(RECHECK_MS)
        assertEquals(2, logs.size, "a different reason is logged: $logs")
        assertEquals(1, bursts)
        loop.cancel()
    }

    private companion object {
        const val SCAN_MS = 6_000L
        const val PAUSE_MS = 54_000L
        const val RECHECK_MS = 10_000L
    }
}
