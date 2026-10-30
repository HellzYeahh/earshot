package dev.earshot.extras.automation

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.earshot.core.AppPreferences
import dev.earshot.core.Appearance
import dev.earshot.core.AutomationRepository
import dev.earshot.core.AutomationRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// A corrupt file is replaced with an empty one, as for the other stores. Without the handler every
// read fell back to the defaults (auto-connect on) and every save threw CorruptionException, so a
// user who had switched Auto-connect off got it back on every link-up and could never save it
// off again short of clearing the app's data. Losing the file costs the rules, the log and these
// preferences, which go back to their defaults.
private val Context.automationStore: DataStore<Preferences> by preferencesDataStore(
    name = "earshot_automation",
    corruptionHandler = ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() },
)

/**
 * DataStore-backed [AutomationRepository]. Rules and the recent log are JSON strings
 * (kotlinx-serialization handles the sealed `Trigger`/`AutomationAction` hierarchies with a
 * `type` discriminator; the rule format lives in [AutomationJson]); preferences are individual
 * keys so a future field is a one-line add.
 *
 * The exposed flows are hot (`stateIn(Eagerly)`) so the tile, widget and engine can read
 * `.value` synchronously; the first emission after process start is the default until the
 * file has been read, which takes a few milliseconds.
 */
class AutomationRepositoryImpl(
    context: Context,
    private val scope: CoroutineScope,
) : AutomationRepository, AutomationLog {

    private val appContext = context.applicationContext
    private val store = appContext.automationStore

    override val rules: StateFlow<List<AutomationRule>> = store.data
        .catch { e -> Log.w(TAG, "rules read failed", e); emit(emptyPreferences()) }
        .map { prefs -> decodeRules(prefs[KEY_RULES]).rules }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val preferences: StateFlow<AppPreferences> = store.data
        .catch { e -> Log.w(TAG, "preferences read failed", e); emit(emptyPreferences()) }
        .map { prefs -> decodePreferences(prefs) }
        .stateIn(scope, SharingStarted.Eagerly, AppPreferences())

    override val recentLog: StateFlow<List<String>> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> decodeLog(prefs[KEY_LOG]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun upsertRule(rule: AutomationRule) = editRules(userEdit = true) { current -> current.filterNot { it.id == rule.id } + rule }

    override suspend fun deleteRule(id: String) = editRules(userEdit = true) { current -> current.filterNot { it.id == id } }

    override suspend fun updateRules(transform: (List<AutomationRule>) -> List<AutomationRule>) = editRules(userEdit = false, transform)

    /**
     * Read-modify-write inside one DataStore edit; rules this build can't read are written back
     * untouched. Nothing is written when nothing changed, and stored text that isn't a rule list at
     * all is replaced only by the user's own edit (as in 0.75), never by an automatic rewrite.
     */
    private suspend fun editRules(userEdit: Boolean, transform: (List<AutomationRule>) -> List<AutomationRule>) {
        store.edit { prefs ->
            val current = decodeRules(prefs[KEY_RULES])
            if (current.corrupt && !userEdit) return@edit
            val next = transform(current.rules)
            if (next == current.rules && !current.corrupt) return@edit
            prefs[KEY_RULES] = AutomationJson.encodeRules(next, current.unreadable)
        }
    }

    override suspend fun setPreferences(preferences: AppPreferences) {
        var switchedOff = false
        store.edit { prefs ->
            switchedOff = persistentNotificationSwitchedOff(prefs, preferences)
            writePreferences(prefs, preferences)
        }
        if (switchedOff) dropKeptConnectionNotification()
    }

    override suspend fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
        var switchedOff = false
        store.edit { prefs ->
            val next = transform(decodePreferences(prefs))
            switchedOff = persistentNotificationSwitchedOff(prefs, next)
            writePreferences(prefs, next)
        }
        if (switchedOff) dropKeptConnectionNotification()
    }

    /** [stored] is the store as it was before this edit's writes. */
    private fun persistentNotificationSwitchedOff(stored: Preferences, next: AppPreferences): Boolean =
        (stored[KEY_PERSISTENT_NOTIFICATION] ?: AppPreferences().persistentNotification) && !next.persistentNotification

    /**
     * "Persistent notification" was just switched off. While the connection service runs, it
     * removes its own notification at its idle stop (it reads the choice then). Once it has
     * stopped, the notification it kept stays up, ongoing (Android 9 to 13 don't let the user
     * swipe it), and nothing else is left to remove it, so it goes here. Android ignores an app's
     * cancel of a running foreground service's notification, so this never touches a live one.
     */
    private fun dropKeptConnectionNotification() {
        runCatching { NotificationManagerCompat.from(appContext).cancel(CONNECTION_NOTIFICATION_ID) }
            .onFailure { Log.w(TAG, "could not remove the kept connection notification", it) }
    }

    private fun writePreferences(prefs: MutablePreferences, preferences: AppPreferences) {
        prefs[KEY_LOW_BATTERY_ENABLED] = preferences.lowBatteryThreshold != null
        preferences.lowBatteryThreshold?.let { prefs[KEY_LOW_BATTERY_THRESHOLD] = it }
        prefs[KEY_PERSISTENT_NOTIFICATION] = preferences.persistentNotification
        prefs[KEY_AUTO_CONNECT] = preferences.autoConnectOnBluetooth
        // Still written after the migration (as the refs' slots), so a downgraded build keeps its tile.
        prefs[KEY_TILE_CYCLE] = AutomationJson.encodeLegacyTileCycle(preferences.legacyTileSlots)
        prefs[KEY_TILE_CYCLE_REFS] = AutomationJson.encodeTileCycle(preferences.tileCycle)
        prefs[KEY_MODE_REFS_MIGRATED] = preferences.modeRefsMigrated
        prefs[KEY_APPEARANCE] = preferences.appearance.name
    }

    /** Newest first, capped at [LOG_CAP]. Fire-and-forget: callers are not suspend-friendly. */
    override fun append(entry: String) {
        scope.launch {
            runCatching {
                store.edit { prefs ->
                    val next = (listOf(entry) + decodeLog(prefs[KEY_LOG])).take(LOG_CAP)
                    prefs[KEY_LOG] = json.encodeToString(ListSerializer(String.serializer()), next)
                }
            }.onFailure { Log.w(TAG, "log append failed", it) }
        }
    }

    /**
     * Usage Access is a "special app access", not a runtime permission: the user grants it in
     * Settings and the only reliable check is the app-op. `MODE_DEFAULT` means "fall back to the
     * manifest permission", which happens on some OEM builds and for preinstalled apps.
     */
    override fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT ->
                appContext.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    override fun openUsageAccessSettings() {
        // Some OEMs reject the package-scoped form; the plain list is a safe fallback.
        val scoped = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(android.net.Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val plain = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(scoped) }
            .recoverCatching { appContext.startActivity(plain) }
            .onFailure { Log.w(TAG, "No Usage Access settings screen", it) }
    }

    // ---- codecs ----------------------------------------------------------------------------

    private fun decodeRules(raw: String?): AutomationJson.Rules {
        val decoded = AutomationJson.decodeRules(raw)
        if (decoded.corrupt) Log.w(TAG, "Dropping unreadable rules (not a JSON list)")
        if (decoded.unreadable.isNotEmpty()) Log.w(TAG, "Keeping ${decoded.unreadable.size} rule(s) this version can't read")
        return decoded
    }

    private fun decodeLog(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrDefault(emptyList())
    }

    private fun decodePreferences(prefs: Preferences): AppPreferences {
        val defaults = AppPreferences()
        val lowBatteryEnabled = prefs[KEY_LOW_BATTERY_ENABLED] ?: (defaults.lowBatteryThreshold != null)
        val threshold = prefs[KEY_LOW_BATTERY_THRESHOLD] ?: defaults.lowBatteryThreshold ?: 20
        val legacyTile = AutomationJson.decodeLegacyTileCycle(prefs[KEY_TILE_CYCLE])
        val tileCycle = AutomationJson.decodeTileCycle(prefs[KEY_TILE_CYCLE_REFS])
        val migrated = (prefs[KEY_MODE_REFS_MIGRATED] ?: defaults.modeRefsMigrated) && AutomationJson.tileRefsCurrent(legacyTile, tileCycle)
        return AppPreferences(
            lowBatteryThreshold = if (lowBatteryEnabled) threshold else null,
            persistentNotification = prefs[KEY_PERSISTENT_NOTIFICATION] ?: defaults.persistentNotification,
            autoConnectOnBluetooth = prefs[KEY_AUTO_CONNECT] ?: defaults.autoConnectOnBluetooth,
            tileCycleModes = legacyTile ?: defaults.tileCycleModes,
            // A name from a newer build that this one doesn't know falls back to the default.
            appearance = prefs[KEY_APPEARANCE]?.let { name -> Appearance.entries.firstOrNull { it.name == name } } ?: defaults.appearance,
            tileCycle = tileCycle,
            modeRefsMigrated = migrated,
        )
    }

    private fun emptyPreferences(): Preferences = androidx.datastore.preferences.core.emptyPreferences()

    companion object {
        private const val TAG = "Earshot/Automation"
        const val LOG_CAP = 50

        /**
         * The connection service's notification id (ServiceNotification.NOTIFICATION_ID), repeated
         * rather than imported: extras may not reference the service package (VendorBoundaryTest).
         * KeptNotificationIdTest keeps the two equal.
         */
        internal const val CONNECTION_NOTIFICATION_ID = 1001

        private val json = AutomationJson.json

        private val KEY_RULES = stringPreferencesKey("rules_json")
        private val KEY_LOG = stringPreferencesKey("recent_log_json")
        private val KEY_LOW_BATTERY_ENABLED = booleanPreferencesKey("low_battery_enabled")
        private val KEY_LOW_BATTERY_THRESHOLD = intPreferencesKey("low_battery_threshold")
        private val KEY_PERSISTENT_NOTIFICATION = booleanPreferencesKey("persistent_notification")
        private val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect_on_bluetooth")
        private val KEY_TILE_CYCLE = stringPreferencesKey("tile_cycle_modes")
        private val KEY_TILE_CYCLE_REFS = stringPreferencesKey("tile_cycle_refs_json")
        private val KEY_MODE_REFS_MIGRATED = booleanPreferencesKey("mode_refs_v1")
        private val KEY_APPEARANCE = stringPreferencesKey("appearance")
    }
}
