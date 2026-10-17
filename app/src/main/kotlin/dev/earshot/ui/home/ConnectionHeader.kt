package dev.earshot.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.ui.common.SectionCard

/** Battery ring + device name + status. Sits at the top of Home. */
@Composable
fun ConnectionHeader(
    connection: ConnectionState,
    batteryPercent: Int?,
    isCharging: Boolean?,
    isBusy: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = connection.status == ConnectionStatus.CONNECTED
    SectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BatteryRing(percent = if (connected) batteryPercent else null, charging = isCharging == true)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    connection.deviceName ?: stringResource(R.string.device_generic_name),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statusLabel(connection),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (connected) {
                if (isBusy) {
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
    }
}

@Composable
private fun statusLabel(connection: ConnectionState): String = when (connection.status) {
    ConnectionStatus.CONNECTED -> stringResource(R.string.status_connected)
    ConnectionStatus.CONNECTING -> stringResource(R.string.status_connecting)
    ConnectionStatus.DISCONNECTED -> connection.message ?: stringResource(R.string.status_disconnected)
    ConnectionStatus.NO_PAIRED_DEVICE -> stringResource(R.string.status_no_paired_device)
    ConnectionStatus.NO_PERMISSION -> stringResource(R.string.status_no_permission)
}

/** The friendly card shown whenever the headphones are not connected. */
@Composable
fun DisconnectedCard(connection: ConnectionState, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    val connecting = connection.status == ConnectionStatus.CONNECTING
    // The virtual unit (ConnectionManager.DEMO_NAME) has no Bose app to fight with; it reconnects by itself.
    val demo = connection.deviceName?.contains("(demo)") == true
    // "Close the Bose app" only when the manager actually diagnosed a busy channel; otherwise the
    // headphones are simply off, out of range or not linked, and that hint would mislead.
    val channelBusy = connection.message == dev.earshot.bt.ConnectionManager.MESSAGE_CHANNEL_BUSY
    SectionCard(modifier = modifier) {
        Text(stringResource(R.string.disconnected_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(
            stringResource(
                when {
                    demo -> R.string.disconnected_body_demo
                    channelBusy -> R.string.disconnected_body
                    else -> R.string.disconnected_body_generic
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (connection.message != null && connection.status == ConnectionStatus.DISCONNECTED) {
            Text(
                connection.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (connecting) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Button(onClick = onConnect, enabled = !connecting) {
                Text(stringResource(if (connecting) R.string.status_connecting else R.string.action_connect))
            }
        }
    }
}

/** Circular battery gauge; `percent == null` draws an empty ring. */
@Composable
fun BatteryRing(percent: Int?, charging: Boolean, modifier: Modifier = Modifier, size: Dp = 72.dp) {
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
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (percent != null) {
                drawArc(fill, startAngle = 135f, sweepAngle = 270f * fraction, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (charging) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = fill, modifier = Modifier.size(16.dp))
            }
            Text(
                if (percent == null) "–" else "$percent",
                style = MaterialTheme.typography.titleMedium,
                color = if (percent == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
