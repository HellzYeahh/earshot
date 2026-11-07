@file:OptIn(ExperimentalTextApi::class) // Font(variationSettings = …) for the two variable fonts

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
 * Aurora type. Sora, a wide geometric face, carries the titles and every short value or legend;
 * Plus Jakarta Sans carries the running text. Both are single variable files under res/font
 * (SIL OFL 1.1, licences in docs/reference/fonts; no INTERNET permission, so no downloadable
 * fonts), and the weight axis picks the instance. Case is as written: Aurora never capitalises.
 */

val Sora: FontFamily = FontFamily(
    Font(R.font.sora, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.sora, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.sora, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

val PlusJakartaSans: FontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

private fun sora(size: TextUnit, line: TextUnit, weight: FontWeight = FontWeight.SemiBold, tracking: TextUnit = (-0.01).em) =
    TextStyle(fontFamily = Sora, fontWeight = weight, fontSize = size, lineHeight = line, letterSpacing = tracking)

private fun jakarta(size: TextUnit, line: TextUnit, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = PlusJakartaSans, fontWeight = weight, fontSize = size, lineHeight = line, letterSpacing = 0.em)

/**
 * The same sizes as Field's (the screens were laid out against them). Label styles are Sora
 * and short, so a sentence still takes a body style.
 */
val AuroraTypography: Typography = Typography(
    displayLarge = sora(52.sp, 58.sp, tracking = (-0.02).em),
    displayMedium = sora(42.sp, 48.sp, tracking = (-0.02).em),
    displaySmall = sora(34.sp, 40.sp, tracking = (-0.02).em),
    headlineLarge = sora(30.sp, 36.sp),
    headlineMedium = sora(26.sp, 32.sp),
    headlineSmall = sora(22.sp, 28.sp),
    titleLarge = sora(22.sp, 28.sp),
    titleMedium = sora(16.sp, 21.sp),
    titleSmall = sora(14.sp, 19.sp),
    bodyLarge = jakarta(17.sp, 24.sp, FontWeight.Medium),
    bodyMedium = jakarta(15.sp, 21.sp),
    bodySmall = jakarta(13.sp, 18.sp),
    labelLarge = jakarta(15.sp, 20.sp, FontWeight.SemiBold),
    labelMedium = sora(12.sp, 16.sp, tracking = 0.em),
    labelSmall = sora(11.sp, 15.sp, FontWeight.Medium, tracking = 0.01.em),
)
