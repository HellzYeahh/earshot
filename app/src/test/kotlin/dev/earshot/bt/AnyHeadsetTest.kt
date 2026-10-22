package dev.earshot.bt

import dev.earshot.core.FormFactor
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.service.GenericHeadphoneRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.6: headsets Earshot has no stack for are shown by their own name and never get BMAP. */
class AnyHeadsetTest {

    // ── Which names are Bose ──────────────────────────────────────────────────────────────

    @Test fun `Bose default names are Bose`() {
        listOf(
            "Bose QC Ultra Headphones", "LE-Bose QC Ultra Earbuds", "Bose QC35 II", "Bose NC 700 HP",
            "Bose Ultra Open Earbuds", "QuietComfort 45", "QC45", "QC Ultra Earbuds", "bose qc earbuds",
        ).forEach { assertEquals(DeviceFamily.BOSE, DeviceFamily.forName(it), it) }
    }

    @Test fun `other brands are generic, including look-alikes the old hints caught`() {
        listOf(
            "QCY-T13", "QCY HT05", // a different brand starting with QC
            "Soundcore Boom 2", "Galaxy Buds3 Pro", "AirPods Pro", "WH-1000XM4", // Sony MDR v1: no driver
            "Nothing Ear (2)", "Redmi Buds 6 Ultra", "Space Ultra", "TWS-X15", "Alex's headphones",
        ).forEach { assertEquals(DeviceFamily.GENERIC, DeviceFamily.forName(it), it) }
    }

    @Test fun `a renamed Bose stays Bose once its stack has answered`() {
        assertEquals(DeviceFamily.BOSE, DeviceFamily.resolve(override = null, name = "Alex's headphones", learned = DeviceFamily.BOSE))
        assertEquals(DeviceFamily.BRAGI, DeviceFamily.resolve(null, "Alex's headphones", learned = DeviceFamily.BRAGI))
        // The user's own setting for that device still wins.
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.resolve("generic", "Bose QC Ultra Headphones", learned = DeviceFamily.BOSE))
    }

    // ── Which headset is shown ────────────────────────────────────────────────────────────

    private val bose = DeviceCandidate("4C:87:5D:00:00:01", "Bose QC Ultra Headphones", looksLikeBose = true, isAudioDevice = true, isSaved = true)
    private val airpods = DeviceCandidate("A0:00:00:00:00:02", "AirPods Pro", looksLikeBose = false, isAudioDevice = true, isSaved = false)
    private val buds = DeviceCandidate("B0:00:00:00:00:03", "TWS-X15", looksLikeBose = false, isAudioDevice = true, isSaved = false)
    private val watch = DeviceCandidate("C0:00:00:00:00:04", "Watch", looksLikeBose = false, isAudioDevice = false, isSaved = false)

    // Sorted the way DeviceFinder.candidates() sorts: saved, Bose-looking, audio, name.
    private val all = listOf(bose, airpods, buds)

    @Test fun `a connected headset beats the saved one that is off`() {
        assertEquals(airpods, DevicePick.choose(all, connected = setOf(airpods.address)))
    }

    @Test fun `the saved headset wins when it is connected too`() {
        assertEquals(bose, DevicePick.choose(all, connected = setOf(airpods.address, bose.address)))
    }

    @Test fun `nothing connected keeps the saved device, so Bose reconnects as before`() {
        assertEquals(bose, DevicePick.choose(all, connected = emptySet()))
    }

    @Test fun `a device picked in this run wins over whatever is connected`() {
        assertEquals(buds, DevicePick.choose(all, connected = setOf(airpods.address), pinned = buds.address.lowercase()))
        // A pin for a device that is no longer bonded is ignored.
        assertEquals(airpods, DevicePick.choose(all, connected = setOf(airpods.address), pinned = "FF:FF:FF:FF:FF:FF"))
    }

    @Test fun `first launch with only generic headsets picks the connected one`() {
        val fresh = listOf(airpods, buds)
        assertEquals(buds, DevicePick.choose(fresh, connected = setOf(buds.address)))
        // Two paired, none connected: ambiguous, the UI asks.
        assertNull(DevicePick.choose(fresh, connected = emptySet()))
    }

    @Test fun `a connected non-audio device is not a headset`() {
        assertNull(DevicePick.choose(listOf(watch), connected = setOf(watch.address)))
    }

    // ── What counts as a headset (classes and UUIDs from the user's phone, 2026-10-20) ────

    private fun deviceClassOf(cod: Int) = cod and 0x1FFC // what BluetoothClass.getDeviceClass() returns

    @Test fun `earbuds, the QC Ultra and hands-free earbuds are headsets`() {
        assertTrue(AudioClass.isHeadset(deviceClassOf(0x240404), emptyList())) // soundcore Sleep A30
        assertTrue(AudioClass.isHeadset(deviceClassOf(0x2C4404), emptyList())) // Bose QC Ultra 2 HP
        assertTrue(AudioClass.isHeadset(deviceClassOf(0x240418), emptyList())) // headphones class
        assertTrue(AudioClass.isHeadset(deviceClassOf(0x240408), listOf("0000111e-0000-1000-8000-00805f9b34fb"))) // hands-free buds
    }

    @Test fun `a car kit, a speaker and a watch are not`() {
        val uconnect = listOf(
            "00001101-0000-1000-8000-00805f9b34fb", "0000110b-0000-1000-8000-00805f9b34fb",
            "0000111e-0000-1000-8000-00805f9b34fb", "00001133-0000-1000-8000-00805F9B34FB", // MNS: a dashboard showing texts
        )
        assertFalse(AudioClass.isHeadset(deviceClassOf(0x340408), uconnect))
        assertFalse(AudioClass.isHeadset(deviceClassOf(0x240414), emptyList())) // soundcore Boom 2 (loudspeaker)
        assertFalse(AudioClass.isHeadset(deviceClassOf(0x280704), emptyList())) // Galaxy Watch
        assertFalse(AudioClass.isHeadset(null, emptyList()))
    }

    @Test fun `on HFP a watch or a car is not the headset, odd audio classes still are`() {
        fun major(cod: Int) = cod and 0x1F00
        // Galaxy Watch8 Classic: the HFP hands-free unit that showed up on the Controls screen.
        assertFalse(AudioClass.isAudioPeer(major(0x280704), deviceClassOf(0x280704), emptyList()))
        assertFalse(AudioClass.isAudioPeer(major(0x340408), deviceClassOf(0x340408), listOf("00001133-0000-1000-8000-00805f9b34fb")))
        assertTrue(AudioClass.isAudioPeer(major(0x240404), deviceClassOf(0x240404), emptyList()))
        assertTrue(AudioClass.isAudioPeer(major(0x240400), deviceClassOf(0x240400), emptyList())) // audio, uncategorized
        assertTrue(AudioClass.isAudioPeer(null, null, emptyList())) // class unreadable: keep
    }

    // ── How a generic headset is worded ───────────────────────────────────────────────────

    @Test fun `generic capabilities offer none of the headset's own controls`() {
        val caps = GenericHeadphoneRepository.capabilitiesFor("AirPods Pro")
        assertFalse(caps.listeningModes)
        assertFalse(caps.noiseCancelling)
        assertFalse(caps.spatialAudio)
        assertTrue(caps.supportNote != null)
        assertEquals(FormFactor.EARBUDS, caps.formFactor)
    }

    @Test fun `earbud names without buds in them`() {
        assertEquals(FormFactor.EARBUDS, HeadphoneLabels.formFactorFromName("AirPods Pro"))
        assertEquals(FormFactor.EARBUDS, HeadphoneLabels.formFactorFromName("Nothing Ear (2)"))
        assertEquals(FormFactor.EARBUDS, HeadphoneLabels.formFactorFromName("TWS-X15"))
        assertEquals(FormFactor.EARBUDS, HeadphoneLabels.formFactorFromName("Galaxy Buds3 Pro"))
        assertEquals(FormFactor.HEADPHONES, HeadphoneLabels.formFactorFromName("AirPods Max"))
        assertEquals(FormFactor.HEADPHONES, HeadphoneLabels.formFactorFromName("WH-1000XM5"))
        assertEquals(FormFactor.HEADPHONES, HeadphoneLabels.formFactorFromName("Gear Headset"))
    }
}
