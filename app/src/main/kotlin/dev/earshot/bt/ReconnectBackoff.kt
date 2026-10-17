package dev.earshot.bt

/**
 * Reconnect schedule: 1 s, 2 s, 5 s, 10 s, then 30 s between attempts.
 *
 * The first retries are quick because the common drop is the headphones hopping between two
 * multipoint sources (the RFCOMM link blips for a second); the long tail exists so a phone
 * sitting next to the open Bose app does not hammer the channel.
 */
internal object ReconnectBackoff {
    val STEPS_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

    /**
     * End of the fast schedule. Past this the loop stops unless the phone still has the
     * headphones linked for audio, in which case it drops to [SLOW_RETRY_MS] (the usual cause
     * is the Bose app holding channel 2; closing it should reconnect us without a tap).
     * Otherwise the next ACL_CONNECTED broadcast or a user tap starts over.
     */
    const val MAX_ATTEMPTS = 10

    /** Retry period once [MAX_ATTEMPTS] is spent but the headphones are still linked to the phone. */
    const val SLOW_RETRY_MS = 5 * 60_000L

    /** Delay before retry number [attempt] (0-based); clamps at the last step. */
    fun delayMs(attempt: Int): Long = STEPS_MS[attempt.coerceIn(0, STEPS_MS.lastIndex)]
}
