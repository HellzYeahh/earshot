package dev.earshot.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How a look draws the things Material has no word for: the shape of its controls, its backdrop
 * and whether short titles are set in capitals. Colours live in [EarshotColors], shapes in the
 * Material `Shapes`, fonts in the `Typography`; this is the rest. Read it through
 * `EarshotTheme.style`.
 */
@Immutable
data class EarshotStyle(
    /** Squared switch, bar slider thumb, 6 dp nav indicator (Field) or round ones (Aurora). */
    val squareControls: Boolean,
    /** The battery dial wears tick marks and square ends (Field); Aurora draws a plain round arc. */
    val tickDial: Boolean,
    /** Short titles and legends in capitals (Field); Aurora keeps the case the string was written in. */
    val capsLegends: Boolean,
    val backdrop: Backdrop,
) {
    enum class Backdrop {
        /** A faint dot grid, like a recorder's panel. */
        DOTS,

        /** Soft colour glows in the corners, from [EarshotColors.glows]. */
        GLOW,
    }

    companion object {
        val Field = EarshotStyle(squareControls = true, tickDial = true, capsLegends = true, backdrop = Backdrop.DOTS)
        val Aurora = EarshotStyle(squareControls = false, tickDial = false, capsLegends = false, backdrop = Backdrop.GLOW)
    }
}

val LocalEarshotStyle = staticCompositionLocalOf { EarshotStyle.Field }

/**
 * A short title, legend or name as the current look sets it: in capitals for Field (Compose has
 * no text-transform, so the screens call this), unchanged for Aurora. For display only: never
 * use it to normalise data (Bluetooth addresses keep `uppercase()`).
 */
@Composable
@ReadOnlyComposable
fun String.legend(): String = if (EarshotTheme.style.capsLegends) uppercase() else this
