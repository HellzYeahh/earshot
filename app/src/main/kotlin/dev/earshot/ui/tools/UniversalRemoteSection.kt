package dev.earshot.ui.tools

import android.content.IntentSender
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.earshot.R
import dev.earshot.appContainer
import dev.earshot.bt.CompanionRemote
import dev.earshot.bt.DevicePrefs
import dev.earshot.bt.RemoteAbilities
import dev.earshot.ui.common.ConfirmDialog
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.NavRow
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.UiMessenger
import dev.earshot.ui.common.earshotViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/** The headset the universal remote acts on: the one at the top of the Controls screen, else the saved one. */
data class RemoteTarget(val address: String, val name: String?)

data class RemoteUiState(
    val target: RemoteTarget? = null,
    val linked: Boolean = false,
    /** The name this phone shows for it (`BluetoothDevice.getAlias`). */
    val alias: String? = null,
)

class UniversalRemoteViewModel(
    private val remote: CompanionRemote,
    private val prefs: DevicePrefs,
    messenger: UiMessenger,
) : EarshotViewModel(messenger) {
    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()
    val abilities: RemoteAbilities get() = remote.abilities

    /** Companion callbacks arrive on a binder thread; the dialog must be launched on the main thread. */
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }

    private val pendingDialog = PendingDialog<IntentSender>()

    /** The system's link dialog, waiting for the screen to launch it (see [PendingDialog]). */
    val dialog: StateFlow<IntentSender?> = pendingDialog.state

    fun refresh(current: RemoteTarget?) {
        viewModelScope.launch {
            val target = current ?: runCatching { prefs.savedDevice() }.getOrNull()?.let { RemoteTarget(it.address, it.name) }
            _state.value = withContext(Dispatchers.IO) {
                val linked = target?.let { remote.isLinked(it.address) } ?: false
                if (linked) remote.observePresence(target!!.address)
                RemoteUiState(
                    target = target,
                    linked = linked,
                    alias = target?.let { remote.aliasOf(it.address) },
                )
            }
        }
    }

    fun link() {
        val target = _state.value.target ?: return
        if (!abilities.canLink) {
            messenger.show(R.string.remote_unavailable)
            return
        }
        remote.link(target.address, target.name, mainExecutor, pendingDialog::offer) { error ->
            if (error == null) {
                messenger.show(R.string.remote_linked, label(target))
            } else if (error.isNotBlank()) {
                messenger.show(R.string.remote_link_failed, error)
            }
            refresh(target)
        }
    }

    /** The screen is launching [sender]; one offered meanwhile stays for the next launch. */
    fun dialogShown(sender: IntentSender) = pendingDialog.taken(sender)

    /** Launching the dialog failed; nothing else will tell the user. */
    fun dialogFailed(error: Throwable) {
        Log.w(TAG, "link dialog failed to launch: ${error.javaClass.simpleName}")
        val detail = error.message
        if (detail.isNullOrBlank()) messenger.show(R.string.error_generic) else messenger.show(R.string.remote_link_failed, detail)
    }

    fun connect() = act { remote.connect(it.address); messenger.show(R.string.remote_connecting, label(it)) }
    fun disconnect() = act { remote.disconnect(it.address); messenger.show(R.string.remote_disconnected, label(it)) }
    fun rename(alias: String) = act { remote.rename(it.address, alias); messenger.show(R.string.remote_renamed) }
    fun forget() = act { remote.forget(it.address); messenger.show(R.string.remote_forgotten, label(it)) }
    fun unlink() = act { remote.unlink(it.address); messenger.show(R.string.remote_unlinked) }

    private fun act(block: suspend (RemoteTarget) -> Unit) = launchCatching {
        val target = _state.value.target ?: return@launchCatching
        withContext(Dispatchers.IO) { block(target) }
        refresh(target)
    }

    private fun label(t: RemoteTarget) = t.name ?: t.address

    private companion object {
        const val TAG = "Earshot/Remote"
    }
}

/**
 * A dialog the system handed over in a callback, kept until the screen launches it. The
 * callback must not launch it itself: on Android 9–12 it comes only after the system's device
 * scan, seconds later, and by then a rotation, a fold (MainActivity handles no configuration
 * changes) or leaving the Controls screen has unregistered the launcher it would have captured.
 * `launch` then threw IllegalStateException from a main-looper callback and crashed the app.
 */
internal class PendingDialog<T : Any> {
    private val pending = MutableStateFlow<T?>(null)
    val state: StateFlow<T?> = pending.asStateFlow()

    fun offer(dialog: T) {
        pending.value = dialog
    }

    /** [dialog] is being launched; a newer one offered meanwhile stays. */
    fun taken(dialog: T) {
        pending.compareAndSet(dialog, null)
    }
}

/**
 * "Universal remote" on the Controls screen: works with any headset, driver or not, through a
 * Companion Device Manager link (see [CompanionRemote]). Every row stays tappable whatever the
 * Android version (Tools never hide settings); one this phone can't do explains why in a message.
 */
@Composable
fun UniversalRemoteSection(target: RemoteTarget?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm = earshotViewModel { deps -> UniversalRemoteViewModel(CompanionRemote(context.applicationContext), context.appContainer.devicePrefs, deps.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
    val abilities = vm.abilities
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { vm.refresh(target) }
    LaunchedEffect(target) { vm.refresh(target) }
    // Launched from here, with this composition's own launcher, never from the companion callback.
    val pendingDialog by vm.dialog.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDialog) {
        val sender = pendingDialog ?: return@LaunchedEffect
        vm.dialogShown(sender)
        try {
            dialog.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: RuntimeException) {
            vm.dialogFailed(e)
        }
    }

    var showRename by rememberSaveable { mutableStateOf(false) }
    var showForget by rememberSaveable { mutableStateOf(false) }
    val name = state.target?.let { it.name ?: it.address }

    SectionCard(modifier = modifier, title = stringResource(R.string.remote_title)) {
        Text(
            stringResource(R.string.remote_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (state.target == null) {
            Text(stringResource(R.string.remote_no_headset), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            return@SectionCard
        }
        if (!state.linked) {
            NavRow(
                title = stringResource(R.string.remote_link),
                value = name,
                subtitle = stringResource(if (abilities.canLink) R.string.remote_link_subtitle else R.string.remote_unavailable),
                icon = Icons.Default.Link,
                onClick = vm::link,
            )
            return@SectionCard
        }
        NavRow(
            title = stringResource(R.string.remote_connect),
            value = name,
            subtitle = stringResource(R.string.remote_needs_android, 17).takeUnless { abilities.canConnect },
            icon = Icons.Default.BluetoothConnected,
            onClick = vm::connect,
        )
        NavRow(
            title = stringResource(R.string.remote_disconnect),
            subtitle = stringResource(R.string.remote_needs_android, 17).takeUnless { abilities.canConnect },
            icon = Icons.Default.BluetoothDisabled,
            onClick = vm::disconnect,
        )
        HorizontalDivider()
        NavRow(
            title = stringResource(R.string.remote_rename),
            value = state.alias,
            subtitle = if (abilities.canRename) stringResource(R.string.remote_rename_subtitle) else stringResource(R.string.remote_needs_android, 13),
            icon = Icons.Default.Edit,
            onClick = { showRename = true },
        )
        NavRow(
            title = stringResource(R.string.remote_forget),
            subtitle = stringResource(R.string.remote_needs_android, 16).takeUnless { abilities.canForget },
            icon = Icons.Default.Delete,
            onClick = { showForget = true },
        )
        NavRow(
            title = stringResource(R.string.remote_unlink),
            subtitle = stringResource(R.string.remote_unlink_subtitle),
            icon = Icons.Default.LinkOff,
            onClick = vm::unlink,
        )
    }

    if (showRename) {
        var alias by rememberSaveable { mutableStateOf(state.alias ?: state.target?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.remote_rename)) },
            text = {
                Column {
                    OutlinedTextField(value = alias, onValueChange = { alias = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.remote_rename_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showRename = false; vm.rename(alias) }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showForget) {
        ConfirmDialog(
            title = stringResource(R.string.remote_forget),
            text = stringResource(R.string.remote_forget_confirm, name.orEmpty()),
            confirmLabel = stringResource(R.string.remote_forget),
            destructive = true,
            onConfirm = { showForget = false; vm.forget() },
            onDismiss = { showForget = false },
        )
    }
}
