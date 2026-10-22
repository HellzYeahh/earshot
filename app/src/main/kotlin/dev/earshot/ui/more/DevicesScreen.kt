@file:OptIn(ExperimentalLayoutApi::class)

package dev.earshot.ui.more

import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import dev.earshot.ui.theme.EarshotTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.HeadphoneState
import dev.earshot.core.PairedDevice
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.EmptyHint
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.SwitchRow
import dev.earshot.ui.common.earshotViewModel
import dev.earshot.ui.tools.ProfileChip

@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val vm = earshotViewModel { SettingsViewModel(it.headphones, it.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
    DevicesContent(state, onBack, vm::setMultipoint, vm::switchToDevice, vm::enterPairingMode)
}

@Composable
fun DevicesContent(
    state: HeadphoneState,
    onBack: () -> Unit,
    onMultipoint: (Boolean) -> Unit,
    onSwitch: (String) -> Unit,
    onPairingMode: () -> Unit,
) {
    val enabled = state.isConnected && !state.isBusy
    SubScreenScaffold(title = stringResource(R.string.settings_devices), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                SwitchRow(
                    title = stringResource(R.string.settings_multipoint),
                    subtitle = stringResource(R.string.settings_multipoint_subtitle),
                    checked = state.multipointEnabled ?: false,
                    enabled = enabled && state.multipointEnabled != null,
                    onCheckedChange = onMultipoint,
                )
            }
            SectionCard(title = stringResource(R.string.devices_paired)) {
                if (state.pairedDevices.isEmpty()) EmptyHint(stringResource(R.string.devices_none))
                state.pairedDevices.forEach { device -> DeviceRow(device, enabled, onSwitch) }
                // [4.18] (meaning inferred): whether a third source could connect right now.
                when (state.availableToConnect) {
                    true -> AvailabilityLine(stringResource(R.string.devices_available_to_connect), Icons.Default.CheckCircle)
                    false -> AvailabilityLine(stringResource(R.string.devices_not_available_to_connect), Icons.Outlined.Info)
                    null -> Unit
                }
            }
            InfoBanner(stringResource(R.string.devices_switch_hint))
            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = onPairingMode, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.devices_pairing_mode))
            }
            Text(stringResource(R.string.devices_pairing_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AvailabilityLine(text: String, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DeviceRow(device: PairedDevice, enabled: Boolean, onSwitch: (String) -> Unit) {
    val linked = device.isConnected
    val iconTint = if (linked == false) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when {
                device.isThisPhone -> Icons.Default.PhoneAndroid
                device.isBoseProduct == true -> Icons.Default.Headphones
                else -> Icons.Default.Computer
            },
            contentDescription = null,
            tint = iconTint,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(device.name ?: device.address, style = MaterialTheme.typography.bodyLarge)
                if (device.isActiveSource) SmallBadge(stringResource(R.string.devices_active), tonal = true)
                if (device.isBoseProduct == true) SmallBadge(stringResource(R.string.devices_bose_product), tonal = false)
            }
            Text(
                listOfNotNull(
                    if (device.isThisPhone) stringResource(R.string.devices_this_phone) else device.address,
                    when (linked) {
                        true -> stringResource(R.string.devices_connected)
                        false -> stringResource(R.string.devices_not_connected)
                        null -> null
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (device.profiles != null) {
                ProfileChip(device.profiles, highlight = device.profiles.usesLeAudio, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (!device.isActiveSource) {
            TextButton(onClick = { onSwitch(device.address) }, enabled = enabled) { Text(stringResource(R.string.devices_switch)) }
        }
    }
}

/** A 4 dp tag in monospace capitals. Material's Badge is always a pill, so this is a Surface. */
@Composable
private fun SmallBadge(text: String, tonal: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = if (tonal) EarshotTheme.colors.chip else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (tonal) EarshotTheme.colors.onChip else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (tonal) null else BorderStroke(1.dp, EarshotTheme.colors.hairline),
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@LightDarkPreview
@Composable
private fun DevicesPreview() {
    EarshotPreview { DevicesContent(FakeHeadphoneRepository.connectedState(), {}, {}, {}, {}) }
}
