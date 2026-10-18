package dev.earshot.ui

import dev.earshot.core.HeadphoneCandidate
import dev.earshot.core.HeadphoneState
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.ui.common.SettingsLoad
import dev.earshot.ui.home.DeviceSwitcherList
import dev.earshot.ui.more.AutoOffOptions
import dev.earshot.ui.sound.HeadphoneEqRange
import dev.earshot.ui.whatsnew.WhatsNew
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure decisions behind the 0.4.0 screens: what's new, auto-off, EQ range, switcher, loading. */
class UiLogicTest {

    @Test
    fun `what's new shows after an update, never on a fresh install, once per release`() {
        assertEquals(WhatsNew.Decision.MARK_SEEN, WhatsNew.decide(lastSeen = null, freshInstall = true))
        // 0.3.x never stored anything: an update from it shows the sheet.
        assertEquals(WhatsNew.Decision.SHOW, WhatsNew.decide(lastSeen = null, freshInstall = false))
        assertEquals(WhatsNew.Decision.SHOW, WhatsNew.decide(lastSeen = "0.3.9", freshInstall = false))
        assertEquals(WhatsNew.Decision.NOTHING, WhatsNew.decide(lastSeen = WhatsNew.RELEASE, freshInstall = false))
        assertEquals(WhatsNew.Decision.NOTHING, WhatsNew.decide(lastSeen = WhatsNew.RELEASE, freshInstall = true))
    }

    @Test
    fun `auto-off offers the standard list plus an odd current value`() {
        // The Bose app's list (protocol AutoOffOptions.MINUTES), Never first.
        assertEquals(listOf(0, 5, 20, 40, 60, 180, 1440), AutoOffOptions.options(20))
        assertEquals(listOf(0, 5, 20, 40, 60, 90, 180, 1440), AutoOffOptions.options(90))
        assertEquals(listOf(0, 5, 20, 40, 60, 180, 1440), AutoOffOptions.options(null))
        assertEquals(AutoOffOptions.Label.Never, AutoOffOptions.label(0))
        assertEquals(AutoOffOptions.Label.Hours(3), AutoOffOptions.label(180))
        assertEquals(AutoOffOptions.Label.Hours(24), AutoOffOptions.label(1440))
        assertEquals(AutoOffOptions.Label.Minutes(90), AutoOffOptions.label(90))
    }

    @Test
    fun `headphone EQ range follows the device inside what HeadphoneEq can hold`() {
        assertEquals(-10..10, HeadphoneEqRange.of(null))
        assertEquals(-6..6, HeadphoneEqRange.of(-6..6))
        assertEquals(-10..10, HeadphoneEqRange.of(-12..12))
        assertEquals(-10..10, HeadphoneEqRange.of(IntRange.EMPTY))
        assertEquals(19, HeadphoneEqRange.steps(-10..10))
        assertEquals(11, HeadphoneEqRange.steps(-6..6))
    }

    @Test
    fun `switcher lists each Bose product once and marks the current one`() {
        val classic = HeadphoneCandidate("AC:00:00:00:00:01", "Bose QC Ultra 2 Earbuds", looksLikeBose = true, isAudioDevice = true)
        val leTwin = HeadphoneCandidate("AC:00:00:00:00:02", "LE-Bose QC Ultra 2 Earbuds", looksLikeBose = true, isAudioDevice = true)
        val headphones = HeadphoneCandidate("68:00:00:00:00:03", "Bose QC Ultra Headphones", looksLikeBose = true, isAudioDevice = true)
        val car = HeadphoneCandidate("00:00:00:00:00:04", "Car stereo", looksLikeBose = false, isAudioDevice = true)

        val none = DeviceSwitcherList.build(listOf(leTwin, classic, headphones, car), currentAddress = null)
        assertEquals(listOf("Bose QC Ultra 2 Earbuds", "Bose QC Ultra Headphones"), none.bose.map { it.label })
        assertEquals(classic, none.bose.first().candidate) // the Classic twin carries the BMAP channel
        assertTrue(none.otherAudio.isEmpty())

        val onLe = DeviceSwitcherList.build(listOf(classic, leTwin, headphones), currentAddress = leTwin.address)
        assertEquals(leTwin, onLe.bose.first().candidate)
        assertTrue(onLe.bose.first().isCurrent)
        assertFalse(onLe.bose[1].isCurrent)

        // Renamed headphones: nothing looks like Bose, so other audio devices are offered.
        val renamed = DeviceSwitcherList.build(listOf(car), currentAddress = null)
        assertTrue(renamed.bose.isEmpty())
        assertEquals(listOf("Car stereo"), renamed.otherAudio.map { it.label })
    }

    @Test
    fun `settings count as loaded once the refresh has read the firmware version`() {
        assertTrue(SettingsLoad.looksRefreshed(FakeHeadphoneRepository.connectedState()))
        assertFalse(SettingsLoad.looksRefreshed(HeadphoneState()))
        val connectedNoInfo = FakeHeadphoneRepository.connectedState().copy(info = null)
        assertFalse(SettingsLoad.looksRefreshed(connectedNoInfo))
    }
}
