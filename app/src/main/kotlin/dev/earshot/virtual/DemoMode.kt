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
enum class DemoDevice(
    val displayName: String,
    val isCrusher: Boolean = false,
    val isGeneric: Boolean = false,
    val isSoundcore: Boolean = false,
) {
    QC_ULTRA("Bose QC Ultra Headphones (2nd Gen)"),
    QC_ULTRA_EARBUDS_2("Bose QC Ultra Earbuds (2nd Gen)"),
    QC_EARBUDS_2("Bose QC Earbuds II"),
    QC_EARBUDS("Bose QC Earbuds (2020)"),
    QC45("Bose QuietComfort 45"),
    NC700("Bose Noise Cancelling Headphones 700"),
    ULTRA_OPEN("Bose Ultra Open Earbuds"),

    /** Recognised but unsupported: shows how the app explains that. */
    QC35_II("Bose QuietComfort 35 II"),
    CRUSHER("Skullcandy Crusher 1080", isCrusher = true),

    /** A Crusher that asks for the one-time pairing, to try the pairing flow without the headphones. */
    CRUSHER_SECURE("Skullcandy Crusher (pairing)", isCrusher = true),

    /** A headset Earshot has no stack for: shown by name, battery from a canned AirPods advert. */
    AIRPODS_PRO("Apple AirPods Pro", isGeneric = true),

    /** The Soundcore driver against a virtual Sleep A30 (noise cancelling on/off, battery, tap controls). */
    SOUNDCORE_SLEEP_A30("Soundcore Sleep A30", isSoundcore = true),

    /** Soundcore models driven from their layout table: three ambient modes, noise-cancelling type and level, settings ([soundcoreModel]). */
    SOUNDCORE_LIBERTY_4_NC("Soundcore Liberty 4 NC", isSoundcore = true),
    SOUNDCORE_SPACE_ONE_PRO("Soundcore Space One Pro", isSoundcore = true),

    /** The Skullcandy Method earbuds on the Bragi stack: Quiet / Aware / Off, sidetone, battery; no Crusher bass, no spatial audio. */
    METHOD_360("Skullcandy Method 360 ANC", isCrusher = true),
    METHOD_540("Skullcandy Method 540 ANC", isCrusher = true),

    /** The Sennheiser driver (Experimental) against a virtual Momentum 4: modes, noise level, anti-wind, EQ, settings. */
    SENNHEISER_MOMENTUM_4("Sennheiser MOMENTUM 4"),

    /** The Sony driver (Experimental) against a virtual WH-1000XM5; see `isSony` in VirtualSony.kt. */
    SONY_WH1000XM5("Sony WH-1000XM5"),
    ;

    /** The demo headset for a generic entry, or null. */
    fun demoHeadset(): DemoHeadset? = when (this) {
        // Left 90 %, right 80 %, case 60 % and charging, as the advert would say it (see ContinuityAdvert).
        AIRPODS_PRO -> DemoHeadset("AirPods Pro (demo)", byteArrayOf(0x07, 0x19, 0x01, 0x14, 0x20, 0x2B, 0x89.toByte(), 0x46, 0, 0, 0))
        else -> null
    }

    /** Runs the Sennheiser stack against a [VirtualSennheiser]. */
    val isSennheiser: Boolean get() = this == SENNHEISER_MOMENTUM_4
}

/** A generic demo headset: its name and the proximity-pairing advert it "sends". */
class DemoHeadset(val name: String, val advert: ByteArray)

/**
 * A virtual unit that demo mode's "press a control" buttons (the Controls screen's detect sheet)
 * can press: it then does and pushes what real headphones would, so the press reaches the app the
 * way a real one does. Neutral, so the UI needs no vendor's virtual device.
 */
interface DemoPressTarget {
    /** Play / pause on the headset itself. */
    fun pressPlayPause()

    /** The headset's own volume control, [delta] steps. */
    fun swipeVolume(delta: Int)

    /** The headset's main programmable button, pressed as it is configured. */
    fun pressMainButton()
}

/** Where the UI finds the unit to press. */
object DemoPresses {
    /** The virtual unit whose session started last, or null; only Bose units take presses so far. */
    fun current(): DemoPressTarget? = VirtualQcUltra2.current()
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
