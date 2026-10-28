@file:OptIn(ExperimentalFoundationApi::class)

package dev.earshot.ui.sleep

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.CustomSound
import dev.earshot.dsp.sleep.LoopMaker
import dev.earshot.ui.common.ConfirmDialog
import dev.earshot.ui.theme.EarshotTheme

/*
 * The user's own sounds in the library: their tiles, the "Your own sound" tile that opens the
 * file picker, and the name and delete dialogs. Earshot copies the picked file and turns it into
 * a seamless loop (up to LoopMaker.MAX_SECONDS, the end blended into the start, levelled), so any
 * recording works, not only files made to loop.
 */

/** The user's own sounds, so any Sleep screen can name a layer that plays one. */
val LocalCustomSounds = compositionLocalOf<List<CustomSound>> { emptyList() }

/** A tile for one own sound: tap to add it to the mix or take it out, long-press to delete it. */
@Composable
fun CustomSoundTile(sound: CustomSound, selected: Boolean, onToggle: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EarshotTheme.colors
    val shape = MaterialTheme.shapes.small
    Surface(
        shape = shape,
        color = if (selected) colors.control else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) colors.onControl else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (selected) colors.control else colors.hairline),
        modifier = modifier
            .aspectRatio(1.15f)
            .clip(shape)
            .combinedClickable(
                role = Role.Checkbox,
                onLongClickLabel = stringResource(R.string.sleep_custom_delete_action),
                onLongClick = onDelete,
                onClick = onToggle,
            )
            .semantics { this.selected = selected },
    ) {
        TileContent(
            icon = { Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(28.dp)) },
            label = sound.name,
        )
    }
}

/** "Your own sound": opens the system file picker for audio. Shows a spinner while one is added. */
@Composable
fun AddCustomSoundTile(importing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.small
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = EarshotTheme.colors.accent,
        border = BorderStroke(1.dp, EarshotTheme.colors.accent),
        modifier = modifier
            .aspectRatio(1.15f)
            .clip(shape)
            .combinedClickable(enabled = !importing, role = Role.Button, onClick = onClick),
    ) {
        TileContent(
            icon = {
                if (importing) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                else Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(28.dp))
            },
            label = stringResource(if (importing) R.string.sleep_custom_adding else R.string.sleep_custom_add),
        )
    }
}

@Composable
private fun TileContent(icon: @Composable () -> Unit, label: String) {
    Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        icon()
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Asks for a name after a file was picked; [suggestion] is the file's name without its extension. */
@Composable
fun CustomSoundNameDialog(suggestion: String, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(suggestion) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_custom_name_title)) },
        text = {
            Column {
                Text(
                    pluralStringResource(R.plurals.sleep_custom_name_hint, LoopMaker.MAX_SECONDS, LoopMaker.MAX_SECONDS),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    label = { Text(stringResource(R.string.sleep_custom_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onAdd(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.sleep_custom_add_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun CustomSoundDeleteDialog(sound: CustomSound, onDelete: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.sleep_custom_delete_title),
        text = stringResource(R.string.sleep_custom_delete_text, sound.name),
        confirmLabel = stringResource(R.string.action_delete),
        destructive = true,
        onConfirm = onDelete,
        onDismiss = onDismiss,
    )
}

/** The picked document's file name without its extension, for the name dialog; "" when unknown. */
fun suggestedSoundName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()?.substringBeforeLast('.')?.replace('_', ' ')?.trim()?.take(MAX_NAME).orEmpty()

private const val MAX_NAME = 40
