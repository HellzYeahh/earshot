package dev.earshot.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.earshot.ui.theme.EarshotTheme

/**
 * The app's switch. Field: a squared 48 × 26 dp track with a square knob, orange when on.
 * Aurora: a 50 × 30 dp pill with a round white knob, the accent gradient when on. Same contract
 * as Material's `Switch`: pass `onCheckedChange = null` when the enclosing row is already
 * `toggleable` (as [SwitchRow] does), so TalkBack announces the row once.
 */
@Composable
fun FieldSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = EarshotTheme.colors
    val scheme = MaterialTheme.colorScheme
    val square = EarshotTheme.style.squareControls
    val trackWidth = if (square) 48.dp else 50.dp
    val trackHeight = if (square) 26.dp else 30.dp
    val knobSize = if (square) 18.dp else 22.dp
    val inset = if (square) 3.dp else 4.dp
    val borderWidth = if (square) 1.dp else 0.dp
    val trackShape = if (square) RoundedCornerShape(5.dp) else CircleShape
    val knobShape = if (square) RoundedCornerShape(3.dp) else CircleShape
    val offTrack = if (square) scheme.surfaceVariant else colors.track
    val track by animateColorAsState(if (checked) colors.accent else offTrack, label = "switchTrack")
    // On: Field's dark knob on orange, Aurora's white on the gradient. Off: the muted text colour in
    // both, because a white knob on Aurora's pale glass track is 1.3:1 and the switch looks missing.
    val onKnob = if (square) colors.onAccent else Color.White
    val knob by animateColorAsState(if (checked) onKnob else scheme.onSurfaceVariant, label = "switchKnob")
    // Aurora's gradient fades in over the solid track instead of popping.
    val lit by animateFloatAsState(if (checked && !square) 1f else 0f, label = "switchLit")
    // Room for the knob: the track minus both borders and both insets, minus the knob itself.
    val travel by animateDpAsState(
        if (checked) trackWidth - borderWidth * 2 - inset * 2 - knobSize else 0.dp,
        label = "switchKnobOffset",
    )
    val interactive = if (onCheckedChange != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    } else Modifier
    // Field's outline (ink when on, hairline when off). Aurora outlines only the off switch, so its
    // edge is visible on glass (the lit track is its own boundary); the border draws inside the size.
    val outlined = when {
        square -> Modifier.border(borderWidth, if (checked) scheme.onSurface else colors.hairline, trackShape)
        !checked -> Modifier.border(1.dp, scheme.outline, trackShape)
        else -> Modifier
    }
    val filled = Modifier
        .background(track, trackShape)
        .then(if (lit > 0f) Modifier.background(colors.accentBrush, trackShape, alpha = lit) else Modifier)
    Box(modifier.then(interactive), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .alpha(if (enabled) 1f else 0.38f)
                .size(width = trackWidth, height = trackHeight)
                .then(outlined)
                .then(filled)
                .padding(inset),
        ) {
            Box(Modifier.offset(x = travel).size(knobSize).background(knob, knobShape))
        }
    }
}
