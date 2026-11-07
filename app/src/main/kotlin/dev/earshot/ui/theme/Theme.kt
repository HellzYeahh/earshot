package dev.earshot.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.earshot.core.Appearance

/*
 * Field is the app's look (ui/theme/FieldPalette.kt, Type.kt); Aurora is the second one
 * (AuroraPalette.kt, AuroraType.kt). The "System colours" appearance keeps Field's type, shapes
 * and dot grid and only takes the palette from the wallpaper on Android 12+; below that it is
 * the same as Field.
 */

/** Squared-off: 8 dp cards, 4 dp chips, 12 dp dialogs and sheets. */
private val FieldShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

/** Soft: 24 dp cards, buttons and mode chips, 16 dp small chips, 32 dp dialogs and sheets. */
private val AuroraShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Accessor for the roles Material has no slot for, next to `MaterialTheme.colorScheme`. */
object EarshotTheme {
    val colors: EarshotColors
        @Composable @ReadOnlyComposable get() = LocalEarshotColors.current

    val style: EarshotStyle
        @Composable @ReadOnlyComposable get() = LocalEarshotStyle.current

    /** A card's fill: Aurora's glass where the look has one, else the scheme's `surfaceContainer`. */
    val cardFill: Color
        @Composable @ReadOnlyComposable get() = colors.cardFill.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.surfaceContainer

    /** The outline of a chosen chip or tile: Field inks it; Aurora lets the accent fill stand alone. */
    val chosenOutline: Color
        @Composable @ReadOnlyComposable get() = if (style.squareControls) MaterialTheme.colorScheme.onSurface else colors.accent
}

@Composable
fun EarshotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appearance: Appearance = Appearance.FIELD,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val aurora = appearance == Appearance.AURORA
    val dynamic = appearance == Appearance.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme: ColorScheme = when {
        aurora -> if (darkTheme) AuroraDarkScheme else AuroraLightScheme
        dynamic -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> FieldDarkScheme
        else -> FieldLightScheme
    }
    val colors = when {
        aurora -> if (darkTheme) AuroraDarkColors else AuroraLightColors
        dynamic -> EarshotColors.fromScheme(colorScheme)
        darkTheme -> FieldDarkColors
        else -> FieldLightColors
    }
    val style = if (aurora) EarshotStyle.Aurora else EarshotStyle.Field
    val shapes: Shapes = if (aurora) AuroraShapes else FieldShapes
    val typography: Typography = if (aurora) AuroraTypography else FieldTypography
    // Text with no colour of its own (a heading outside any card) reads LocalContentColor, whose
    // default is black: unreadable on a dark screen unless the theme sets it.
    CompositionLocalProvider(
        LocalEarshotColors provides colors,
        LocalEarshotStyle provides style,
        LocalContentColor provides colorScheme.onBackground,
    ) {
        MaterialTheme(colorScheme = colorScheme, shapes = shapes, typography = typography, content = content)
    }
}
