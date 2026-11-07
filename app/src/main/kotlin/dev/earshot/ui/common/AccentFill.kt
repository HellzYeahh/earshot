package dev.earshot.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A coloured halo under a lit thing (Aurora's chosen mode, its play button). A no-op where the
 * look has no glow ([glow] is transparent), so Field draws exactly what it always drew. Needs a
 * non-clipping shadow with its own colour, which Android supports from API 28 (our minSdk).
 * The shadow colour is passed opaque: Android multiplies its alpha by the system shadow alpha,
 * so a translucent colour would all but vanish. [intensity] (0..1) scales the spread, to fade it.
 */
fun Modifier.glowShadow(glow: Color, shape: Shape, elevation: Dp = 10.dp, intensity: Float = 1f): Modifier =
    if (glow.alpha == 0f || intensity <= 0f) this
    else shadow(elevation * intensity, shape, clip = false, ambientColor = glow.copy(alpha = 1f), spotColor = glow.copy(alpha = 1f))
