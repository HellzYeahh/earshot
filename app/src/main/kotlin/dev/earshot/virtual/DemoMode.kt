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

/** Which virtual headphones demo mode talks to. */
enum class DemoDevice {
    QC_ULTRA,
    CRUSHER,

    /** A Crusher that asks for the one-time pairing, to try the pairing flow without the headphones. */
    CRUSHER_SECURE,
    ;

    val isCrusher: Boolean get() = this != QC_ULTRA
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
