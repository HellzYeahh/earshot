package dev.earshot.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.backup.BackupStaging
import dev.earshot.backup.RestoreFailedNotice
import dev.earshot.backup.RestoredNotice
import dev.earshot.core.HeadphoneState
import dev.earshot.core.Sidetone
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.ui.common.rememberSettingsLoaded
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.NavRow
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.VendorControlsCard
import dev.earshot.ui.common.SegmentedChoice
import dev.earshot.ui.common.SwitchRow
import dev.earshot.ui.common.earshotViewModel
import dev.earshot.ui.backup.RestoreFailedBanner
import dev.earshot.ui.backup.formatDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MoreScreen(
    contentPadding: PaddingValues,
    onOpenAutomations: () -> Unit,
    onOpenPreferences: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val vm = earshotViewModel { SettingsViewModel(it.headphones, it.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
    // A restore restarts the app onto this tab: say once that it worked.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var restored by remember { mutableStateOf<RestoredNotice?>(null) }
    LaunchedEffect(Unit) { restored = withContext(Dispatchers.IO) { BackupStaging.restoredNotice(context) } }
    // ...or that it didn't finish (the previous settings are back, or it completes at the next start).
    var restoreFailed by remember { mutableStateOf<RestoreFailedNotice?>(null) }
    LaunchedEffect(Unit) { restoreFailed = withContext(Dispatchers.IO) { BackupStaging.restoreFailedNotice(context) } }
    MoreContent(
        state = state,
        contentPadding = contentPadding,
        callbacks = SettingsCallbacks(
            onSidetone = vm::setSidetone,
            onAutoPlayPause = vm::setAutoPlayPause,
            onAutoAnswer = vm::setAutoAnswer,
            onMultipoint = vm::setMultipoint,
            onAutoOff = vm::setAutoOff,
            onVendorControl = vm::setVendorControl,
        ),
        onOpenAutomations = onOpenAutomations,
        onOpenPreferences = onOpenPreferences,
        onOpenAbout = onOpenAbout,
        onOpenBackup = onOpenBackup,
        restoredFrom = restored?.backupCreated,
        onDismissRestored = {
            restored = null
            scope.launch(Dispatchers.IO) { BackupStaging.dismissRestoredNotice(context) }
        },
        restoreFailed = restoreFailed,
        onDismissRestoreFailed = {
            restoreFailed = null
            scope.launch(Dispatchers.IO) { BackupStaging.dismissRestoreFailedNotice(context) }
        },
        onFinishRestore = { scope.launch(Dispatchers.IO) { BackupStaging.restartToFinish(context) } },
    )
}

data class SettingsCallbacks(
    val onSidetone: (Sidetone, Boolean) -> Unit = { _, _ -> },
    val onAutoPlayPause: (Boolean) -> Unit = {},
    val onAutoAnswer: (Boolean) -> Unit = {},
    val onMultipoint: (Boolean) -> Unit = {},
    val onAutoOff: (Int) -> Unit = {},
    val onVendorControl: (id: String, value: Int) -> Unit = { _, _ -> },
)

/**
 * The More tab: the headphones' everyday settings, About these headphones and the App section.
 * Touch controls, voice prompts, devices, rename and power off live on Tools → Controls
 * (Headphone controls), which also sets the Shortcut button.
 */
@Composable
fun MoreContent(
    state: HeadphoneState,
    contentPadding: PaddingValues,
    callbacks: SettingsCallbacks,
    onOpenAutomations: () -> Unit,
    onOpenPreferences: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenBackup: () -> Unit = {},
    /** The backup's ISO creation time while the "Settings restored" line is due (blank when unknown), else null. */
    restoredFrom: String? = null,
    onDismissRestored: () -> Unit = {},
    restoreFailed: RestoreFailedNotice? = null,
    onDismissRestoreFailed: () -> Unit = {},
    onFinishRestore: () -> Unit = {},
) {
    val enabled = state.isConnected && !state.isBusy
    var showAutoOff by rememberSaveable { mutableStateOf(false) }
    val buds = HeadphoneLabels.isEarbuds(HeadphoneLabels.formFactor(state))
    // Once the refresh has landed, null means "this model doesn't have it": hide the row rather
    // than leave a switch that can never be used. While loading, rows stay (disabled) so nothing jumps.
    val loaded = rememberSettingsLoaded(state)
    fun has(value: Any?) = !loaded || value != null
    val hasSidetone = has(state.sidetone)
    val hasGeneral = has(state.autoPlayPause) || has(state.autoAnswer) || has(state.multipointEnabled) || has(state.autoOffMinutes)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_more).uppercase(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))

        // A restore that finished without its manifest (the staging half cleaned up) has no date:
        // the plain line then, as on the Backup screen, never "the backup made on ."
        if (restoredFrom != null) InfoBanner(
            text = if (restoredFrom.isNotBlank()) stringResource(R.string.backup_restored_from, formatDate(restoredFrom)) else stringResource(R.string.backup_restored),
            icon = Icons.Default.CheckCircle,
            action = { TextButton(onClick = onDismissRestored) { Text(stringResource(R.string.backup_ok)) } },
        )
        restoreFailed?.let { RestoreFailedBanner(it, onDismiss = onDismissRestoreFailed, onRestart = onFinishRestore) }

        if (!state.capabilities.ownSettings) InfoBanner(stringResource(R.string.settings_generic_headset))
        else if (!state.isConnected) InfoBanner(stringResource(R.string.settings_not_connected))

        // A model with none of these settings gets no empty card.
        if (state.capabilities.ownSettings && (hasSidetone || hasGeneral)) SectionCard(title = stringResource(if (buds) R.string.settings_earbuds else R.string.settings_headphones)) {
            if (hasSidetone) {
                // Sidetone: wire order is 0=off 1=high 2=med 3=low; the enum handles it, we just show labels.
                Text(stringResource(R.string.settings_sidetone), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                SegmentedChoice(
                    options = listOf(Sidetone.OFF, Sidetone.LOW, Sidetone.MEDIUM, Sidetone.HIGH),
                    selected = state.sidetone ?: Sidetone.OFF,
                    onSelect = { callbacks.onSidetone(it, state.sidetonePersistent ?: true) },
                    label = { it.label },
                    enabled = enabled && state.sidetone != null,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_sidetone_persistent),
                    subtitle = stringResource(R.string.settings_sidetone_persistent_subtitle),
                    checked = state.sidetonePersistent ?: true,
                    enabled = enabled && state.sidetone != null,
                    onCheckedChange = { callbacks.onSidetone(state.sidetone ?: Sidetone.OFF, it) },
                )
                if (hasGeneral) HorizontalDivider()
            }
            if (has(state.autoPlayPause)) SwitchRow(stringResource(R.string.settings_auto_play_pause), state.autoPlayPause ?: false, callbacks.onAutoPlayPause, subtitle = stringResource(R.string.settings_auto_play_pause_subtitle), enabled = enabled && state.autoPlayPause != null)
            if (has(state.autoAnswer)) SwitchRow(stringResource(R.string.settings_auto_answer), state.autoAnswer ?: false, callbacks.onAutoAnswer, subtitle = stringResource(R.string.settings_auto_answer_subtitle), enabled = enabled && state.autoAnswer != null)
            if (has(state.multipointEnabled)) SwitchRow(stringResource(R.string.settings_multipoint), state.multipointEnabled ?: false, callbacks.onMultipoint, subtitle = stringResource(R.string.settings_multipoint_subtitle), enabled = enabled && state.multipointEnabled != null)
            if (has(state.autoOffMinutes)) NavRow(
                title = stringResource(R.string.settings_auto_off),
                subtitle = stringResource(R.string.settings_auto_off_subtitle),
                value = state.autoOffMinutes?.let { autoOffLabel(it) },
                icon = Icons.Default.Timer,
                onClick = { showAutoOff = true },
                enabled = enabled && state.autoOffMinutes != null,
            )
        }

        // Every setting only this maker's headsets have (the Home card shows the quick ones).
        VendorControlsCard(
            title = stringResource(if (buds) R.string.settings_earbuds else R.string.settings_headphones),
            controls = state.vendorControls,
            enabled = enabled,
            onChange = callbacks.onVendorControl,
        )

        DeviceInfoCard(state)

        SectionCard(title = stringResource(R.string.settings_app)) {
            NavRow(title = stringResource(R.string.settings_automations), subtitle = stringResource(R.string.settings_automations_subtitle), icon = Icons.Default.AutoAwesome, onClick = onOpenAutomations)
            NavRow(title = stringResource(R.string.settings_preferences), subtitle = stringResource(R.string.settings_preferences_subtitle), icon = Icons.Default.Tune, onClick = onOpenPreferences)
            NavRow(title = stringResource(R.string.backup_title), subtitle = stringResource(R.string.backup_entry_subtitle), icon = Icons.Default.SettingsBackupRestore, onClick = onOpenBackup)
            NavRow(title = stringResource(R.string.settings_about), icon = Icons.Default.Info, onClick = onOpenAbout)
        }
    }

    if (showAutoOff) {
        AutoOffDialog(
            current = state.autoOffMinutes,
            onPick = { minutes -> showAutoOff = false; callbacks.onAutoOff(minutes) },
            onDismiss = { showAutoOff = false },
        )
    }
}

@LightDarkPreview
@Composable
private fun MorePreview() {
    EarshotPreview {
        MoreContent(FakeHeadphoneRepository.connectedState(), PaddingValues(0.dp), SettingsCallbacks(), {}, {}, {})
    }
}

/** Right after a restore (the restart lands here): the one-time "Settings restored" line. */
@LightDarkPreview
@Composable
private fun MoreRestoredPreview() {
    EarshotPreview {
        MoreContent(FakeHeadphoneRepository.connectedState(), PaddingValues(0.dp), SettingsCallbacks(), {}, {}, {}, restoredFrom = "2026-10-27T08:15:00Z")
    }
}

/** Earbuds: only the rows the model has, earbud wording, the device info card. */
@LightDarkPreview
@Composable
private fun MoreEarbudsPreview() {
    EarshotPreview {
        MoreContent(FakeHeadphoneRepository.connectedEarbudsState(), PaddingValues(0.dp), SettingsCallbacks(), {}, {}, {})
    }
}

/** A basic-tier model whose layout is a best guess: the Experimental tier and its note in the device info. */
@LightDarkPreview
@Composable
private fun MoreBasicTierPreview() {
    EarshotPreview {
        MoreContent(FakeHeadphoneRepository.basicTierState(), PaddingValues(0.dp), SettingsCallbacks(), {}, {}, {})
    }
}
