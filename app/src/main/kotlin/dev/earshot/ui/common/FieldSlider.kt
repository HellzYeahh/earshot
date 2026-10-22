@file:OptIn(ExperimentalMaterial3Api::class) // the thumb/track slots and SliderState

package dev.earshot.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.earshot.ui.theme.EarshotTheme

/**
 * Material's `Slider` with Field's parts: a flat square-ended track (olive fill on a sand
 * ground) and a bar-shaped orange thumb ringed in the card colour. Drag, keyboard and TalkBack
 * behaviour are Material's; only the drawing changes.
 */
@Composable
fun FieldSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    /** 4 dp in rows, 6 dp for the one control a card is about (the noise level). */
    trackHeight: Dp = 4.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        steps = steps,
        thumb = { FieldThumb(enabled = enabled) },
        track = { state -> FieldTrack(state = state, enabled = enabled, height = trackHeight) },
        valueRange = valueRange,
    )
}

@Composable
private fun FieldThumb(enabled: Boolean) {
    val bar = if (enabled) EarshotTheme.colors.accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        Modifier
            .size(width = 14.dp, height = 28.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(3.dp))
            .padding(2.dp)
            .background(bar, RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun FieldTrack(state: SliderState, enabled: Boolean, height: Dp) {
    val fill = if (enabled) EarshotTheme.colors.fill else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val ground = EarshotTheme.colors.track
    val span = state.valueRange.endInclusive - state.valueRange.start
    val fraction = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.fillMaxWidth().height(height)) {
        drawRect(ground)
        val filled = size.width * fraction
        val start = if (rtl) size.width - filled else 0f
        drawRect(fill, topLeft = Offset(start, 0f), size = Size(filled, size.height))
    }
}
