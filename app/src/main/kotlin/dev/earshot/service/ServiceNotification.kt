package dev.earshot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.earshot.MainActivity
import dev.earshot.R
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode

/**
 * Builds the persistent notification from a [HeadphoneState].
 *
 *   Bose QC Ultra · 72%
 *   Quiet · Noise cancelling 10/10
 *   [Aware] [Immersion] [ANC off]
 *
 * Buttons are favourites (fallback Quiet/Aware/Immersion) minus the mode already active, capped
 * so that with the ANC toggle there are never more than three: Android's standard template shows
 * at most three actions and silently drops the rest.
 */
class ServiceNotification(private val context: Context) {

    /** The fields that change what the notification looks like; used to skip no-op updates. */
    data class Snapshot(
        val status: ConnectionStatus,
        val deviceName: String?,
        val message: String?,
        val batteryPercent: Int?,
        val isCharging: Boolean?,
        val currentModeIndex: Int?,
        val modeNames: Map<Int, String>,
        val favorites: List<Int>,
        val cancellingLevel: Int?,
        val ancEnabled: Boolean?,
        val windBlock: Boolean?,
    ) {
        companion object {
            fun of(s: HeadphoneState) = Snapshot(
                status = s.connection.status,
                deviceName = s.connection.deviceName,
                message = s.connection.message,
                batteryPercent = s.batteryPercent,
                isCharging = s.isCharging,
                currentModeIndex = s.currentModeIndex,
                modeNames = s.modes.filter { it.isConfigured }.associate { it.index to it.name },
                favorites = s.favoriteModes.filter { it.isConfigured }.map { it.index },
                cancellingLevel = s.noise?.cancellingLevel,
                ancEnabled = s.noise?.ancEnabled,
                windBlock = s.noise?.windBlock,
            )
        }
    }

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // LOW: visible in the shade, no sound, no heads-up. The user chose to see it; it must
        // never interrupt.
        val channel = NotificationChannel(CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Headphone status and quick controls"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(snapshot: Snapshot): Notification {
        val title = buildString {
            append(snapshot.deviceName?.takeIf { it.isNotBlank() }?.let(::shortName) ?: DEFAULT_NAME)
            snapshot.batteryPercent?.let { append(" · ").append(it).append('%') }
            if (snapshot.isCharging == true) append(" ⚡")
        }
        val text = when (snapshot.status) {
            ConnectionStatus.CONNECTED -> connectedText(snapshot)
            ConnectionStatus.CONNECTING -> "Connecting…"
            ConnectionStatus.DISCONNECTED -> snapshot.message ?: "Disconnected"
            ConnectionStatus.NO_PERMISSION -> snapshot.message ?: "Bluetooth permission needed"
            ConnectionStatus.NO_PAIRED_DEVICE -> snapshot.message ?: "No headphones paired"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_mode)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (snapshot.status) {
            ConnectionStatus.CONNECTED -> {
                for (index in modeButtons(snapshot)) {
                    val label = snapshot.modeNames[index] ?: DEFAULT_MODE_NAMES[index] ?: "Mode $index"
                    builder.addAction(0, label, serviceIntent(HeadphoneService.ACTION_SET_MODE, requestCode = 100 + index) {
                        putExtra(HeadphoneService.EXTRA_MODE_INDEX, index)
                    })
                }
                snapshot.ancEnabled?.let { anc ->
                    builder.addAction(0, if (anc) "ANC off" else "ANC on", serviceIntent(HeadphoneService.ACTION_TOGGLE_ANC, requestCode = 2))
                }
            }
            ConnectionStatus.DISCONNECTED ->
                builder.addAction(0, "Connect", serviceIntent(HeadphoneService.ACTION_CONNECT, requestCode = 3))
            else -> Unit
        }
        return builder.build()
    }

    private fun connectedText(s: Snapshot): String {
        val mode = s.currentModeIndex?.let { i -> s.modeNames[i] ?: if (i == 0xFF) "Custom" else "Mode $i" }
        val noise = when {
            s.ancEnabled == false -> "ANC off"
            s.windBlock == true -> "Wind block"
            s.cancellingLevel != null -> "Noise cancelling ${s.cancellingLevel}/10"
            else -> null
        }
        return listOfNotNull(mode, noise).joinToString(" · ").ifEmpty { "Connected" }
    }

    /**
     * Favourites (or the first three slots that exist: Quiet/Aware/Immersion on the QC Ultra, a
     * custom slot 2 on the QC45), minus the active one, at most two when ANC is shown.
     */
    private fun modeButtons(s: Snapshot): List<Int> {
        val pool = s.favorites.ifEmpty {
            listOf(ListeningMode.QUIET, ListeningMode.AWARE, ListeningMode.IMMERSION).filter { s.modeNames.isEmpty() || it in s.modeNames }
        }
        val max = if (s.ancEnabled != null) 2 else 3
        return pool.filter { it != s.currentModeIndex }.take(max)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun serviceIntent(action: String, requestCode: Int, extras: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(context, HeadphoneService::class.java).setAction(action).apply(extras)
        // getForegroundService: the tap may arrive with the app in the background, where a
        // plain getService() would be refused on Android 8+.
        return PendingIntent.getForegroundService(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** "Bose QC Ultra Headphones" is too long next to a battery figure. */
    private fun shortName(name: String): String =
        name.replace(" Headphones", "", ignoreCase = true).trim().ifEmpty { DEFAULT_NAME }

    companion object {
        const val CHANNEL_ID = "connection"
        const val NOTIFICATION_ID = 1001
        private const val DEFAULT_NAME = "Bose headphones"

        /** Presets to fall back on when nothing is marked favourite. */
        val DEFAULT_MODE_NAMES: Map<Int, String> = ListeningMode.PRESET_NAMES.withIndex().associate { it.index to it.value }
    }
}
