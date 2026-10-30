package dev.earshot.ui.backup

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.earshot.R
import dev.earshot.backup.BackupError
import dev.earshot.backup.BackupTransfer
import dev.earshot.backup.OwnSoundsInfo
import dev.earshot.backup.RestoreFailedNotice
import dev.earshot.backup.RestoredNotice
import dev.earshot.distribution.LegacyApp
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.ConfirmDialog
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** More → Back up and restore: one file out, one file in, nothing else leaves the phone. */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // The SavedStateHandle keeps the own-sounds choice across a recreation behind the save picker.
    val vm: BackupViewModel = viewModel(factory = viewModelFactory { initializer { BackupViewModel(context.applicationContext, createSavedStateHandle()) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupTransfer.MIME)) { vm.export(it) }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.import(it) }
    // Re-checked on every return to the screen: the user may just have removed the other app.
    // It reads the package manager with the signer's certificate: off the main thread.
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose {}
    }
    val otherEarshot by produceState<LegacyApp.Kind?>(null, checks) {
        value = withContext(Dispatchers.IO) { LegacyApp.find(context) }
    }
    BackupContent(
        state = state,
        otherEarshot = otherEarshot,
        onOpenLegacy = { LegacyApp.openAppInfo(context) },
        onBack = onBack,
        onIncludeOwnSounds = vm::setIncludeOwnSounds,
        onExport = { saveLauncher.launch(BackupTransfer.defaultFileName()) },
        onImport = { openLauncher.launch(BackupTransfer.OPEN_MIME_TYPES) },
        onRestart = vm::restartNow,
        onCancelRestore = vm::cancelRestore,
        onDismissRestored = vm::dismissRestored,
        onDismissRestoreFailed = vm::dismissRestoreFailed,
        onFinishRestore = vm::finishRestoreNow,
    )
}

@Composable
fun BackupContent(
    state: BackupUiState,
    onBack: () -> Unit,
    onIncludeOwnSounds: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onRestart: () -> Unit,
    onCancelRestore: () -> Unit,
    onDismissRestored: () -> Unit,
    onDismissRestoreFailed: () -> Unit = {},
    onFinishRestore: () -> Unit = {},
    /** Another Earshot on this phone (GitHub build only), or null. */
    otherEarshot: LegacyApp.Kind? = null,
    onOpenLegacy: () -> Unit = {},
) {
    var confirmImport by rememberSaveable { mutableStateOf(false) }
    val busy = state.busy != null

    SubScreenScaffold(title = stringResource(R.string.backup_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.restored?.let { RestoredBanner(it, onDismissRestored) }
            state.restoreFailed?.let { RestoreFailedBanner(it, onDismiss = onDismissRestoreFailed, onRestart = onFinishRestore, enabled = !busy) }
            state.waitingRestore?.let { WaitingRestoreBanner(it, enabled = !busy, onRestart = onRestart, onDiscard = onCancelRestore) }
            state.message?.let { MessageBanner(it) }
            otherEarshot?.let { OtherEarshotNote(it, onOpenLegacy) }

            Text(stringResource(R.string.backup_intro), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 4.dp))

            SectionCard(title = stringResource(R.string.backup_export_title)) {
                val sounds = state.ownSounds
                val hasSounds = (sounds?.count ?: 0) > 0
                SwitchRow(
                    title = if (sounds != null && hasSounds) {
                        stringResource(R.string.backup_own_sounds, Formatter.formatShortFileSize(LocalContext.current, sounds.bytes))
                    } else {
                        stringResource(R.string.backup_own_sounds_none)
                    },
                    subtitle = if (sounds != null && hasSounds) {
                        pluralStringResource(R.plurals.backup_own_sounds_subtitle, sounds.count, sounds.count)
                    } else {
                        stringResource(R.string.backup_own_sounds_empty)
                    },
                    checked = state.includeOwnSounds && hasSounds,
                    onCheckedChange = onIncludeOwnSounds,
                    enabled = hasSounds && !busy,
                )
                Spacer(Modifier.height(8.dp))
                Button(shape = MaterialTheme.shapes.medium, onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (state.busy == BackupBusy.SAVING) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(stringResource(if (state.busy == BackupBusy.SAVING) R.string.backup_saving else R.string.backup_export_button))
                }
            }

            SectionCard(title = stringResource(R.string.backup_import_title)) {
                Text(stringResource(R.string.backup_import_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { confirmImport = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (state.busy == BackupBusy.CHECKING) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(stringResource(if (state.busy == BackupBusy.CHECKING) R.string.backup_checking else R.string.backup_import_button))
                }
            }

            SectionCard(title = stringResource(R.string.backup_contents_title)) {
                Text(stringResource(R.string.backup_included), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.backup_excluded), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            InfoBanner(stringResource(R.string.backup_private), icon = Icons.Default.Lock)
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmImport) {
        ConfirmDialog(
            title = stringResource(R.string.backup_import_confirm_title),
            text = stringResource(R.string.backup_import_confirm_text),
            confirmLabel = stringResource(R.string.backup_import_confirm_action),
            onConfirm = onImport,
            onDismiss = { confirmImport = false },
        )
    }

    state.readyToRestore?.let { staged ->
        // Only the buttons close it: a stray tap outside must neither restart nor drop the checked backup.
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.backup_ready_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val date = formatDate(staged.createdIso)
                    Text(
                        if (staged.versionName != null) stringResource(R.string.backup_ready_text_version, date, staged.versionName)
                        else stringResource(R.string.backup_ready_text, date),
                    )
                    Text(
                        staged.ownSoundCount?.let { pluralStringResource(R.plurals.backup_ready_sounds, it, it) }
                            ?: stringResource(R.string.backup_ready_no_sounds),
                    )
                }
            },
            confirmButton = { TextButton(onClick = onRestart, enabled = !busy) { Text(stringResource(R.string.backup_restart_now)) } },
            dismissButton = { TextButton(onClick = onCancelRestore, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * A restore the user confirmed that didn't finish: the app restarted onto its old settings, or,
 * when the undo failed too, it runs on a mix until a restart finishes the restore (offered here:
 * a new import is refused until then). Also shown on the More tab, where a restore lands;
 * [onRestart] there is `BackupStaging.restartToFinish` (off the main thread).
 */
@Composable
fun RestoreFailedBanner(notice: RestoreFailedNotice, onDismiss: () -> Unit, onRestart: () -> Unit, enabled: Boolean = true) {
    val text = when {
        notice.finishAtNextStart -> R.string.backup_restore_failed_restart
        notice.previousSettingsBack -> R.string.backup_restore_failed
        else -> R.string.backup_restore_failed_partly
    }
    InfoBanner(
        text = stringResource(text),
        tone = BannerTone.ERROR,
        icon = Icons.Default.ErrorOutline,
        action = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_ok)) }
            if (notice.finishAtNextStart) {
                TextButton(onClick = onRestart, enabled = enabled) { Text(stringResource(R.string.backup_restart_now)) }
            }
        },
    )
}

/** A backup checked earlier and never confirmed: nothing happens to it until the user picks one. */
@Composable
private fun WaitingRestoreBanner(staged: StagedBackup, enabled: Boolean, onRestart: () -> Unit, onDiscard: () -> Unit) {
    InfoBanner(
        text = stringResource(R.string.backup_waiting, formatDate(staged.createdIso)),
        tone = BannerTone.WARNING,
        action = {
            TextButton(onClick = onDiscard, enabled = enabled) { Text(stringResource(R.string.backup_discard)) }
            TextButton(onClick = onRestart, enabled = enabled) { Text(stringResource(R.string.backup_restart_now)) }
        },
    )
}

@Composable
private fun RestoredBanner(notice: RestoredNotice, onDismiss: () -> Unit) {
    val date = notice.backupCreated.takeIf { it.isNotBlank() }?.let { formatDate(it) }
    InfoBanner(
        text = if (date != null) stringResource(R.string.backup_restored_from, date) else stringResource(R.string.backup_restored),
        icon = Icons.Default.CheckCircle,
        action = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_ok)) } },
    )
}

/**
 * Another Earshot is installed (GitHub build only). The pre-1.0 one: move the settings over, then
 * remove it. The Google Play build is no older app, so only a neutral note that both shouldn't
 * drive the headset, with the same way to its App info.
 */
@Composable
private fun OtherEarshotNote(kind: LegacyApp.Kind, onOpen: () -> Unit) {
    val legacy = kind == LegacyApp.Kind.LEGACY
    InfoBanner(
        text = stringResource(if (legacy) R.string.backup_legacy_app else R.string.backup_other_earshot),
        tone = if (legacy) BannerTone.WARNING else BannerTone.INFO,
        action = {
            TextButton(onClick = onOpen) { Text(stringResource(if (legacy) R.string.backup_legacy_open else R.string.backup_other_earshot_open)) }
        },
    )
}

@Composable
private fun MessageBanner(message: BackupMessage) {
    when (message) {
        is BackupMessage.Saved -> {
            val context = LocalContext.current
            val text = buildString {
                append(stringResource(R.string.backup_saved))
                if (message.leftOut.isNotEmpty()) {
                    append("\n\n")
                    append(
                        pluralStringResource(
                            R.plurals.backup_saved_left_out,
                            message.leftOut.size,
                            message.leftOut.size,
                            Formatter.formatShortFileSize(context, BackupViewModel.LIMITS.maxOwnSoundBytes),
                            Formatter.formatShortFileSize(context, BackupViewModel.LIMITS.maxTotalBytes),
                        ),
                    )
                    val names = message.leftOut.filterNotNull()
                    if (names.isNotEmpty()) {
                        append(' ')
                        append(stringResource(R.string.backup_saved_left_out_names, names.joinToString(", ")))
                    }
                }
            }
            InfoBanner(text, tone = if (message.leftOut.isEmpty()) BannerTone.INFO else BannerTone.WARNING, icon = Icons.Default.CheckCircle)
        }
        BackupMessage.RestoreCancelled -> InfoBanner(stringResource(R.string.backup_restore_cancelled))
        BackupMessage.RestartFailed -> InfoBanner(stringResource(R.string.backup_restart_failed), tone = BannerTone.ERROR, icon = Icons.Default.ErrorOutline)
        is BackupMessage.Failed -> InfoBanner(stringResource(errorText(message.error)), tone = BannerTone.ERROR, icon = Icons.Default.ErrorOutline)
    }
}

fun errorText(error: BackupError): Int = when (error) {
    BackupError.NOT_A_BACKUP -> R.string.backup_error_not_backup
    BackupError.NEWER_FORMAT -> R.string.backup_error_newer
    BackupError.DAMAGED -> R.string.backup_error_damaged
    BackupError.UNEXPECTED_FILE -> R.string.backup_error_unexpected
    BackupError.TOO_LARGE -> R.string.backup_error_too_large
    BackupError.NO_SPACE -> R.string.backup_error_no_space
    BackupError.READ_FAILED -> R.string.backup_error_read
    BackupError.WRITE_FAILED -> R.string.backup_error_write
    BackupError.OVERWRITE_FAILED -> R.string.backup_error_overwrite
    BackupError.CHANGED_DURING_EXPORT -> R.string.backup_error_changed
    BackupError.BUSY -> R.string.backup_error_busy
    BackupError.RESTORE_UNFINISHED -> R.string.backup_error_unfinished
}

/** The backup's ISO time as a local date ("27 Oct 2026"); the raw text if it doesn't parse. */
fun formatDate(iso: String): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrDefault(iso)

@LightDarkPreview
@Composable
private fun BackupPreview() {
    EarshotPreview {
        BackupContent(
            state = BackupUiState(ownSounds = OwnSoundsInfo(3, 12_400_000), includeOwnSounds = true),
            onBack = {}, onIncludeOwnSounds = {}, onExport = {}, onImport = {}, onRestart = {}, onCancelRestore = {}, onDismissRestored = {},
        )
    }
}

/** Just restored, and the last import attempt failed: both banners. */
@LightDarkPreview
@Composable
private fun BackupRestoredPreview() {
    EarshotPreview {
        BackupContent(
            state = BackupUiState(
                ownSounds = OwnSoundsInfo(0, 0),
                message = BackupMessage.Failed(BackupError.NEWER_FORMAT),
                restored = RestoredNotice(restoredAtMillis = 0, backupCreated = "2026-10-27T08:15:00Z", fromVersion = "0.9.0"),
            ),
            onBack = {}, onIncludeOwnSounds = {}, onExport = {}, onImport = {}, onRestart = {}, onCancelRestore = {}, onDismissRestored = {},
        )
    }
}

/** A restore that stopped part-way and needs a restart to finish. */
@LightDarkPreview
@Composable
private fun BackupFinishRestorePreview() {
    EarshotPreview {
        BackupContent(
            state = BackupUiState(
                ownSounds = OwnSoundsInfo(0, 0),
                restoreFailed = RestoreFailedNotice(atMillis = 0, previousSettingsBack = false, finishAtNextStart = true),
            ),
            onBack = {}, onIncludeOwnSounds = {}, onExport = {}, onImport = {}, onRestart = {}, onCancelRestore = {}, onDismissRestored = {},
        )
    }
}

/** A restore that didn't finish, and the checked backup still waiting for the user. */
@LightDarkPreview
@Composable
private fun BackupWaitingPreview() {
    EarshotPreview {
        BackupContent(
            state = BackupUiState(
                ownSounds = OwnSoundsInfo(2, 300_000_000),
                restoreFailed = RestoreFailedNotice(atMillis = 0, backupCreated = "2026-10-27T08:15:00Z"),
                waitingRestore = StagedBackup("2026-10-27T08:15:00Z", "0.9.1", ownSoundCount = null),
                message = BackupMessage.Saved(leftOut = listOf("Rain all night", null)),
            ),
            onBack = {}, onIncludeOwnSounds = {}, onExport = {}, onImport = {}, onRestart = {}, onCancelRestore = {}, onDismissRestored = {},
        )
    }
}

/** The checked backup waiting for the restart. */
@LightDarkPreview
@Composable
private fun BackupReadyPreview() {
    EarshotPreview {
        BackupContent(
            state = BackupUiState(
                ownSounds = OwnSoundsInfo(1, 2_000_000),
                readyToRestore = StagedBackup("2026-10-27T08:15:00Z", "0.9.0", ownSoundCount = 2),
            ),
            onBack = {}, onIncludeOwnSounds = {}, onExport = {}, onImport = {}, onRestart = {}, onCancelRestore = {}, onDismissRestored = {},
        )
    }
}
