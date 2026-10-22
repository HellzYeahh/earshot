@file:OptIn(ExperimentalTextApi::class) // Font(variationSettings = …) for the variable JetBrains Mono

package dev.earshot.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.earshot.R

/*
 * Field type. Barlow Condensed carries the titles (the screens uppercase the short ones),
 * Barlow the running text, JetBrains Mono the small labels, values and hints. All three are
 * bundled under res/font (SIL OFL 1.1, licences in docs/reference/fonts): the app has no
 * INTERNET permission, so downloadable fonts are out.
 */

val BarlowCondensed: FontFamily = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

val Barlow: FontFamily = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
)

/** One variable file; the weight axis picks the instance (minSdk 28 renders variable fonts). */
val JetBrainsMono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jetbrains_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.jetbrains_mono, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

private fun condensed(size: TextUnit, line: TextUnit, weight: FontWeight = FontWeight.Bold, tracking: TextUnit = 0.02.em) =
    TextStyle(fontFamily = BarlowCondensed, fontWeight = weight, fontSize = size, lineHeight = line, letterSpacing = tracking)

private fun body(size: TextUnit, line: TextUnit, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Barlow, fontWeight = weight, fontSize = size, lineHeight = line, letterSpacing = 0.em)

private fun mono(size: TextUnit, line: TextUnit, tracking: TextUnit) =
    TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = size, lineHeight = line, letterSpacing = tracking)

/**
 * Body sizes stay a notch above Material's: the app is read at arm's length. Label styles are
 * monospace, so anything that carries a sentence uses a body style instead.
 */
val FieldTypography: Typography = Typography(
    displayLarge = condensed(56.sp, 60.sp, tracking = 0.01.em),
    displayMedium = condensed(44.sp, 48.sp, tracking = 0.01.em),
    displaySmall = condensed(36.sp, 40.sp, tracking = 0.01.em),
    headlineLarge = condensed(32.sp, 36.sp),
    headlineMedium = condensed(28.sp, 32.sp),
    headlineSmall = condensed(24.sp, 28.sp),
    titleLarge = condensed(26.sp, 30.sp),
    titleMedium = condensed(18.sp, 22.sp, FontWeight.SemiBold, 0.06.em),
    titleSmall = condensed(15.sp, 20.sp, FontWeight.SemiBold, 0.06.em),
    bodyLarge = body(17.sp, 24.sp, FontWeight.Medium),
    bodyMedium = body(15.sp, 21.sp),
    bodySmall = body(13.sp, 18.sp),
    labelLarge = body(15.sp, 20.sp, FontWeight.SemiBold),
    labelMedium = mono(12.sp, 16.sp, 0.04.em),
    labelSmall = mono(11.sp, 14.sp, 0.05.em),
)
