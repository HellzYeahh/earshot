package dev.earshot.ui.common

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The app picker's list handling: the search filter and the last list kept between opens. */
class AppPickerLogicTest {

    private val spotify = "Spotify" to "com.spotify.music"
    private val maps = "Maps" to "com.google.android.apps.maps"
    private val apps = listOf(maps, spotify)

    @BeforeTest fun reset() = AppPickerLogic.clear()

    @AfterTest fun tidy() = AppPickerLogic.clear()

    @Test fun `the first open has no list to show yet, so the picker shows its spinner`() {
        assertNull(AppPickerLogic.lastLoaded)
    }

    @Test fun `a loaded list is kept for the next open and replaced by a newer one`() {
        AppPickerLogic.keep(apps)
        assertEquals(apps, AppPickerLogic.lastLoaded)
        AppPickerLogic.keep(listOf(spotify))
        assertEquals(listOf(spotify), AppPickerLogic.lastLoaded)
    }

    @Test fun `a failed query never replaces a good list, on screen or for the next open`() {
        AppPickerLogic.keep(apps)
        AppPickerLogic.keep(emptyList())
        assertEquals(apps, AppPickerLogic.lastLoaded)
        assertEquals(apps, AppPickerLogic.shownAfterLoad(emptyList()))
    }

    @Test fun `with nothing ever found the picker falls back to typing a package name`() {
        assertEquals(emptyList(), AppPickerLogic.shownAfterLoad(emptyList()))
    }

    @Test fun `a fresh list wins over the kept one`() {
        AppPickerLogic.keep(apps)
        val fresh = listOf(maps)
        AppPickerLogic.keep(fresh)
        assertEquals(fresh, AppPickerLogic.shownAfterLoad(fresh))
    }

    @Test fun `search matches label or package, ignoring case, and a blank query shows all`() {
        assertEquals(apps, AppPickerLogic.filter(apps, "  "))
        assertEquals(listOf(spotify), AppPickerLogic.filter(apps, "spot"))
        assertEquals(listOf(maps), AppPickerLogic.filter(apps, "GOOGLE"))
        assertEquals(emptyList(), AppPickerLogic.filter(apps, "telegram"))
    }
}
