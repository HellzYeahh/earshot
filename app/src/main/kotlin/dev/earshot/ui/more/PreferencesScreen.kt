@file:OptIn(ExperimentalLayoutApi::class)

package dev.earshot.ui.more

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.appContainer
import kotlinx.coroutines.launch
import dev.earshot.core.AppPreferences
import dev.earshot.core.Appearance
import dev.earshot.core.ListeningMode
import dev.earshot.core.ModeResolver
import dev.earshot.core.SecureSessionStatus
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.extras.tile.TileCycle
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.virtual.DemoDevice
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.SwitchRow
import dev.earshot.ui.common.ValueSlider
import dev.earshot.ui.common.earshotViewModel
import kotlin.math.roundToInt

@Composable
fun PreferencesScreen(onBack: () -> Unit) {
    val vm = earshotViewModel { AutomationsViewModel(it.automation, it.headphones, it.tuning, it.messenger) }
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val headphones by vm.headphoneState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val demoMode = remember(context) { context.appContainer.demoMode }
    val demoEnabled by demoMode.isEnabled.collectAsStateWithLifecycle(initialValue = false)
    val demoDevice by demoMode.device.collectAsStateWithLifecycle(initialValue = DemoDevice.QC_ULTRA)
    val devicePrefs = remember(context) { context.appContainer.devicePrefs }
    val familyOverride by devicePrefs.familyOverrideFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val headphonesRepo = remember(context) { context.appContainer.headphones }
    PreferencesContent(
        prefs, headphones.modes, onBack, vm::setPreferences,
        address = headphones.connection.deviceAddress,
        canForgetPairing = headphones.secureSession.status != SecureSessionStatus.NOT_REQUIRED,
        onForgetPairing = {
            scope.launch {
                runCatching { headphonesRepo.forgetPairing() }
                // The next connect pairs again.
                headphonesRepo.disconnect()
                headphonesRepo.connect()
            }
        },
        familyOverride = familyOverride,
        onFamilyChange = { key ->
            scope.launch {
                devicePrefs.setFamilyOverride(key)
                // The family is chosen at the start of a connect: reconnect so the change applies now.
                headphonesRepo.disconnect()
                headphonesRepo.connect()
            }
        },
        demoDevice = demoDevice,
        onDemoDeviceChange = { device ->
            scope.launch {
                demoMode.setDevice(device)
                headphonesRepo.disconnect()
                headphonesRepo.connect()
            }
        },
        demoEnabled = demoEnabled,
        onDemoChange = { enabled ->
            scope.launch {
                demoMode.set(enabled)
                // Demo mode is read at the start of a connect loop: restart it so the switch
                // takes effect at once instead of on the next natural reconnect.
                headphonesRepo.disconnect()
                headphonesRepo.connect()
            }
        },
    )
}

@Composable
fun PreferencesContent(
    prefs: AppPreferences,
    modes: List<ListeningMode>,
    onBack: () -> Unit,
    onChange: (AppPreferences) -> Unit,
    /** The connected headset, whose modes [modes] are. */
    address: String? = null,
    canForgetPairing: Boolean = false,
    onForgetPairing: () -> Unit = {},
    familyOverride: String? = null,
    onFamilyChange: (String?) -> Unit = {},
    demoDevice: DemoDevice = DemoDevice.QC_ULTRA,
    onDemoDeviceChange: (DemoDevice) -> Unit = {},
    demoEnabled: Boolean = false,
    onDemoChange: (Boolean) -> Unit = {},
) {
    SubScreenScaffold(title = stringResource(R.string.settings_preferences), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Below Android 12 there is no wallpaper palette, so the choice would be a no-op: hidden.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SectionCard(title = stringResource(R.string.prefs_appearance)) {
                Text(
                    stringResource(R.string.prefs_appearance_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = prefs.appearance == Appearance.FIELD,
                        onClick = { onChange(prefs.copy(appearance = Appearance.FIELD)) },
                        label = { Text(stringResource(R.string.prefs_appearance_field)) },
                    )
                    FilterChip(
                        selected = prefs.appearance == Appearance.SYSTEM,
                        onClick = { onChange(prefs.copy(appearance = Appearance.SYSTEM)) },
                        label = { Text(stringResource(R.string.prefs_appearance_system)) },
                    )
                }
            }
            SectionCard(title = stringResource(R.string.prefs_alerts)) {
                SwitchRow(
                    title = stringResource(R.string.prefs_low_battery),
                    subtitle = stringResource(R.string.prefs_low_battery_subtitle),
                    checked = prefs.lowBatteryThreshold != null,
                    onCheckedChange = { onChange(prefs.copy(lowBatteryThreshold = if (it) 20 else null)) },
                )
                val threshold = prefs.lowBatteryThreshold
                if (threshold != null) {
                    var drag by remember(threshold) { mutableFloatStateOf(threshold.toFloat()) }
                    ValueSlider(
                        label = stringResource(R.string.prefs_low_battery_threshold),
                        value = drag,
                        valueRange = 5f..50f,
                        steps = 8,
                        valueText = "${drag.roundToInt()} %",
                        onValueChange = { drag = it },
                        onValueChangeFinished = { onChange(prefs.copy(lowBatteryThreshold = drag.roundToInt())) },
                    )
                }
                SwitchRow(
                    title = stringResource(R.string.prefs_persistent_notification),
                    subtitle = stringResource(R.string.prefs_persistent_notification_subtitle),
                    checked = prefs.persistentNotification,
                    onCheckedChange = { onChange(prefs.copy(persistentNotification = it)) },
                )
                SwitchRow(
                    title = stringResource(R.string.prefs_auto_connect),
                    subtitle = stringResource(R.string.prefs_auto_connect_subtitle),
                    checked = prefs.autoConnectOnBluetooth,
                    onCheckedChange = { onChange(prefs.copy(autoConnectOnBluetooth = it)) },
                )
            }
            SectionCard(title = stringResource(R.string.prefs_tile)) {
                Text(stringResource(R.string.prefs_tile_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                // The choice is saved by meaning: what is shown here is what it means on this headset.
                val tileModes = modes.filter { it.isConfigured }
                val chosen = TileCycle.preferredSlots(prefs, modes, address).filter { slot -> tileModes.any { it.index == slot } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tileModes.forEach { mode ->
                        FilterChip(
                            selected = mode.index in chosen,
                            onClick = { onChange(TileCycle.toggle(prefs, mode, modes, address)) },
                            label = { Text(mode.name.ifBlank { stringResource(R.string.mode_slot_number, mode.index) }) },
                        )
                    }
                }
                // With nothing connected there are no chips, and a saved choice is not "none selected".
                val nothingSaved = if (prefs.modeRefsMigrated) prefs.tileCycle.isEmpty() else prefs.tileCycleModes.isEmpty()
                if (nothingSaved || (tileModes.isNotEmpty() && chosen.isEmpty())) {
                    Text(stringResource(R.string.prefs_tile_default), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            SectionCard(title = stringResource(R.string.prefs_headphone_type)) {
                Text(
                    stringResource(R.string.prefs_headphone_type_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = familyOverride == null, onClick = { onFamilyChange(null) }, label = { Text(stringResource(R.string.prefs_type_auto)) })
                    FilterChip(selected = familyOverride == "bose", onClick = { onFamilyChange("bose") }, label = { Text(stringResource(R.string.prefs_type_bose)) })
                    FilterChip(selected = familyOverride == "bragi", onClick = { onFamilyChange("bragi") }, label = { Text(stringResource(R.string.prefs_type_crusher)) })
                    FilterChip(selected = familyOverride == "soundcore", onClick = { onFamilyChange("soundcore") }, label = { Text(stringResource(R.string.prefs_type_soundcore)) })
                    FilterChip(selected = familyOverride == "sennheiser", onClick = { onFamilyChange("sennheiser") }, label = { Text(stringResource(R.string.prefs_type_sennheiser)) })
                    FilterChip(selected = familyOverride == "sony", onClick = { onFamilyChange("sony") }, label = { Text(stringResource(R.string.prefs_type_sony)) })
                    FilterChip(selected = familyOverride == "generic", onClick = { onFamilyChange("generic") }, label = { Text(stringResource(R.string.prefs_type_generic)) })
                }
                if (canForgetPairing) {
                    TextButton(onClick = onForgetPairing, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.prefs_forget_pairing))
                    }
                    Text(stringResource(R.string.prefs_forget_pairing_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionCard(title = stringResource(R.string.prefs_demo)) {
                SwitchRow(
                    title = stringResource(R.string.prefs_demo_mode),
                    subtitle = stringResource(R.string.prefs_demo_mode_subtitle),
                    checked = demoEnabled,
                    onCheckedChange = onDemoChange,
                )
                if (demoEnabled) {
                    Text(
                        stringResource(R.string.prefs_demo_device),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                    // Built from the enum so every virtual model the protocol side adds shows up here.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DemoDevice.entries.forEach { device ->
                            FilterChip(
                                selected = demoDevice == device,
                                onClick = { onDemoDeviceChange(device) },
                                label = { Text(device.displayName) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@LightDarkPreview
@Composable
private fun PreferencesPreview() {
    val fake = FakeHeadphoneRepository.connectedState()
    val prefs = AppPreferences().withTileCycle(fake.modes.take(2).map { ModeResolver.refFor(it, fake.connection.deviceAddress) })
    EarshotPreview { PreferencesContent(prefs, fake.modes, {}, {}, address = fake.connection.deviceAddress, demoEnabled = true) }
}
