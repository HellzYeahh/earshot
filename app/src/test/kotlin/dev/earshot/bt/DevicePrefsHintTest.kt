package dev.earshot.bt

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The per-family link hints: BOSE and BRAGI on the keys they always had (no migration, nothing
 * lost on upgrade or downgrade), every other family on `hint_<family>`, all of them only for the
 * saved device and forgotten when another device is saved. A real file-backed DataStore, no Android.
 */
class DevicePrefsHintTest {

    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var store: DataStore<Preferences>

    private fun prefs(): DevicePrefs {
        store = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "bt_device.preferences_pb") }
        return DevicePrefs(store)
    }

    private fun raw(): Preferences = runBlocking { store.data.first() }

    @After fun close() = scope.cancel()

    @Test fun `Bose and Bragi hints stay on their legacy keys`() = runBlocking<Unit> {
        val p = prefs()
        p.save("aa:bb:cc:dd:ee:01", "Bose QC Ultra Headphones")
        p.saveCandidate("AA:BB:CC:DD:EE:01", BmapCandidate.Channel(8))
        assertEquals(8, raw()[intPreferencesKey("rfcomm_channel")], "the 0.75 key, as an Int")
        assertEquals(BmapCandidate.Channel(8), p.candidateFor("AA:BB:CC:DD:EE:01"))
        assertEquals("8", p.linkHint(DeviceFamily.BOSE, "aa:bb:cc:dd:ee:01"))

        p.saveLinkHint(DeviceFamily.BOSE, "AA:BB:CC:DD:EE:01", BmapCandidate.SdpSpp.prefValue.toString())
        assertEquals(BmapCandidate.SdpSpp, p.candidateFor("AA:BB:CC:DD:EE:01"), "SDP winners keep their negative codes")
        assertFailsWith<IllegalArgumentException> { p.saveLinkHint(DeviceFamily.BOSE, "AA:BB:CC:DD:EE:01", "spp") }

        p.saveBragiProfile("AA:BB:CC:DD:EE:01", "data-54c5")
        assertEquals("data-54c5", raw()[stringPreferencesKey("bragi_profile")])
        assertEquals("data-54c5", p.linkHint(DeviceFamily.BRAGI, "AA:BB:CC:DD:EE:01"))
        assertEquals("data-54c5", p.bragiProfileFor("AA:BB:CC:DD:EE:01"))
    }

    @Test fun `a value written by 0_75 is read back unchanged`() = runBlocking<Unit> {
        val p = prefs()
        p.save("AA:BB:CC:DD:EE:02", "LE-Bose QC Ultra")
        // What 0.75 wrote for an LE CoC winner and a Bragi profile, straight into the store.
        store.edit { it[intPreferencesKey("rfcomm_channel")] = BmapCandidate.LeCoc.prefValue }
        assertEquals(BmapCandidate.LeCoc, p.candidateFor("AA:BB:CC:DD:EE:02"))
        assertEquals(DeviceFamily.BOSE, p.knownFamily("AA:BB:CC:DD:EE:02"), "the pre-0.6 fallback still reads the legacy key")
    }

    @Test fun `other families get their own hint key`() = runBlocking<Unit> {
        val p = prefs()
        p.save("AA:BB:CC:DD:EE:03", "WH-1000XM5")
        p.saveLinkHint(DeviceFamily.SONY, "AA:BB:CC:DD:EE:03", "956c7b26")
        p.saveLinkHint(DeviceFamily.SOUNDCORE, "AA:BB:CC:DD:EE:03", "d1301")
        assertEquals("956c7b26", raw()[stringPreferencesKey("hint_sony")])
        assertEquals("956c7b26", p.linkHint(DeviceFamily.SONY, "aa:bb:cc:dd:ee:03"))
        assertEquals("d1301", p.linkHint(DeviceFamily.SOUNDCORE, "AA:BB:CC:DD:EE:03"))
        assertNull(p.linkHint(DeviceFamily.SENNHEISER, "AA:BB:CC:DD:EE:03"))
        assertNull(p.knownFamily("AA:BB:CC:DD:EE:03"), "a new-style hint is not a pre-0.6 family record")
    }

    @Test fun `hints belong to the saved device and go when another one is saved`() = runBlocking<Unit> {
        val p = prefs()
        p.save("AA:BB:CC:DD:EE:04", "Momentum 4")
        p.saveLinkHint(DeviceFamily.SENNHEISER, "AA:BB:CC:DD:EE:99", "ignored")
        assertNull(p.linkHint(DeviceFamily.SENNHEISER, "AA:BB:CC:DD:EE:99"), "not the saved device: nothing stored")
        assertNull(raw()[stringPreferencesKey("hint_sennheiser")])

        p.saveLinkHint(DeviceFamily.SENNHEISER, "AA:BB:CC:DD:EE:04", "gaia")
        p.saveCandidate("AA:BB:CC:DD:EE:04", BmapCandidate.Channel(2))
        p.saveBragiProfile("AA:BB:CC:DD:EE:04", "data-a460")
        p.save("aa:bb:cc:dd:ee:04", "MOMENTUM 4") // same device, renamed: everything stays
        assertEquals("gaia", p.linkHint(DeviceFamily.SENNHEISER, "AA:BB:CC:DD:EE:04"))
        assertEquals(BmapCandidate.Channel(2), p.candidateFor("AA:BB:CC:DD:EE:04"))

        p.save("AA:BB:CC:DD:EE:05", "Other")
        assertNull(raw()[stringPreferencesKey("hint_sennheiser")])
        assertNull(raw()[intPreferencesKey("rfcomm_channel")])
        assertNull(raw()[stringPreferencesKey("bragi_profile")])
        assertNull(p.linkHint(DeviceFamily.SENNHEISER, "AA:BB:CC:DD:EE:04"))
        assertEquals("AA:BB:CC:DD:EE:05", p.savedDevice()?.address)
    }
}
