/*
 * QuietRebellionService.kt – ForegroundService that keeps the BMAP connection alive in the background.
 * Shows a persistent notification with device name, battery, current mode, and ANC toggle.
 * Equivalent to the Windows tray icon.
 */
package net.quietrebellion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG             = "QuietRebellion"
private const val CHANNEL_ID      = "quiet_rebellion_main"
private const val NOTIF_ID        = 1
const val ACTION_TOGGLE_ANC       = "net.quietrebellion.TOGGLE_ANC"
const val ACTION_NEXT_MODE        = "net.quietrebellion.NEXT_MODE"
const val ACTION_POWER_OFF        = "net.quietrebellion.POWER_OFF"

class QuietRebellionService : Service() {

    inner class LocalBinder : Binder() {
        val service get() = this@QuietRebellionService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ── State ─────────────────────────────────────────────────────────────────

    var conn: BoseConnection? = null
        private set

    private var deviceName  = ""
    private var battery     = -1
    private var modeName    = ""
    private var ancEnabled  = false
    private var modeNames   = mapOf<Int, String>()
    private var modeIndex   = -1
    private var favorites   = setOf<Int>()
    private var sourceName  = ""  // currently streaming device name

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Set to true while MainActivity is bound so the service skips its own poll.
    var clientBound = false
    private var pollJob: Job? = null
    // Screen state – initialised from PowerManager in onCreate, kept in sync via broadcast.
    private var screenOn = true
    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        screenOn = (getSystemService(PowerManager::class.java)).isInteractive
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        registerReceiver(actionReceiver, IntentFilter().apply {
            addAction(ACTION_TOGGLE_ANC)
            addAction(ACTION_NEXT_MODE)
            addAction(ACTION_POWER_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        conn?.close()
        conn = null
        unregisterReceiver(actionReceiver)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    // ── Public API ────────────────────────────────────────────────────────────

    fun attach(
        connection: BoseConnection,
        name: String,
        bat: Int,
        mode: Int,
        modes: Map<Int, String>,
        anc: Boolean,
        favs: Set<Int> = emptySet(),
        source: String = "",
    ) {
        conn        = connection
        deviceName  = name
        battery     = bat
        modeIndex   = mode
        modeNames   = modes
        modeName    = QcUltra2.modeDisplayName(mode, modes)
        ancEnabled  = anc
        favorites   = favs
        sourceName  = source
        updateNotification()
        startPolling()
    }


    fun detach() {
        pollJob?.cancel(); pollJob = null
        conn = null
        deviceName = ""
        battery = -1
        modeName = ""
        updateNotification()
    }

    // ── Background polling ────────────────────────────────────────────────────

    private fun startPolling() {
        pollJob?.cancel()
        // Polls battery+mode every 30s when app is backgrounded and screen is on.
        // Ceiling: changes appear up to 30s late in notification/widget.
        // Upgrade: unsolicited BMAP STATUS listener if device sends them.
        pollJob = scope.launch {
            while (true) {
                delay(30_000)
                if (clientBound || !screenOn) continue  // MainActivity polls while open; no poll with screen off
                val c = conn ?: break
                try {
                    val bat  = c.battery()
                    val mode = c.modeIndex()
                    if (bat != battery || mode != modeIndex) {
                        battery   = bat
                        modeIndex = mode
                        modeName = QcUltra2.modeDisplayName(modeIndex, modeNames)
                        updateNotification()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "background poll failed: ${e.message}")
                }
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ancIntent = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_TOGGLE_ANC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val modeIntent = PendingIntent.getBroadcast(
            this, 2, Intent(ACTION_NEXT_MODE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val powerIntent = PendingIntent.getBroadcast(
            this, 3, Intent(ACTION_POWER_OFF),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (deviceName.isNotEmpty())
            "$deviceName${if (battery >= 0) " · $battery%" else ""}"
        else
            getString(R.string.notif_title_disconnected)

        // Subtitle: source device + current mode + ANC state
        val ancState = if (ancEnabled) getString(R.string.str_anc_on) else getString(R.string.str_anc_off)
        val text = listOfNotNull(
            sourceName.ifEmpty { null },
            modeName.ifEmpty { null },
            ancState,
        ).joinToString(" · ")

        // "Next Mode" label shows next mode name if favorites are set
        val cycleModes = if (favorites.isNotEmpty())
            modeNames.keys.filter { it in favorites }.sorted()
        else
            modeNames.keys.sorted()
        val nextModeIdx = cycleModes.let { keys ->
            if (keys.isEmpty()) -1
            else keys[(keys.indexOf(modeIndex) + 1) % keys.size]
        }
        val nextModeName = if (nextModeIdx >= 0) QcUltra2.modeDisplayName(nextModeIdx, modeNames) else getString(R.string.str_mode_fallback)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, if (ancEnabled) getString(R.string.str_notif_anc_off_action) else getString(R.string.str_notif_anc_on_action), ancIntent)
            .addAction(0, getString(R.string.str_notif_switch_mode, nextModeName), modeIntent)
            .addAction(0, getString(R.string.str_notif_power_off), powerIntent)
            .build()
    }

    fun updateNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification())
    }

    // ── Broadcast receiver for notification actions ───────────────────────────

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON  -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
            val c = conn ?: return
            when (intent.action) {
                ACTION_TOGGLE_ANC -> scope.launch {
                    try {
                        ancEnabled = !ancEnabled
                        c.setAnc(ancEnabled)
                        updateNotification()
                    } catch (e: Exception) {
                        Log.w(TAG, "ANC toggle failed: ${e.message}")
                        ancEnabled = !ancEnabled  // revert
                    }
                }
                ACTION_NEXT_MODE -> scope.launch {
                    try {
                        // cycle favorites only; fall back to all modes
                        val pool = if (favorites.isNotEmpty())
                            modeNames.keys.filter { it in favorites }.sorted()
                        else
                            modeNames.keys.sorted()
                        if (pool.isEmpty()) return@launch
                        val next = pool[(pool.indexOf(modeIndex) + 1) % pool.size]
                        c.setModeByIndex(next)
                        modeIndex = next
                        modeName = QcUltra2.modeDisplayName(next, modeNames)
                        updateNotification()
                    } catch (e: Exception) {
                        Log.w(TAG, "Mode switch failed: ${e.message}")
                    }
                }
                ACTION_POWER_OFF -> scope.launch {
                    try {
                        c.powerOff()
                    } catch (e: Exception) {
                        Log.w(TAG, "Power off failed: ${e.message}")
                    }
                    // connection will drop — clean up
                    detach()
                }
            }
        }
    }
}

