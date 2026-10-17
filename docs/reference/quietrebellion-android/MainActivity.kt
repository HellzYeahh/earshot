/*
 * MainActivity.kt – Android UI for bosectl.
 */
package net.quietrebellion

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.quietrebellion.databinding.ActivityMainBinding
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    // ── Service binding ───────────────────────────────────────────────────────

    private var boseService: QuietRebellionService? = null
    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            boseService = (binder as QuietRebellionService.LocalBinder).service
            boseService?.clientBound = true
            val existingConn = boseService?.conn
            if (existingConn != null) {
                // Back from background – service already has a live connection
                lifecycleScope.launch { fetchAndRefresh(existingConn, b.tvDeviceName.text.toString()) }
            } else {
                ensurePermissionsThenConnect()
            }
            // Polls every 15s while app is open; ceiling: ~15s staleness for battery/mode.
            pollJob = lifecycleScope.launch {
                while (true) {
                    delay(15_000)
                    if (conn != null) fetchAndRefresh()
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { boseService = null }
    }

    // conn delegates to service; falls back to null if no service yet
    private val conn: BoseConnection? get() = boseService?.conn

    // ── Bluetooth adapter ─────────────────────────────────────────────────────
    private val btAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // ── Cached state ──────────────────────────────────────────────────────────
    private var modeNames: Map<Int, String> = QcUltra2.MODE_NAMES
    private var currentModeIndex = -1
    private var audioSettings    = AudioSettings(0, SpatialMode.Off, false, false)
    private var eqBands          = listOf<EqBand>()
    private var sidetone         = 0
    private var multipoint       = false
    private var favorites        = setOf<Int>()
    private var favoritesTotalModes = 11
    private var pairedDevices     = mapOf<String, String>()
    private var activeMac: String? = null
    private var canRoute          = false
    private var autoPlayPause     = false
    private var autoAnswer        = false
    private var sidetonePersistent = true

    // populateModes: skip rebuild when modes+favorites haven't changed
    private var lastModeNamesForRadio: Map<Int, String> = emptyMap()
    private var lastFavoritesForRadio: Set<Int>         = emptySet()

    // Guards against re-entrant UI updates triggering command sends
    private var updatingUi = false

    // ── Permission launcher ───────────────────────────────────────────────────
    private var pollJob: Job? = null
    private var reconnectJob: Job? = null
    private var routeProbeJob: Job? = null
    private var sourceSwitchInFlight = false
    private var lastSourceSwitchAtMs = 0L
    private var autoReconnectSuppressed = false
    private var autoReconnectTriggeredForDrop = false
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // POST_NOTIFICATIONS is optional – only BLUETOOTH_CONNECT is required
        val btGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            grants.getOrDefault(Manifest.permission.BLUETOOTH_CONNECT, true)
        if (btGranted) startAndBindService()
        else setStatus(getString(R.string.str_bt_permission_required))
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        applyNightModePref()
        super.onCreate(savedInstanceState)
        applyDynamicColorsPref()
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        wireListeners()
        requestRequiredPermissions()
    }

    override fun onStop() {
        super.onStop()
        pollJob?.cancel(); pollJob = null
        reconnectJob?.cancel(); reconnectJob = null
        routeProbeJob?.cancel(); routeProbeJob = null
        boseService?.clientBound = false
        // Unbind but keep service running in background
        try { unbindService(serviceConn) } catch (_: Exception) {}
        boseService = null
    }

    override fun onStart() {
        super.onStart()
        // Only bind if service was already started (permission granted); startAndBindService() handles first launch.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bindService(Intent(this, QuietRebellionService::class.java), serviceConn, BIND_AUTO_CREATE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't close conn – service owns it
    }

    private fun startAndBindService() {
        val svcIntent = Intent(this, QuietRebellionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svcIntent) else startService(svcIntent)
        bindService(svcIntent, serviceConn, BIND_AUTO_CREATE)
    }

    /** Requests all required runtime permissions in one shot via permLauncher. */
    private fun requestRequiredPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        if (needed.isEmpty()) startAndBindService() else permLauncher.launch(needed)
    }


    // ── Theme / Appearance ────────────────────────────────────────────────────

    /** Called before super.onCreate() – sets night mode so DayNight themes switch correctly. */
    private fun applyNightModePref() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        // Migrate old boolean pref
        val theme = if (prefs.contains("material_you") && !prefs.contains("theme")) {
            val v = if (prefs.getBoolean("material_you", false)) "material_you" else "cyan_light"
            prefs.edit().putString("theme", v).remove("material_you").apply()
            v
        } else prefs.getString("theme", "cyan_light")

        when (theme) {
            "cyan_light", "mono_light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "cyan_dark",  "mono_dark"  -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    /** Called after super.onCreate(), before setContentView – applies the custom theme/DynamicColors. */
    private fun applyDynamicColorsPref() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        when (prefs.getString("theme", "cyan_light")) {
            "cyan_light"   -> setTheme(R.style.Theme_QuietRebellion_Cyan_Light)
            "cyan_dark"    -> setTheme(R.style.Theme_QuietRebellion_Cyan_Dark)
            "mono_light"   -> setTheme(R.style.Theme_QuietRebellion_Mono_Light)
            "mono_dark"    -> setTheme(R.style.Theme_QuietRebellion_Mono_Dark)
            "material_you" -> DynamicColors.applyToActivityIfAvailable(this)
            else           -> setTheme(R.style.Theme_QuietRebellion_Cyan_Light)
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val current = prefs.getString("theme", "cyan_light") ?: "cyan_light"
        val dp = resources.displayMetrics.density

        data class Opt(val key: String, val label: String, val fill: Int, val stroke: Int, val rainbow: Boolean = false)
        val options = listOf(
            Opt("cyan_light",   getString(R.string.str_theme_fresh_light),  0xFFF0FAFA.toInt(), 0xFF06B6D4.toInt()),
            Opt("cyan_dark",    getString(R.string.str_theme_fresh_dark),   0xFF0A1929.toInt(), 0xFF06B6D4.toInt()),
            Opt("mono_light",   getString(R.string.str_theme_mono_light),   0xFFF2F2F0.toInt(), 0xFFABABAB.toInt()),
            Opt("mono_dark",    getString(R.string.str_theme_mono_dark),    0xFF1A1A1A.toInt(), 0xFF555555.toInt()),
            Opt("material_you", getString(R.string.str_theme_material_you), 0, 0, rainbow = true),
        )

        val sheet = BottomSheetDialog(this)
        val pad = (20 * dp).toInt()
        val padH = (16 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, pad, padH, (36 * dp).toInt())
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.str_appearance)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(4, 0, 0, (16 * dp).toInt())
        })

        options.forEach { opt ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val vp = (14 * dp).toInt()
                setPadding(4, vp, 4, vp)
                isClickable = true; isFocusable = true
                val tv = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
            }

            val sz = (36 * dp).toInt()
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = (16 * dp).toInt() }
                background = if (opt.rainbow) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        gradientType = GradientDrawable.SWEEP_GRADIENT
                        setColors(intArrayOf(0xFF7C3AED.toInt(), 0xFF06B6D4.toInt(), 0xFF2BAA5A.toInt(), 0xFFE07B00.toInt(), 0xFF7C3AED.toInt()))
                    }
                } else {
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(opt.fill)
                        setStroke((2.5f * dp).toInt(), opt.stroke)
                    }
                }
            })

            row.addView(TextView(this).apply {
                text = opt.label
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            if (opt.key == current) {
                row.addView(TextView(this).apply {
                    text = "✓"
                    textSize = 18f
                    setTextColor(0xFF06B6D4.toInt())
                })
            }

            row.setOnClickListener {
                prefs.edit().putString("theme", opt.key).apply()
                sheet.dismiss()
                recreate()
            }
            root.addView(row)
        }


        sheet.setContentView(root)
        sheet.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) { showSettingsDialog(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Connection lifecycle ──────────────────────────────────────────────────

    private fun ensurePermissionsThenConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                .toTypedArray()
            if (needed.isNotEmpty()) { permLauncher.launch(needed); return }
        }
        connect()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun connect() {
        val adapter = btAdapter ?: return setStatus(getString(R.string.str_bt_not_available))

        val devices = try {
            DeviceDiscovery.bondedDevices(adapter)
        } catch (e: SecurityException) {
            ensurePermissionsThenConnect(); return
        }
        if (devices.isEmpty()) return setStatus(getString(R.string.str_no_paired_devices))

        val lastMac = getSharedPreferences("prefs", MODE_PRIVATE).getString("last_mac", null)
        val preferred = lastMac?.let { mac -> devices.firstOrNull { it.address == mac } }

        if (preferred != null) {
            connectTo(preferred)
        } else if (devices.size == 1) {
            connectTo(devices[0])
        } else {
            val names = devices.map { it.name ?: it.address }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.str_select_device))
                .setItems(names) { _, i -> connectTo(devices[i]) }
                .show()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun connectTo(device: android.bluetooth.BluetoothDevice) {
        lifecycleScope.launch {
            setStatus(getString(R.string.str_connecting_to, device.name))
            boseService?.detach()
            val connection = try {
                BoseConnection(BluetoothTransport.connectDirect(device))
            } catch (e: Exception) {
                Log.e("BoseCtl", "Connect failed: ${e::class.simpleName}: ${e.message}", e)
                scheduleConservativeReconnect(e)
                setStatus(getString(R.string.str_error_msg, userFacingErrorMessage(e))); return@launch
            }
            try {
                Log.d("BoseCtl", "Socket connected to ${device.name}")
                getSharedPreferences("prefs", MODE_PRIVATE).edit()
                    .putString("last_mac", device.address).apply()
                b.tvDeviceName.text = device.name
                fetchAndRefresh(connection, device.name)
            } catch (e: Exception) {
                // service never adopted the connection → close it to avoid socket leak
                connection.close()
                Log.e("BoseCtl", "Initial fetch failed: ${e::class.simpleName}: ${e.message}", e)
                if (isDisconnectedError(e)) boseService?.detach()
                scheduleConservativeReconnect(e)
                setStatus(getString(R.string.str_error_msg, userFacingErrorMessage(e)))
            }
        }
    }

    private suspend fun fetchAndRefresh(
        connection: BoseConnection? = conn,
        deviceName: String = b.tvDeviceName.text.toString(),
    ) {
        val c = connection ?: return
        try {
            modeNames        = c.allModeNames()
            currentModeIndex = c.modeIndex()
            audioSettings    = c.audioSettings()
            eqBands          = c.eq()
            sidetone         = c.sidetone()
            multipoint       = c.multipoint()
            val (favSet, favTotal) = try { c.favorites() } catch (_: Exception) { setOf<Int>() to 11 }
            favorites           = favSet
            favoritesTotalModes = favTotal
            val battery      = c.battery()
            val charging     = c.chargingState()
            val firmware     = try { c.firmware() } catch (_: Exception) { "" }

            val chargingIcon = if (charging == true) " ⚡" else ""
            b.tvBattery.text  = getString(R.string.str_battery, battery, chargingIcon)
            b.tvFirmware.text = if (firmware.isNotEmpty()) getString(R.string.str_firmware, firmware) else ""
            setStatus(getString(R.string.str_connected))
                autoReconnectTriggeredForDrop = false
                autoReconnectSuppressed = false

            try {
                val (_, mac) = c.source()
                activeMac     = mac
                pairedDevices = c.pairedDevicesWithNames()
                canRoute      = if (multipoint) try { c.canRoute() } catch (_: Exception) { false } else false
            } catch (_: Exception) { }

            autoPlayPause = try { c.autoPlayPause()   } catch (_: Exception) { false }
            autoAnswer    = try { c.autoAnswer()      } catch (_: Exception) { false }
            sidetonePersistent = try { c.sidetonePersistent() } catch (_: Exception) { true }

            // Attach to service so it keeps running in background
            boseService?.attach(c, deviceName, battery, currentModeIndex,
                modeNames, audioSettings.ancToggle, favorites,
                source = activeMac?.let { pairedDevices[it] } ?: "")

            updateUi()
        } catch (e: Exception) {
            if (isDisconnectedError(e)) boseService?.detach()
            scheduleConservativeReconnect(e)
            setStatus(getString(R.string.str_fetch_error, userFacingErrorMessage(e)))
        }
    }

    // ── UI population ─────────────────────────────────────────────────────────

    private fun updateUi() {
        updatingUi = true
        try {
            populateModes()
            applyCnc()
            applySpatial()
            b.swWindBlock.isChecked = audioSettings.windBlock
            applyEq()
            applySidetone()
            b.swMultipoint.isChecked = multipoint
            applyPairedDevices()
            b.swAutoPlayPause.isChecked = autoPlayPause
            b.swAutoAnswer.isChecked = autoAnswer
            b.swSidetonePersistent.isChecked = sidetonePersistent
            applyNowPlaying()        } finally {
            updatingUi = false
        }
    }

    private fun populateModes() {
        if (modeNames == lastModeNamesForRadio && favorites == lastFavoritesForRadio) {
            // Only update checked state – avoids RadioGroup flicker on every poll
            for (i in 0 until b.rgModes.childCount) {
                val rb = b.rgModes.getChildAt(i) as? RadioButton ?: continue
                rb.isChecked = (rb.tag as? Int) == currentModeIndex
            }
            return
        }
        lastModeNamesForRadio = modeNames
        lastFavoritesForRadio = favorites
        b.rgModes.removeAllViews()
        modeNames.entries.sortedBy { it.key }
            .filter { (_, name) -> name.lowercase() != "none" }
            .forEach { (idx, name) ->
                val isFav = idx in favorites
                val rb = RadioButton(this).apply {
                    id   = View.generateViewId()
                    text = (if (isFav) "★ " else "") + name.replaceFirstChar { it.uppercaseChar() }
                    tag  = idx
                    if (idx == currentModeIndex) isChecked = true
                    setOnLongClickListener {
                    val newFavs = if (isFav) favorites - idx else favorites + idx
                    sendCmd {
                        conn!!.setFavorites(newFavs, favoritesTotalModes)
                        favorites = newFavs
                    }
                        true
                    }
                }
                b.rgModes.addView(rb)
            }
    }

    private fun applyCnc() {
        // UI is inverted: slider 10 = max ANC, device stores 0 = max ANC
        val uiVal = 10 - audioSettings.cncLevel
        b.sbCnc.progress = uiVal
        b.tvCncLevel.text = getString(R.string.str_anc_level, uiVal)
    }

    private fun applySpatial() {
        val rb = when (audioSettings.spatial) {
            SpatialMode.Off  -> b.rbSpatialOff
            SpatialMode.Room -> b.rbSpatialRoom
            SpatialMode.Head -> b.rbSpatialHead
        }
        b.rgSpatial.check(rb.id)
    }

    private fun applyEq() {
        for (band in eqBands) {
            val tv   = when (band.bandId) { 0 -> b.tvBassVal; 1 -> b.tvMidVal; else -> b.tvTrebleVal }
            tv.text  = "%+d".format(band.current)
            b.eqCurveView.setBand(band.bandId, band.current, band.minVal, band.maxVal)
        }
    }

    private fun applySidetone() {
        val rbId = when (sidetone) {
            1    -> b.rbSidetoneHigh.id
            2    -> b.rbSidetoneMedium.id
            3    -> b.rbSidetoneLow.id
            else -> b.rbSidetoneOff.id
        }
        b.rgSidetone.check(rbId)
    }

    private fun applyPairedDevices() {
        val others = pairedDevices.keys.filter { !it.equals(activeMac, ignoreCase = true) }
        val show = multipoint && activeMac != null && others.isNotEmpty()
        b.tvPairedDevicesLabel.visibility = if (show) View.VISIBLE else View.GONE
        b.tvActiveDevice.visibility       = if (show) View.VISIBLE else View.GONE
        b.tvPairedDevicesHint.visibility  = if (show) View.VISIBLE else View.GONE
        b.rgPairedDevices.visibility      = if (show) View.VISIBLE else View.GONE
        if (!show) return

        val activeName = activeMac?.let { pairedDevices[it] ?: shortenMac(it) } ?: "unknown"
        b.tvActiveDevice.text = getString(R.string.str_now_playing, activeName)

        // Show a context-sensitive hint depending on whether routing is currently available
        b.tvPairedDevicesHint.text = if (canRoute)
            getString(R.string.str_multipoint_hint)
        else
            getString(R.string.str_multipoint_hint_unavailable)

        b.rgPairedDevices.removeAllViews()
        others.forEach { mac ->
            val label = pairedDevices[mac] ?: shortenMac(mac)
            val rb = RadioButton(this).apply {
                id       = View.generateViewId()
                text     = label
                tag      = mac
                // Keep tap enabled so user can still hit the short routing window; retry logic runs in BoseConnection.
                isEnabled = true
                alpha = if (canRoute) 1.0f else 0.65f
            }
            b.rgPairedDevices.addView(rb)
        }
    }


    private fun applyNowPlaying() {
        val sourceName = activeMac?.let { pairedDevices[it] } ?: return run { b.tvNowPlaying.visibility = View.GONE }
        b.tvNowPlaying.text = sourceName
        b.tvNowPlaying.visibility = View.VISIBLE
    }


    // ── Wire UI listeners ─────────────────────────────────────────────────────

    private fun wireListeners() {
        b.btnReconnect.setOnClickListener {
            reconnectJob?.cancel(); reconnectJob = null
            autoReconnectSuppressed = false
            autoReconnectTriggeredForDrop = false
            ensurePermissionsThenConnect()
        }

        b.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) { showSettingsDialog(); true } else false
        }

        b.btnRename.setOnClickListener {
            val cur = b.tvDeviceName.text.toString()
            val input = android.widget.EditText(this).apply { setText(cur) }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.str_rename_device_title))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotEmpty() && newName != cur) sendCmd {
                        conn!!.setDeviceName(newName)
                        b.tvDeviceName.text = newName
                    }
                }
                .setNegativeButton(getString(R.string.str_cancel), null)
                .show()
        }

        b.rgModes.setOnCheckedChangeListener { group, checkedId ->
            if (updatingUi) return@setOnCheckedChangeListener
            val idx = group.findViewById<RadioButton>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
            sendCmd {
                conn!!.setModeByIndex(idx)
                currentModeIndex = idx
                // Eagerly read audio settings so CNC, Spatial and Wind Block update in ~200 ms
                // instead of waiting for the full 14-GET fetchAndRefresh (~3 s).
                // updatingUi guard prevents the UI listeners from re-firing during the update.
                try {
                    audioSettings = conn!!.audioSettings()
                    updatingUi = true
                    try { applyCnc(); applySpatial(); b.swWindBlock.isChecked = audioSettings.windBlock }
                    finally { updatingUi = false }
                } catch (_: Exception) {}
            }
        }

        b.sbCnc.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingUi) return
                // invert: UI 10 = max ANC = device 0
                audioSettings = audioSettings.copy(cncLevel = 10 - progress)
                b.tvCncLevel.text = getString(R.string.str_anc_level, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) {
                if (updatingUi) return
                sendCmd { conn!!.setCncLevel(audioSettings.cncLevel) }
            }
        })

        b.rgSpatial.setOnCheckedChangeListener { _, checkedId ->
            if (updatingUi) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                b.rbSpatialRoom.id -> SpatialMode.Room
                b.rbSpatialHead.id -> SpatialMode.Head
                else               -> SpatialMode.Off
            }
            sendCmdFast { conn!!.setSpatial(mode); audioSettings = audioSettings.copy(spatial = mode) }
        }

        b.swWindBlock.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            sendCmdFast { conn!!.setWindBlock(checked); audioSettings = audioSettings.copy(windBlock = checked) }
        }

        b.eqCurveView.onBandChanged = { bandId, value ->
            val tv   = when (bandId) { 0 -> b.tvBassVal; 1 -> b.tvMidVal; else -> b.tvTrebleVal }
            tv.text  = "%+d".format(value)
        }
        b.eqCurveView.onBandReleased = { bandId, value ->
            sendCmdFast { conn!!.setEqBand(bandId, value) }
            eqBands = eqBands.map { if (it.bandId == bandId) it.copy(current = value) else it }
        }

        b.rgSidetone.setOnCheckedChangeListener { _, checkedId ->
            if (updatingUi) return@setOnCheckedChangeListener
            val level = when (checkedId) {
                b.rbSidetoneHigh.id   -> 1
                b.rbSidetoneMedium.id -> 2
                b.rbSidetoneLow.id    -> 3
                else                  -> 0
            }
            sendCmdFast { conn!!.setSidetone(level); sidetone = level }
        }

        b.swMultipoint.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            sendMultipointToggle(checked)
        }

        b.rgPairedDevices.setOnCheckedChangeListener { group, checkedId ->
            if (updatingUi) return@setOnCheckedChangeListener
            val now = System.currentTimeMillis()
            if (sourceSwitchInFlight || now - lastSourceSwitchAtMs < 400L) {
                setStatus(getString(R.string.str_switch_in_progress))
                return@setOnCheckedChangeListener
            }
            val mac = group.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            sourceSwitchInFlight = true
            lastSourceSwitchAtMs = now
            startRouteAvailabilityProbe()
            sendCmd {
                try {
                    conn!!.switchToDevice(mac)
                    activeMac = try { conn!!.source().second ?: mac } catch (_: Exception) { mac }
                    startRouteAvailabilityProbe(windowMs = 6_000L)
                } finally {
                    sourceSwitchInFlight = false
                    lastSourceSwitchAtMs = System.currentTimeMillis()
                }
            }
        }

        // Media controls

        b.btnPowerOff.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.str_power_off_title))
                .setMessage(getString(R.string.str_power_off_message, b.tvDeviceName.text))
                .setPositiveButton(getString(R.string.str_power_off_confirm_btn)) { _, _ ->
                    autoReconnectSuppressed = true
                    reconnectJob?.cancel(); reconnectJob = null
                    autoReconnectTriggeredForDrop = false
                    sendCmd { conn!!.powerOff() }
                }
                .setNegativeButton(getString(R.string.str_cancel), null)
                .show()
        }


        // Auto Play/Pause + Auto Answer
        b.swAutoPlayPause.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            sendCmdFast { conn!!.setAutoPlayPause(checked); autoPlayPause = checked }
        }
        b.swAutoAnswer.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            sendCmdFast { conn!!.setAutoAnswer(checked); autoAnswer = checked }
        }
        b.swSidetonePersistent.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            sendCmdFast { conn!!.setSidetonePersistent(checked); sidetonePersistent = checked }
        }

        // Pairing mode
        b.btnPairingMode.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.str_pairing_mode_title))
                .setMessage(getString(R.string.str_pairing_mode_message))
                .setPositiveButton(getString(R.string.str_enter_pairing_btn)) { _, _ ->
                    autoReconnectSuppressed = true
                    reconnectJob?.cancel(); reconnectJob = null
                    autoReconnectTriggeredForDrop = false
                    sendCmd { conn!!.enterPairingMode() }
                }
                .setNegativeButton(getString(R.string.str_cancel), null)
                .show()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Multipoint toggles can briefly drop RFCOMM; treat disconnect as expected and reconnect quietly. */
    private fun sendMultipointToggle(enabled: Boolean) {
        if (conn == null) { Toast.makeText(this, getString(R.string.notif_title_disconnected), Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            try {
                conn!!.setMultipoint(enabled)
                multipoint = enabled
                if (!enabled) canRoute = false
                applyPairedDevices()
                startRouteAvailabilityProbe(windowMs = 10_000L)
                // Give firmware a short settle window before the full state pull.
                delay(600)
                fetchAndRefresh()
            } catch (e: Exception) {
                if (isDisconnectedError(e)) {
                    boseService?.detach()
                    setStatus(getString(R.string.str_reconnecting_after_multipoint))
                    scheduleConservativeReconnect(e)
                    return@launch
                }
                Toast.makeText(this@MainActivity, userFacingErrorMessage(e), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Short high-frequency probe so Switch UI follows the brief firmware routing window. */
    private fun startRouteAvailabilityProbe(windowMs: Long = 8_000L, intervalMs: Long = 1_000L) {
        routeProbeJob?.cancel()
        if (!multipoint || intervalMs <= 0L) {
            canRoute = false
            applyPairedDevices()
            return
        }
        routeProbeJob = lifecycleScope.launch {
            val iterations = (windowMs / intervalMs).toInt().coerceAtLeast(1)
            var lastCanRoute = canRoute
            var lastActiveMac = activeMac
            repeat(iterations) {
                val c = conn ?: return@launch
                try {
                    val nextCanRoute = c.canRoute()
                    var nextActiveMac = activeMac
                    if (nextCanRoute) {
                        val (_, mac) = c.source()
                        nextActiveMac = mac
                    }
                    val changed = nextCanRoute != lastCanRoute || nextActiveMac != lastActiveMac
                    canRoute = nextCanRoute
                    activeMac = nextActiveMac
                    if (changed) {
                        applyPairedDevices()
                        applyNowPlaying()
                        lastCanRoute = nextCanRoute
                        lastActiveMac = nextActiveMac
                    }
                } catch (_: Exception) {
                    // Best-effort probe only: keep current UI state on transient errors.
                }
                delay(intervalMs)
            }
        }
    }

    private fun isDisconnectedError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val msg = current.message?.lowercase().orEmpty()
            if (current is IOException) return true
            if (
                "broken pipe" in msg ||
                "connection reset" in msg ||
                "socket closed" in msg ||
                "not connected" in msg
            ) return true
            current = current.cause
        }
        return false
    }

    private fun userFacingErrorMessage(error: Throwable): String {
        if (isDisconnectedError(error)) return getString(R.string.str_connection_lost)
        return error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.str_unknown_error)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun scheduleConservativeReconnect(error: Throwable) {
        if (!isDisconnectedError(error)) return
        if (autoReconnectSuppressed || autoReconnectTriggeredForDrop) return

        val adapter = btAdapter ?: return
        val lastMac = getSharedPreferences("prefs", MODE_PRIVATE).getString("last_mac", null) ?: return
        val device = try {
            DeviceDiscovery.bondedDevices(adapter).firstOrNull { it.address == lastMac }
        } catch (_: SecurityException) {
            null
        } ?: return

        autoReconnectTriggeredForDrop = true
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            val retryDelaysMs = longArrayOf(2_000L, 10_000L)
            for (delayMs in retryDelaysMs) {
                delay(delayMs)
                if (conn != null) {
                    autoReconnectTriggeredForDrop = false
                    return@launch
                }
                val name = device.name ?: device.address
                setStatus(getString(R.string.str_reconnecting_to, name))
                connectTo(device)
            }
        }
    }

    /** Run a suspending BMAP command, then immediately refresh UI from device. */
    private fun sendCmd(block: suspend () -> Unit) {
        if (conn == null) { Toast.makeText(this, getString(R.string.notif_title_disconnected), Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            try {
                block()
                fetchAndRefresh()
            } catch (e: Exception) {
                if (isDisconnectedError(e)) boseService?.detach()
                scheduleConservativeReconnect(e)
                Toast.makeText(this@MainActivity, userFacingErrorMessage(e), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Fire-and-forget command for pure-toggle ops where local state is updated optimistically.
     * Skips the full 13-GET fetchAndRefresh; saves ~2 s per interaction.
     * No server-side confirmation – error only visible via Toast on failure.
     */
    private fun sendCmdFast(block: suspend () -> Unit) {
        if (conn == null) { Toast.makeText(this, getString(R.string.notif_title_disconnected), Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            try { block() } catch (e: Exception) {
                if (isDisconnectedError(e)) boseService?.detach()
                scheduleConservativeReconnect(e)
                Toast.makeText(this@MainActivity, userFacingErrorMessage(e), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setStatus(msg: String) { b.tvStatus.text = msg }

    private fun shortenMac(mac: String): String {
        val parts = mac.split(":")
        return if (parts.size >= 3) "…" + parts.takeLast(3).joinToString(":") else mac
    }
}
