package dev.earshot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.earshot.MainActivity
import dev.earshot.find.FindSide
import dev.earshot.R
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.extras.HeadphoneLabels

/**
 * Builds the persistent notification from a [HeadphoneState].
 *
 *   Bose QC Ultra · 72%
 *   Quiet · Noise cancelling 10/10
 *   [Aware] [Immersion] [ANC off]
 *
 * Buttons are favourites (fallback: the first three named slots) minus the mode already active, capped
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
        /** "72%" or, for earbuds, "L 80% · R 74%" ([HeadphoneLabels.batteryShort]). */
        val batteryText: String?,
        val isCharging: Boolean?,
        val currentModeIndex: Int?,
        val modeNames: Map<Int, String>,
        val favorites: List<Int>,
        val cancellingLevel: Int?,
        val ancEnabled: Boolean?,
        val windBlock: Boolean?,
        /** The model has listening modes at all (0.4.0: older ones and the NC 700 don't). */
        val listeningModes: Boolean = true,
        /** Noise cancelling can be switched off (the "ANC off" action only makes sense then). */
        val ancToggle: Boolean = true,
    ) {
        companion object {
            fun of(s: HeadphoneState) = Snapshot(
                status = s.connection.status,
                // The user's name for the product, without Android's "LE-" prefix of LE Audio twins.
                deviceName = HeadphoneLabels.displayName(s),
                message = s.connection.message,
                batteryPercent = s.batteryPercent,
                batteryText = HeadphoneLabels.batteryShort(s),
                isCharging = s.isCharging,
                currentModeIndex = s.currentModeIndex,
                // Only slots with a real name: a fallback like "Mode 4" or another model's presets
                // would put wrong buttons on the shade.
                modeNames = s.modes.filter { it.isConfigured }
                    .mapNotNull { m -> HeadphoneLabels.modeName(s, m.index)?.let { m.index to it } }.toMap(),
                favorites = s.favoriteModes.filter { it.isConfigured }.map { it.index },
                cancellingLevel = s.noise?.cancellingLevel,
                ancEnabled = s.noise?.ancEnabled,
                windBlock = s.noise?.windBlock,
                listeningModes = s.capabilities.listeningModes,
                ancToggle = s.capabilities.ancToggle,
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
            (snapshot.batteryText ?: snapshot.batteryPercent?.let { "$it%" })?.let { append(" · ").append(it) }
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
                var actions = 0
                for (index in modeButtons(snapshot)) {
                    val label = snapshot.modeNames[index] ?: continue
                    builder.addAction(0, label, serviceIntent(HeadphoneService.ACTION_SET_MODE, requestCode = 100 + index) {
                        putExtra(HeadphoneService.EXTRA_MODE_INDEX, index)
                    })
                    actions++
                }
                snapshot.ancEnabled?.takeIf { snapshot.ancToggle }?.let { anc ->
                    builder.addAction(0, if (anc) "ANC off" else "ANC on", serviceIntent(HeadphoneService.ACTION_TOGGLE_ANC, requestCode = 2))
                    actions++
                }
                // Android shows three actions; modes and ANC keep theirs, Find takes a free one
                // (always there for headsets without a driver, where it matters most).
                if (actions < MAX_ACTIONS) builder.addAction(0, "Find", findIntent())
            }
            ConnectionStatus.DISCONNECTED ->
                builder.addAction(0, "Connect", serviceIntent(HeadphoneService.ACTION_CONNECT, requestCode = 3))
            else -> Unit
        }
        return builder.build()
    }

    private fun connectedText(s: Snapshot): String {
        val mode = s.currentModeIndex?.takeIf { s.listeningModes }?.let { i -> s.modeNames[i] ?: if (i == 0xFF) "Custom" else null }
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
        if (!s.listeningModes) return emptyList()
        val pool = s.favorites.ifEmpty {
            listOf(ListeningMode.QUIET, ListeningMode.AWARE, ListeningMode.IMMERSION).filter { it in s.modeNames }
        }
        val max = if (s.ancEnabled != null && s.ancToggle) 2 else 3
        return pool.filter { it != s.currentModeIndex }.take(max)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Opens Tools → Find and rings both sides. An activity, not the service: the tone then starts
     * while Earshot is visible, which Android 17 requires for playback (background audio hardening).
     */
    private fun findIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_FIND, FindSide.BOTH.name)
        }
        return PendingIntent.getActivity(context, 4, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
        private const val DEFAULT_NAME = "Headphones"

        // What Android shows on a notification.
        private const val MAX_ACTIONS = 3
    }
}
