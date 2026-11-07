package dev.earshot.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * Aurora: frosted glass over deep ink, lit by an emerald-to-cyan glow (by day: mist, teal to
 * blue). Every lit thing (the chosen mode, the play button, switches, slider fills, the battery
 * dial) takes the same diagonal gradient. Cards are see-through ([EarshotColors.cardFill]) over
 * the corner glows; the Material roles stay opaque approximations of that glass so dialogs and
 * sheets never show the screen through them.
 *
 * Contrast (WCAG, measured, worst case = the very centre of the top-left glow): white on the day
 * gradient is 4.9:1 (teal) to 5.2:1 (blue); the night ink on the emerald-cyan gradient is 7.7:1
 * or better; muted text is 4.9:1 by day and 4.8:1 at night on a card or the bare backdrop (6 to 9
 * away from the glow), so every text pair is above 4.5:1. Graphics need 3:1: the day nav icon on
 * its tinted pill is 3.9:1, the off switch's muted knob 3.4:1 at worst, its outline 3.3:1 or better
 * (the switches sit on the right, away from the glow).
 *
 * The tertiary roles are the app's warning colour (low battery, WARNING banners, Experimental), so
 * they stay warm amber here too: cyan would read as one more part of the glow. `primary` is also
 * text (banner actions, links), so by day it is a darker teal than the gradient: 5:1 or better on
 * the banners and cards, 4.6:1 at the centre of a glow. Amber text is 5.8:1 or better on a card
 * by day and 4.6:1 on the bare glow.
 */

private val Mist = Color(0xFFEAF4F4)
private val InkDay = Color(0xFF0B2A2E)
// Muted text also sits directly on the corner glows, where a lighter muted tone would drop below 4.5:1.
private val MutedDay = Color(0xFF3F6165)
private val Teal = Color(0xFF08806A)
// Teal as text: the gradient's own teal falls just under 4.5:1 on the light glass.
private val TealText = Color(0xFF066A58)
private val Blue = Color(0xFF0E6FC0)

private val DeepInk = Color(0xFF07131A)
private val FrostNight = Color(0xFFE6F5F3)
private val MutedNight = Color(0xFFA3BDC1)
private val Emerald = Color(0xFF2EE6A6)
private val Cyan = Color(0xFF3AB8FF)

// ---- Light ------------------------------------------------------------------------------------

val AuroraLightScheme: ColorScheme = lightColorScheme(
    primary = TealText,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBFEBDF),
    onPrimaryContainer = Color(0xFF04382E),
    inversePrimary = Emerald,
    secondary = Blue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E5F7),
    onSecondaryContainer = Color(0xFF0A3A68),
    tertiary = Color(0xFF8F4F00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE4C2),
    onTertiaryContainer = Color(0xFF3D2200),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Mist,
    onBackground = InkDay,
    surface = Mist,
    onSurface = InkDay,
    surfaceVariant = Color(0xFFD3E3E4),
    onSurfaceVariant = MutedDay,
    surfaceTint = Teal,
    inverseSurface = Color(0xFF0E2A30),
    inverseOnSurface = FrostNight,
    outline = Color(0xFF6A898D),
    outlineVariant = Color(0xFFC9DBDC),
    scrim = Color.Black,
    surfaceBright = Color(0xFFF7FBFB),
    surfaceDim = Color(0xFFD6E5E5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F8F8),
    surfaceContainer = Color(0xFFF6FAFA),
    surfaceContainerHigh = Color(0xFFEDF5F5),
    surfaceContainerHighest = Color(0xFFE2EEEE),
)

private val DayGradient = Brush.linearGradient(listOf(Teal, Blue))

val AuroraLightColors: EarshotColors = EarshotColors(
    accent = Teal,
    onAccent = Color.White,
    dial = Teal,
    fill = Teal,
    track = Color(0x1A0B2A2E), // ink at 10 %
    hairline = Color(0x1F0B2A2E), // ink at 12 %
    chip = Color(0x1F08806A), // teal at 12 %
    onChip = Color(0xFF06594A),
    control = Teal,
    onControl = Color.White,
    dotGrid = Color.Transparent,
    ok = Color(0xFF1B7F3B),
    accentBrush = DayGradient,
    controlBrush = DayGradient,
    fillBrush = DayGradient,
    gradientFrom = Teal,
    gradientTo = Blue,
    glow = Color(0x470E6FC0), // blue at 28 %
    cardFill = Color(0xB8FFFFFF), // white at 72 %
    navIndicator = Color(0x2408806A), // teal at 14 %
    onNavIndicator = Teal,
    glows = listOf(
        BackdropGlow(Color(0x6134D6A5), x = 0f, y = 0f, radius = 380.dp),
        BackdropGlow(Color(0x4D4CA6FF), x = 1f, y = 0.34f, radius = 360.dp),
        BackdropGlow(Color(0x3834D6A5), x = 0.2f, y = 1f, radius = 420.dp),
    ),
)

// ---- Dark -------------------------------------------------------------------------------------

val AuroraDarkScheme: ColorScheme = darkColorScheme(
    primary = Emerald,
    onPrimary = Color(0xFF04211A),
    primaryContainer = Color(0xFF0C4A3B),
    onPrimaryContainer = Color(0xFF8FF3CD),
    inversePrimary = TealText,
    secondary = Cyan,
    onSecondary = Color(0xFF031C2E),
    secondaryContainer = Color(0xFF0D3A57),
    onSecondaryContainer = Color(0xFFBFE6FF),
    tertiary = Color(0xFFFFC27A),
    onTertiary = Color(0xFF3D2200),
    tertiaryContainer = Color(0xFF553508),
    onTertiaryContainer = Color(0xFFFFE2BD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = DeepInk,
    onBackground = FrostNight,
    surface = DeepInk,
    onSurface = FrostNight,
    surfaceVariant = Color(0xFF1B2D36),
    onSurfaceVariant = MutedNight,
    surfaceTint = Emerald,
    inverseSurface = FrostNight,
    inverseOnSurface = InkDay,
    outline = Color(0xFF55747A),
    outlineVariant = Color(0xFF2A3E48),
    scrim = Color.Black,
    surfaceBright = Color(0xFF1F323C),
    surfaceDim = DeepInk,
    surfaceContainerLowest = Color(0xFF040C11),
    surfaceContainerLow = Color(0xFF0C1920),
    surfaceContainer = Color(0xFF112029),
    surfaceContainerHigh = Color(0xFF172A34),
    surfaceContainerHighest = Color(0xFF1E343F),
)

private val NightGradient = Brush.linearGradient(listOf(Emerald, Cyan))

val AuroraDarkColors: EarshotColors = EarshotColors(
    accent = Emerald,
    onAccent = Color(0xFF04211A),
    dial = Emerald,
    fill = Emerald,
    track = Color(0x21FFFFFF), // white at 13 %
    hairline = Color(0x1CFFFFFF), // white at 11 %
    chip = Color(0x242EE6A6), // emerald at 14 %
    onChip = Color(0xFF8FF3CD),
    control = Emerald,
    onControl = Color(0xFF04211A),
    dotGrid = Color.Transparent,
    ok = Color(0xFF7DD99A),
    accentBrush = NightGradient,
    controlBrush = NightGradient,
    fillBrush = NightGradient,
    gradientFrom = Emerald,
    gradientTo = Cyan,
    glow = Color(0x592EE6A6), // emerald at 35 %
    cardFill = Color(0x0FFFFFFF), // white at 6 %
    navIndicator = Color(0x2E2EE6A6), // emerald at 18 %
    onNavIndicator = Emerald,
    glows = listOf(
        // 22 % at the centre, not 30: muted text on a card there must stay above 4.5:1.
        BackdropGlow(Color(0x382EE6A6), x = 0f, y = 0f, radius = 400.dp),
        BackdropGlow(Color(0x383AB8FF), x = 1f, y = 0.32f, radius = 380.dp),
        BackdropGlow(Color(0x262EE6A6), x = 0.2f, y = 1f, radius = 440.dp),
    ),
)
