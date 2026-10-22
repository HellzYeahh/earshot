package dev.earshot.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
sealed interface Trigger {
    @Serializable data object OnConnect : Trigger
    @Serializable data class WifiNetwork(val ssid: String) : Trigger
    @Serializable data class ForegroundApp(val packageName: String, val appLabel: String) : Trigger
    /** [days] use java.time.DayOfWeek values 1..7 (Monday = 1). */
    @Serializable data class Schedule(val hour: Int, val minute: Int, val days: Set<Int>) : Trigger
}

@Serializable
sealed interface AutomationAction {
    @Serializable data class SetMode(val modeIndex: Int) : AutomationAction
    @Serializable data class SetEqPreset(val presetId: String?) : AutomationAction
    @Serializable data class SetNoise(
        val cancellingLevel: Int,
        val ancEnabled: Boolean,
        val windBlock: Boolean,
        val spatialAudioWire: Int,
    ) : AutomationAction
}

@Serializable
data class AutomationRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: Trigger,
    val actions: List<AutomationAction>,
)

/**
 * Where the app's colours come from. The Field look (type, shapes, the dot grid) stays either
 * way; [SYSTEM] only swaps the palette for the wallpaper-derived one on Android 12+.
 */
enum class Appearance { FIELD, SYSTEM }

/** App-wide preferences that are not headphone state. */
data class AppPreferences(
    /** Battery percentage that triggers the low-battery alert; null = off. */
    val lowBatteryThreshold: Int? = 20,
    val persistentNotification: Boolean = true,
    val autoConnectOnBluetooth: Boolean = true,
    /** Which modes the Quick Settings tile cycles through. Empty = favourites, or presets if none. */
    val tileCycleModes: List<Int> = emptyList(),
    val appearance: Appearance = Appearance.FIELD,
)

interface AutomationRepository {
    val rules: StateFlow<List<AutomationRule>>
    val preferences: StateFlow<AppPreferences>
    /** Short human-readable log of what fired recently, newest first. */
    val recentLog: StateFlow<List<String>>

    suspend fun upsertRule(rule: AutomationRule)
    suspend fun deleteRule(id: String)
    suspend fun setPreferences(preferences: AppPreferences)

    /** True when the app has the Usage Access special permission (needed for ForegroundApp triggers). */
    fun hasUsageAccess(): Boolean
    fun openUsageAccessSettings()
}
