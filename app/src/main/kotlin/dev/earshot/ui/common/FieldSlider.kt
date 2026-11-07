@file:OptIn(ExperimentalMaterial3Api::class) // the thumb/track slots and SliderState

package dev.earshot.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.earshot.ui.theme.EarshotTheme

/**
 * Material's `Slider` with the app's own parts. Field: a flat square-ended track (olive fill on
 * a sand ground) and a bar-shaped orange thumb ringed in the card colour. Aurora: a round
 * track filled with the accent gradient and a white round thumb in a soft glow. Drag, keyboard
 * and TalkBack behaviour are Material's; only the drawing changes.
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
    /** 4 dp in rows, 6 dp for the one control a card is about (the noise level); Aurora adds 2 dp. */
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
    if (EarshotTheme.style.squareControls) {
        val bar = if (enabled) EarshotTheme.colors.accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        Box(
            Modifier
                .size(width = 14.dp, height = 28.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(3.dp))
                .padding(2.dp)
                .background(bar, RoundedCornerShape(2.dp)),
        )
    } else {
        // A 20 dp white dot with a 2 dp accent ring (white on white glass is 1:1), inside a 28 dp
        // halo in the look's glow colour.
        val disabled = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        val dot = if (enabled) Color.White else disabled
        val ring = if (enabled) EarshotTheme.colors.accent else disabled
        val halo = if (enabled) EarshotTheme.colors.glow else Color.Transparent
        Box(Modifier.size(28.dp).background(halo, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(20.dp).shadow(2.dp, CircleShape).background(dot, CircleShape).border(2.dp, ring, CircleShape))
        }
    }
}

@Composable
private fun FieldTrack(state: SliderState, enabled: Boolean, height: Dp) {
    val colors = EarshotTheme.colors
    val square = EarshotTheme.style.squareControls
    val disabled = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val fill = if (enabled) colors.fill else disabled
    val fillBrush = if (enabled) colors.fillBrush else SolidColor(disabled)
    val ground = colors.track
    val span = state.valueRange.endInclusive - state.valueRange.start
    val fraction = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.fillMaxWidth().height(if (square) height else height + 2.dp)) {
        // Aurora's thumb centre never reaches the ends (14 dp in, half its 28 dp size), so the fill
        // follows the thumb instead of overrunning it. Field's track is unchanged (no inset).
        val inset = if (square) 0f else 14.dp.toPx()
        val filled = inset + (size.width - 2 * inset) * fraction
        val start = if (rtl) size.width - filled else 0f
        if (square) {
            drawRect(ground)
            drawRect(fill, topLeft = Offset(start, 0f), size = Size(filled, size.height))
        } else {
            val corner = CornerRadius(size.height / 2)
            drawRoundRect(ground, cornerRadius = corner)
            drawRoundRect(fillBrush, topLeft = Offset(start, 0f), size = Size(filled, size.height), cornerRadius = corner)
        }
    }
}
