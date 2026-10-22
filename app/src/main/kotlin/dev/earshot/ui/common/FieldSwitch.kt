package dev.earshot.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.earshot.ui.theme.EarshotTheme

/**
 * Field's switch: a squared 48 × 26 dp track with a square knob, orange when on. Same contract
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
    val track by animateColorAsState(if (checked) colors.accent else scheme.surfaceVariant, label = "switchTrack")
    val knob by animateColorAsState(if (checked) colors.onAccent else scheme.onSurfaceVariant, label = "switchKnob")
    val border = if (checked) scheme.onSurface else colors.hairline
    // 48 wide, minus the 1 dp border and 3 dp padding each side, minus the 18 dp knob.
    val travel by animateDpAsState(if (checked) 22.dp else 0.dp, label = "switchKnobOffset")
    val trackShape = RoundedCornerShape(5.dp)
    val interactive = if (onCheckedChange != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    } else Modifier
    Box(modifier.then(interactive), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .alpha(if (enabled) 1f else 0.38f)
                .size(width = 48.dp, height = 26.dp)
                .border(1.dp, border, trackShape)
                .background(track, trackShape)
                .padding(3.dp),
        ) {
            Box(Modifier.offset(x = travel).size(18.dp).background(knob, RoundedCornerShape(3.dp)))
        }
    }
}
