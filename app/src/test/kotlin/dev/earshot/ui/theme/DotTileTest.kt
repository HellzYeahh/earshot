package dev.earshot.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/** The backdrop's dot grid is one repeated tile; its side is the spacing in whole pixels. */
class DotTileTest {

    @Test
    fun `the tile is the spacing rounded to whole pixels`() {
        // 14 dp at a 2.625x density: 36.75 px becomes 37, under 1 % wider.
        assertEquals(37, DotTile.sidePx(36.75f))
        assertEquals(36, DotTile.sidePx(36.4f))
        assertEquals(42, DotTile.sidePx(42f))
    }

    @Test
    fun `the tile never shrinks below one pixel`() {
        assertEquals(1, DotTile.sidePx(0.3f))
        assertEquals(1, DotTile.sidePx(0f))
    }
}
