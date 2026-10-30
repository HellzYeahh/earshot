package dev.earshot.sleep

import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState

/**
 * When the Sleep runtime opens Earshot's control link itself, so the morning restore can put the
 * headset back (audit X18).
 *
 * The headset half of a restore (touch lock, prompts, noise cancelling) runs only on that link
 * ([SleepRepositoryImpl] watches for it). With auto-connect off nothing else opens it when the
 * headset comes back in the morning: the link-up receiver, the presence service and the headset
 * service all stand down, and the app's own connect runs once per process (`ui.AutoConnectOnce`),
 * so a process that lived through the night doesn't connect when the app is opened again. The
 * headset would keep its touch lock until the user happened to tap Connect.
 *
 * So the restore connects once, whatever auto-connect says: it carries out the night the user set
 * up, as the reconnect before an earbud alarm already does. Only once ([RestoreRecord.connectTried]),
 * so a refused command or a later Disconnect never becomes a reconnect loop; only once the morning
 * has come, since the night's changes stay on until then even if the headset links again at 2 am;
 * and only while the headset is linked for audio, so a headset that is away is never paged.
 */
internal object RestoreConnect {
    /**
     * The restore for [address] that may still have its connect, or null: no night runs, the
     * morning has come for it (tonight's once due, or one left from an earlier night), it has a
     * headset part, and it hasn't had its connect yet.
     */
    fun waitingFor(address: String, night: NightRecord): RestoreRecord? {
        if (night.active != null) return null
        val tonight = night.restore
        // Tonight's changes to that headset stay until the morning: nothing connects for them, nor
        // for an older restore of the same headset, which would undo them.
        if (tonight != null && !tonight.due && tonight.address.equals(address, ignoreCase = true)) return null
        val waiting = listOfNotNull(tonight?.takeIf { it.due }) + night.pending
        return waiting.firstOrNull { it.headsetPart && !it.connectTried && it.address.equals(address, ignoreCase = true) }
    }

    /**
     * True when [s] shows such a headset linked for audio while Earshot's control link is down
     * (not connecting, not refused for a missing permission).
     */
    fun shouldConnect(s: HeadphoneState, night: NightRecord): Boolean {
        val address = s.connection.deviceAddress ?: return false
        if (s.connection.status != ConnectionStatus.DISCONNECTED || s.connection.audioLinked != true) return false
        return waitingFor(address, night) != null
    }

    /** [night] with every restore for [address] marked as having had its connect. */
    fun markTried(night: NightRecord, address: String): NightRecord {
        fun mark(r: RestoreRecord) = if (r.address.equals(address, ignoreCase = true)) r.copy(connectTried = true) else r
        return night.copy(restore = night.restore?.let { mark(it) }, pending = night.pending.map { mark(it) })
    }

    /**
     * A headset the night's end action has just disconnected or switched off: the restore connect
     * must not reverse that. For a moment after the disconnect Android still reports the audio link
     * up, and a restore that is due at once (the morning time passed during the night, so it runs
     * straight after the end action) or the headset watcher would connect the headset straight
     * back. So nothing connects for [address] until it has been seen unlinked for audio, or until
     * [untilMillis]. The connect isn't used up meanwhile ([markTried] waits): when the headset links
     * again in the morning it still gets its one connect.
     */
    data class Hold(val address: String, val untilMillis: Long)

    /** How long a [Hold] lasts at most: far longer than a disconnect takes to reach the radio. */
    const val HOLD_MS = 2 * 60_000L

    fun isHeld(hold: Hold?, address: String, now: Long): Boolean =
        hold != null && now < hold.untilMillis && hold.address.equals(address, ignoreCase = true)

    /** [hold] after [s] was seen: gone once its headset shows unlinked for audio, or once it expired. */
    fun release(hold: Hold?, s: HeadphoneState, now: Long): Hold? {
        if (hold == null || now >= hold.untilMillis) return null
        val gone = s.connection.audioLinked == false && hold.address.equals(s.connection.deviceAddress, ignoreCase = true)
        return if (gone) null else hold
    }
}
