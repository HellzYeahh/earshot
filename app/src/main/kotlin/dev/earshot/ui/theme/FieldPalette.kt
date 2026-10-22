package dev.earshot.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/*
 * Field: olive, sand and ink, with safety orange as the one indicator colour. Light mode is
 * ink-led (the play button, the chosen segment and the battery dial are ink); dark mode is
 * orange-led, the way a recorder's status lamp reads in the dark.
 *
 * Material roles that components read on their own (buttons, text fields, progress, dialogs)
 * come from the ColorScheme; the handful of Field-specific roles that Material has no slot
 * for live in [EarshotColors].
 */

/** The roles Material 3 has no name for. Read through `EarshotTheme.colors`. */
@Immutable
data class EarshotColors(
    /** Safety orange: selection fills, slider thumbs, the switch when on, the nav indicator. */
    val accent: Color,
    val onAccent: Color,
    /** The battery dial at a healthy level. */
    val dial: Color,
    /** The active part of a slider track. */
    val fill: Color,
    /** The inactive part of a track; the segmented control's ground. */
    val track: Color,
    /** 1 dp borders on cards and controls. */
    val hairline: Color,
    /** Status chips: ink on sand by day, sand on ink at night. */
    val chip: Color,
    val onChip: Color,
    /** The play button and the chosen segment. */
    val control: Color,
    val onControl: Color,
    /** The dot grid behind every screen. */
    val dotGrid: Color,
    /** "It worked" text and ticks (a detected button). Material has no success role. */
    val ok: Color,
) {
    companion object {
        /** The same roles derived from a wallpaper (Material You) scheme, for the System colours option. */
        fun fromScheme(scheme: ColorScheme): EarshotColors = EarshotColors(
            accent = scheme.primary,
            onAccent = scheme.onPrimary,
            dial = scheme.primary,
            fill = scheme.primary,
            track = scheme.surfaceVariant,
            hairline = scheme.outlineVariant,
            chip = scheme.inverseSurface,
            onChip = scheme.inverseOnSurface,
            control = scheme.primary,
            onControl = scheme.onPrimary,
            dotGrid = scheme.onSurface.copy(alpha = 0.08f),
            // A wallpaper scheme may have no green at all: keep a fixed one, light or dark by the surface.
            ok = if (scheme.surface.luminance() < 0.5f) Color(0xFF7DD99A) else Color(0xFF1B7F3B),
        )
    }
}

val LocalEarshotColors = staticCompositionLocalOf { FieldLightColors }

// ---- Light ------------------------------------------------------------------------------------

private val Sand = Color(0xFFD8DCCD)
private val SandCard = Color(0xFFECEFE3)
private val Ink = Color(0xFF1C2316)
private val InkMuted = Color(0xFF4F5A44)
private val Olive = Color(0xFF3F4D2B)
private val OrangeDay = Color(0xFFFF6A1F)
private val OnOrangeDay = Color(0xFF17110A)
private val HairlineDay = Color(0xFFA6AE97)

val FieldLightScheme: ColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = SandCard,
    primaryContainer = OrangeDay,
    onPrimaryContainer = OnOrangeDay,
    inversePrimary = Color(0xFFA9BC76),
    secondary = Olive,
    onSecondary = SandCard,
    secondaryContainer = Color(0xFFAEB99A),
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF8A3A0E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF6C9A8),
    onTertiaryContainer = Color(0xFF3A1600),
    error = Color(0xFF9E2A1C),
    onError = Color.White,
    errorContainer = Color(0xFFF2C5BD),
    onErrorContainer = Color(0xFF3C0A05),
    background = Sand,
    onBackground = Ink,
    surface = Sand,
    onSurface = Ink,
    surfaceVariant = Color(0xFFC4CAB6),
    onSurfaceVariant = InkMuted,
    surfaceTint = Olive,
    inverseSurface = Ink,
    inverseOnSurface = Sand,
    outline = Color(0xFF7A8470),
    outlineVariant = HairlineDay,
    scrim = Color.Black,
    surfaceBright = SandCard,
    surfaceDim = Color(0xFFC9CEBE),
    surfaceContainerLowest = Color(0xFFF3F5EC),
    surfaceContainerLow = Color(0xFFE4E8DB),
    surfaceContainer = SandCard,
    surfaceContainerHigh = Color(0xFFE2E6D8),
    surfaceContainerHighest = Color(0xFFD0D5C4),
)

val FieldLightColors: EarshotColors = EarshotColors(
    accent = OrangeDay,
    onAccent = OnOrangeDay,
    dial = Ink,
    fill = Olive,
    track = Color(0xFFBDC4AE),
    hairline = HairlineDay,
    chip = Ink,
    onChip = Sand,
    control = Ink,
    onControl = SandCard,
    dotGrid = Ink.copy(alpha = 0.14f),
    ok = Color(0xFF2F6B2C),
)

// ---- Dark -------------------------------------------------------------------------------------

private val Night = Color(0xFF0F130E)
private val NightCard = Color(0xFF171D15)
private val Bone = Color(0xFFE4E9D6)
private val BoneMuted = Color(0xFF9AA58A)
private val OliveLight = Color(0xFFA9BC76)
private val OrangeNight = Color(0xFFFF7A2E)
private val OnOrangeNight = Color(0xFF14100A)
private val HairlineNight = Color(0xFF2E382A)

val FieldDarkScheme: ColorScheme = darkColorScheme(
    primary = OrangeNight,
    onPrimary = OnOrangeNight,
    primaryContainer = OrangeNight,
    onPrimaryContainer = OnOrangeNight,
    inversePrimary = Ink,
    secondary = OliveLight,
    onSecondary = Ink,
    secondaryContainer = Color(0xFF3A4A2E),
    onSecondaryContainer = Color(0xFFDCE6C4),
    tertiary = Color(0xFFFFB38A),
    onTertiary = Color(0xFF4A1F00),
    tertiaryContainer = Color(0xFF6B3312),
    onTertiaryContainer = Color(0xFFFFDCC7),
    error = Color(0xFFFFB4A8),
    onError = Color(0xFF5C0E05),
    errorContainer = Color(0xFF7A2A20),
    onErrorContainer = Color(0xFFFFDAD4),
    background = Night,
    onBackground = Bone,
    surface = Night,
    onSurface = Bone,
    surfaceVariant = Color(0xFF2A3427),
    onSurfaceVariant = BoneMuted,
    surfaceTint = OliveLight,
    inverseSurface = Bone,
    inverseOnSurface = Ink,
    outline = Color(0xFF6B7862),
    outlineVariant = HairlineNight,
    scrim = Color.Black,
    surfaceBright = Color(0xFF2E382A),
    surfaceDim = Night,
    surfaceContainerLowest = Color(0xFF0A0D09),
    surfaceContainerLow = Color(0xFF131812),
    surfaceContainer = NightCard,
    surfaceContainerHigh = Color(0xFF1E2520),
    surfaceContainerHighest = Color(0xFF262E27),
)

val FieldDarkColors: EarshotColors = EarshotColors(
    accent = OrangeNight,
    onAccent = OnOrangeNight,
    dial = OrangeNight,
    fill = OliveLight,
    track = Color(0xFF2A3427),
    hairline = HairlineNight,
    chip = Bone,
    onChip = Night,
    control = OrangeNight,
    onControl = OnOrangeNight,
    dotGrid = Bone.copy(alpha = 0.08f),
    ok = Color(0xFF9DCB7A),
)
