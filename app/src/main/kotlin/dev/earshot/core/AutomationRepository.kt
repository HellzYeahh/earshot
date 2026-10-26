package dev.earshot.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The rules are saved as JSON with a "type" discriminator per Trigger / AutomationAction. 0.75
 * wrote the classes' fully qualified names there (no @SerialName), so every subclass pins exactly
 * that name: moving these classes to another package (the module split) must not change what is on
 * disk, or every saved rule becomes unreadable. Never change a pinned name; a new subclass gets a
 * short one of its own.
 */

@Serializable
sealed interface Trigger {
    @Serializable @SerialName("dev.earshot.core.Trigger.OnConnect")
    data object OnConnect : Trigger

    @Serializable @SerialName("dev.earshot.core.Trigger.WifiNetwork")
    data class WifiNetwork(val ssid: String) : Trigger

    @Serializable @SerialName("dev.earshot.core.Trigger.ForegroundApp")
    data class ForegroundApp(val packageName: String, val appLabel: String) : Trigger

    /** [days] use java.time.DayOfWeek values 1..7 (Monday = 1). */
    @Serializable @SerialName("dev.earshot.core.Trigger.Schedule")
    data class Schedule(val hour: Int, val minute: Int, val days: Set<Int>) : Trigger
}

@Serializable
sealed interface AutomationAction {
    /**
     * Switch listening mode. [mode] says which by meaning (0.8 on); [modeIndex] is the slot on the
     * headset it was picked on and is always written, because a downgraded 0.75 build reads only
     * that and drops every rule if it is missing. A rule with no [mode] is a 0.75 rule that the
     * migration has not bound yet: it runs [modeIndex] exactly as 0.75 did.
     */
    @Serializable @SerialName("dev.earshot.core.AutomationAction.SetMode")
    data class SetMode(val modeIndex: Int, val mode: ModeRef? = null) : AutomationAction

    @Serializable @SerialName("dev.earshot.core.AutomationAction.SetEqPreset")
    data class SetEqPreset(val presetId: String?) : AutomationAction

    /** [spatialAudioWire] keeps its 0.75 key but holds [SpatialAudio.storedId], not a headset's wire code. */
    @Serializable @SerialName("dev.earshot.core.AutomationAction.SetNoise")
    data class SetNoise(
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
    /**
     * 0.75's tile cycle: bare slot numbers of whatever headset was connected. Used until
     * [modeRefsMigrated]; after that it is stored as [tileCycle]'s slots so a downgraded build keeps
     * its tile ([legacyTileSlots]).
     */
    val tileCycleModes: List<Int> = emptyList(),
    val appearance: Appearance = Appearance.FIELD,
    /** Which modes the Quick Settings tile and the widget cycle through. Empty = favourites, else the first modes. */
    val tileCycle: List<ModeRef> = emptyList(),
    /** True once [tileCycle] replaced [tileCycleModes]: the one-time migration ran, or the user chose modes in 0.8. */
    val modeRefsMigrated: Boolean = false,
) {
    /** The bare slots to store for an older build's tile. */
    val legacyTileSlots: List<Int>
        get() = if (modeRefsMigrated) tileCycle.mapNotNull { it.slot ?: it.legacySlot }.distinct() else tileCycleModes

    /** The user's (or the migration's) tile choice, which replaces the 0.75 list for good. */
    fun withTileCycle(refs: List<ModeRef>): AppPreferences {
        val migrated = copy(tileCycle = refs, modeRefsMigrated = true)
        return migrated.copy(tileCycleModes = migrated.legacyTileSlots)
    }
}

interface AutomationRepository {
    val rules: StateFlow<List<AutomationRule>>
    val preferences: StateFlow<AppPreferences>
    /** Short human-readable log of what fired recently, newest first. */
    val recentLog: StateFlow<List<String>>

    suspend fun upsertRule(rule: AutomationRule)
    suspend fun deleteRule(id: String)
    suspend fun setPreferences(preferences: AppPreferences)

    /**
     * Rewrites the saved rules in one step, from what is stored rather than from [rules] (which is
     * empty until the store has been read), keeping their order. For the mode migration. The
     * default suits in-memory fakes.
     */
    suspend fun updateRules(transform: (List<AutomationRule>) -> List<AutomationRule>) {
        val before = rules.value
        transform(before).forEach { rule -> if (rule !in before) upsertRule(rule) }
    }

    /** [setPreferences] from the stored preferences in one step, for the same reason as [updateRules]. */
    suspend fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
        setPreferences(transform(preferences.value))
    }

    /** True when the app has the Usage Access special permission (needed for ForegroundApp triggers). */
    fun hasUsageAccess(): Boolean
    fun openUsageAccessSettings()
}
