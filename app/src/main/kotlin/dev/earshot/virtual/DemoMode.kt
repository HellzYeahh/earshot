package dev.earshot.virtual

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

// File-private delegate: exactly one DataStore for this file per process.
private val Context.demoModeDataStore: DataStore<Preferences> by preferencesDataStore(name = "demo_mode")

/**
 * Which virtual headphones demo mode talks to. [displayName] is what the Preferences chips show;
 * the UI lists [entries], so a new entry appears there without UI changes. The Bose entries run
 * a [VirtualQcUltra2] with the matching [VirtualProfile] ([virtualProfile]).
 */
enum class DemoDevice(val displayName: String, val isCrusher: Boolean = false) {
    QC_ULTRA("QC Ultra Headphones (2nd Gen)"),
    QC_ULTRA_EARBUDS_2("QC Ultra Earbuds (2nd Gen)"),
    QC_EARBUDS_2("QC Earbuds II"),
    QC_EARBUDS("QC Earbuds (2020)"),
    QC45("QuietComfort 45"),
    NC700("Noise Cancelling Headphones 700"),
    ULTRA_OPEN("Ultra Open Earbuds"),

    /** Recognised but unsupported: shows how the app explains that. */
    QC35_II("QuietComfort 35 II"),
    CRUSHER("Crusher 1080", isCrusher = true),

    /** A Crusher that asks for the one-time pairing, to try the pairing flow without the headphones. */
    CRUSHER_SECURE("Crusher (pairing)", isCrusher = true),
    ;
}

/**
 * The "Demo mode" switch: when on, the connection manager talks to a [VirtualQcUltra2] through
 * [VirtualTransport.factory] instead of opening an RFCOMM socket, so the app can be shown (and
 * exercised on an emulator) without the headphones. Off by default; survives restarts.
 */
class DemoMode(context: Context) {
    private val store = context.applicationContext.demoModeDataStore

    val isEnabled: Flow<Boolean> = store.data.map { it[KEY_ENABLED] ?: false }

    suspend fun set(enabled: Boolean) {
        store.edit { it[KEY_ENABLED] = enabled }
    }

    /** The virtual device demo mode uses; the QC Ultra 2 unless the user picked the Crusher. */
    val device: Flow<DemoDevice> = store.data.map { prefs -> prefs[KEY_DEVICE]?.let { k -> DemoDevice.entries.firstOrNull { it.name == k } } ?: DemoDevice.QC_ULTRA }

    suspend fun setDevice(device: DemoDevice) {
        store.edit { it[KEY_DEVICE] = device.name }
    }

    fun deviceBlocking(): DemoDevice = runBlocking { device.first() }

    /**
     * For the one place that cannot suspend (choosing the transport factory when the manager
     * is built). DataStore's first read is a small file read; later reads come from memory.
     */
    fun isEnabledBlocking(): Boolean = runBlocking { isEnabled.first() }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_DEVICE = stringPreferencesKey("device")
    }
}
