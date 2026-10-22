package dev.earshot.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.earshot.core.Appearance

/*
 * Field is the app's look (ui/theme/FieldPalette.kt, Type.kt). The "System colours" appearance
 * keeps the type, shapes and dot grid and only takes the palette from the wallpaper on
 * Android 12+; below that it is the same as Field.
 */

/** Squared-off: 8 dp cards, 4 dp chips, 12 dp dialogs and sheets. */
private val FieldShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

/** Accessor for the Field roles Material has no slot for, next to `MaterialTheme.colorScheme`. */
object EarshotTheme {
    val colors: EarshotColors
        @Composable @ReadOnlyComposable get() = LocalEarshotColors.current
}

@Composable
fun EarshotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appearance: Appearance = Appearance.FIELD,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamic = appearance == Appearance.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        dynamic -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> FieldDarkScheme
        else -> FieldLightScheme
    }
    val colors = when {
        dynamic -> EarshotColors.fromScheme(colorScheme)
        darkTheme -> FieldDarkColors
        else -> FieldLightColors
    }
    CompositionLocalProvider(LocalEarshotColors provides colors) {
        MaterialTheme(colorScheme = colorScheme, shapes = FieldShapes, typography = FieldTypography, content = content)
    }
}
