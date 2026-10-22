package dev.earshot.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.FormFactor
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard

/**
 * The one card Home shows while the headphones are not connected: what to do (Connect, Choose
 * headphones), the three things that fix most failed connections, and the connection report for
 * when they don't. The mode and noise controls stay hidden until the link is up.
 */
@Composable
fun DisconnectedCard(
    connection: ConnectionState,
    formFactor: FormFactor,
    onConnect: () -> Unit,
    onChooseHeadphones: () -> Unit,
    onCopyReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connecting = connection.status == ConnectionStatus.CONNECTING
    val buds = formFactor != FormFactor.HEADPHONES
    // The virtual unit (ConnectionManager.DEMO_NAME) has no Bose app to fight with; it reconnects by itself.
    val demo = connection.deviceName?.contains("(demo)") == true
    // "Close the Bose app" leads only when the manager actually diagnosed a busy channel; otherwise the
    // headphones are simply off, out of range or not linked, and that hint would mislead.
    val heldBy = (connection.problem as? dev.earshot.core.ConnectionProblem.ChannelHeld)?.appName
    val channelBusy = heldBy != null
    SectionCard(modifier = modifier) {
        Text(
            stringResource(if (buds) R.string.disconnected_title_buds else R.string.disconnected_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            stringResource(
                when {
                    demo -> R.string.disconnected_body_demo
                    heldBy != null -> R.string.disconnected_body_held
                    else -> R.string.disconnected_body_generic
                },
                heldBy ?: "",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The manager's own diagnosis, unless the body above already is that diagnosis.
        if (connection.message != null && connection.status == ConnectionStatus.DISCONNECTED && !channelBusy) {
            Text(
                connection.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        // Stacked full-width buttons: two side by side would ellipsize "Choose headphones" at phone width.
        Button(shape = MaterialTheme.shapes.medium, onClick = onConnect, enabled = !connecting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (connecting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                Spacer(Modifier.width(10.dp))
            }
            Text(stringResource(if (connecting) R.string.status_connecting else R.string.action_connect))
        }
        Spacer(Modifier.size(8.dp))
        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = onChooseHeadphones, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.action_choose_headphones))
        }
        if (!demo) {
            Spacer(Modifier.size(16.dp))
            HorizontalDivider()
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.disconnected_tips_title), style = MaterialTheme.typography.titleSmall)
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Tip(Icons.Default.PowerSettingsNew, stringResource(if (buds) R.string.tip_power_on_buds else R.string.tip_power_on))
                // Only Bose has a Bose app to close; for any other headset the tip would mislead.
                if (heldBy == "Bose" || (heldBy == null && dev.earshot.bt.DeviceFamily.looksLikeBose(connection.deviceName))) {
                    Tip(Icons.Default.Block, stringResource(R.string.tip_close_bose_app))
                }
                Tip(Icons.Default.Bluetooth, stringResource(R.string.tip_le_audio))
            }
            Spacer(Modifier.size(4.dp))
            TextButton(
                onClick = onCopyReport,
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.lab_report_copy))
            }
        }
    }
}

@Composable
private fun Tip(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

@LightDarkPreview
@Composable
private fun DisconnectedCardPreview() {
    EarshotPreview {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DisconnectedCard(
                connection = ConnectionState(ConnectionStatus.DISCONNECTED, FakeHeadphoneRepository.DEVICE_NAME),
                formFactor = FormFactor.HEADPHONES,
                onConnect = {}, onChooseHeadphones = {}, onCopyReport = {},
            )
            DisconnectedCard(
                connection = ConnectionState(ConnectionStatus.CONNECTING, FakeHeadphoneRepository.EARBUDS_NAME),
                formFactor = FormFactor.EARBUDS,
                onConnect = {}, onChooseHeadphones = {}, onCopyReport = {},
            )
        }
    }
}
