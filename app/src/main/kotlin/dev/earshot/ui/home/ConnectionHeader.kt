package dev.earshot.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Earbuds
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.BatteryLevel
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneState
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard

/**
 * Top of Home: the user's name for the product (big), the model and generation, the status and
 * time left, and the battery: one ring for headphones, L / R / case for earbuds. Tapping the
 * card opens the device switcher ([onSwitch]).
 */
@Composable
fun ConnectionHeader(
    state: HeadphoneState,
    onRefresh: () -> Unit,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = state.isConnected
    val formFactor = HeadphoneLabels.formFactor(state)
    val buds = HeadphoneLabels.isEarbuds(formFactor)
    val budBatteries = connected && HeadphoneLabels.hasBudBatteries(state)
    val name = HeadphoneLabels.displayName(state)
        ?: stringResource(if (buds) R.string.device_generic_name_buds else R.string.device_generic_name)
    // The model line is redundant when the Bluetooth name already says the same thing.
    val model = HeadphoneLabels.modelLine(state.info)?.takeUnless { it.equals(name, ignoreCase = true) }
    val status = listOfNotNull(
        statusLabel(state.connection),
        if (connected) HeadphoneLabels.minutesLeft(state)?.let { timeLeftLabel(it) } else null,
    ).joinToString(" · ")
    val switchLabel = stringResource(R.string.header_switch_action)
    val shape = RoundedCornerShape(20.dp)

    SectionCard(
        modifier = modifier
            .clip(shape)
            .clickable(onClickLabel = switchLabel, role = Role.Button, onClick = onSwitch),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (budBatteries) {
                FormFactorBadge(formFactor)
            } else {
                BatteryRing(percent = if (connected) state.batteryPercent else null, charging = state.isCharging == true)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Hints that the name opens the switcher, like a dropdown title.
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
                if (model != null) {
                    MetaLine(text = model, icon = formFactorIcon(formFactor), iconDescription = formFactorLabel(formFactor))
                }
                MetaLine(
                    text = status,
                    icon = if (model == null) formFactorIcon(formFactor) else null,
                    iconDescription = formFactorLabel(formFactor),
                )
            }
            if (connected) {
                if (state.isBusy) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                }
            }
        }
        if (budBatteries) {
            Spacer(Modifier.height(12.dp))
            BudBatteries(state)
        }
    }
}

@Composable
private fun MetaLine(text: String, icon: ImageVector?, iconDescription: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        if (icon != null) {
            Icon(icon, contentDescription = iconDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Earbuds: the battery moves to the row of small rings below, so the big slot shows what they are. */
@Composable
private fun FormFactorBadge(formFactor: FormFactor) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(72.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                formFactorIcon(formFactor),
                contentDescription = formFactorLabel(formFactor),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/** Left, right and the case. The case figure is only as fresh as the last time the buds were docked. */
@Composable
private fun BudBatteries(state: HeadphoneState) {
    val b = state.battery ?: return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        BudBattery(
            level = b.left,
            label = stringResource(R.string.battery_left_short),
            description = b.left?.percent?.let { stringResource(R.string.battery_left_percent, it) } ?: stringResource(R.string.battery_left_unknown),
            modifier = Modifier.weight(1f),
        )
        BudBattery(
            level = b.right,
            label = stringResource(R.string.battery_right_short),
            description = b.right?.percent?.let { stringResource(R.string.battery_right_percent, it) } ?: stringResource(R.string.battery_right_unknown),
            modifier = Modifier.weight(1f),
        )
        if (b.case != null) {
            BudBattery(
                level = b.case,
                label = stringResource(R.string.battery_case_last_known),
                description = b.case.percent?.let { stringResource(R.string.battery_case_percent, it) } ?: stringResource(R.string.battery_case_unknown),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BudBattery(level: BatteryLevel?, label: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.clearAndSetSemantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
        BatteryRing(percent = level?.percent, charging = false, size = 52.dp, stroke = 5.dp, textStyle = MaterialTheme.typography.labelLarge)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
fun formFactorIcon(formFactor: FormFactor): ImageVector = when (formFactor) {
    FormFactor.HEADPHONES -> Icons.Default.Headphones
    FormFactor.EARBUDS, FormFactor.OPEN_EARBUDS -> Icons.Default.Earbuds
}

@Composable
fun formFactorLabel(formFactor: FormFactor): String = stringResource(
    when (formFactor) {
        FormFactor.HEADPHONES -> R.string.form_headphones
        FormFactor.EARBUDS -> R.string.form_earbuds
        FormFactor.OPEN_EARBUDS -> R.string.form_open_earbuds
    },
)

/** "≈ 45 min left" below an hour, else whole hours ("≈ 12 h left"). */
@Composable
private fun timeLeftLabel(minutes: Int): String =
    if (minutes < 60) stringResource(R.string.header_time_left_minutes, minutes.coerceAtLeast(1))
    else stringResource(R.string.header_time_left_hours, (minutes + 30) / 60)

@Composable
private fun statusLabel(connection: ConnectionState): String = when (connection.status) {
    ConnectionStatus.CONNECTED -> stringResource(R.string.status_connected)
    ConnectionStatus.CONNECTING -> stringResource(R.string.status_connecting)
    ConnectionStatus.DISCONNECTED -> connection.message ?: stringResource(R.string.status_disconnected)
    ConnectionStatus.NO_PAIRED_DEVICE -> stringResource(R.string.status_no_paired_device)
    ConnectionStatus.NO_PERMISSION -> stringResource(R.string.status_no_permission)
}

/** Circular battery gauge; `percent == null` draws an empty ring. */
@Composable
fun BatteryRing(
    percent: Int?,
    charging: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    stroke: Dp = 7.dp,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val fraction by animateFloatAsState(
        targetValue = (percent ?: 0).coerceIn(0, 100) / 100f,
        animationSpec = tween(600),
        label = "battery",
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = when {
        percent == null -> MaterialTheme.colorScheme.outline
        percent <= 15 -> MaterialTheme.colorScheme.error
        percent <= 30 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val description = when {
        percent == null -> stringResource(R.string.battery_unknown)
        charging -> stringResource(R.string.battery_charging_percent, percent)
        else -> stringResource(R.string.battery_percent, percent)
    }
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            drawArc(track, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
            if (percent != null) {
                drawArc(fill, startAngle = 135f, sweepAngle = 270f * fraction, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (charging) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = fill, modifier = Modifier.size(16.dp))
            }
            Text(
                if (percent == null) "–" else "$percent",
                style = textStyle,
                color = if (percent == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ---- Previews ---------------------------------------------------------------------------------

@LightDarkPreview
@Composable
private fun HeaderHeadphonesPreview() {
    EarshotPreview {
        ConnectionHeader(FakeHeadphoneRepository.connectedState(), onRefresh = {}, onSwitch = {}, modifier = Modifier.padding(16.dp))
    }
}

@LightDarkPreview
@Composable
private fun HeaderEarbudsPreview() {
    EarshotPreview {
        Column(Modifier.padding(PaddingValues(16.dp)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionHeader(FakeHeadphoneRepository.connectedEarbudsState(), onRefresh = {}, onSwitch = {})
            ConnectionHeader(HeadphoneState(connection = ConnectionState(ConnectionStatus.CONNECTING, FakeHeadphoneRepository.EARBUDS_NAME)), onRefresh = {}, onSwitch = {})
        }
    }
}
