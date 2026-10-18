@file:OptIn(ExperimentalFoundationApi::class)

package dev.earshot.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.ListeningMode

/**
 * Big chips for every preset and every configured custom slot. Tap = switch mode; long-press =
 * toggle favourite (the star). The firmware's slot order is the display order (no reordering).
 *
 * [customActive]: the headphones report `[31.3]` = 0xFF, i.e. the live settings no longer match
 * any slot; a selected, non-tappable "Custom" chip leads the row so the selection isn't invisible.
 */
@Composable
fun ModeCarousel(
    modes: List<ListeningMode>,
    currentIndex: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onToggleFavorite: (Int) -> Unit,
    onEditModes: () -> Unit,
    modifier: Modifier = Modifier,
    customActive: Boolean = false,
    /** False where modes cannot be edited (the Crusher's three modes are fixed): hides "Edit modes". */
    canEdit: Boolean = true,
    /** False where modes cannot be starred: the long-press does nothing. */
    canFavorite: Boolean = true,
) {
    val visible = modes.filter { it.isPreset || it.isConfigured }
    Row(modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.home_modes_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (canEdit) TextButton(onClick = onEditModes, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.action_edit_modes))
        }
    }
    val listState = rememberLazyListState()
    // Bring the selection into view: a newly used custom slot sits at the end of the row, and the
    // restored selection after a reconnect may be off-screen.
    LaunchedEffect(currentIndex, customActive, visible.size) {
        val target = if (customActive) 0 else visible.indexOfFirst { it.index == currentIndex }
        if (target >= 0) listState.animateScrollToItem(target)
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
    ) {
        if (customActive) item(key = "custom") { CustomChip() }
        items(visible, key = { it.index }) { mode ->
            ModeChip(
                mode = mode,
                selected = mode.index == currentIndex,
                enabled = enabled,
                onClick = { onSelect(mode.index) },
                onLongClick = { if (canFavorite) onToggleFavorite(mode.index) },
            )
        }
    }
}

/** The 0xFF state: shown selected, never tappable (there is no slot to switch to). */
@Composable
private fun CustomChip() {
    val currentLabel = stringResource(R.string.mode_current)
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .heightIn(min = 56.dp)
            .semantics { selected = true; stateDescription = currentLabel },
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.mode_custom), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(stringResource(R.string.mode_custom_subtitle), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun ModeChip(
    mode: ListeningMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "chipBg",
    )
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        label = "chipFg",
    )
    val favouriteLabel = stringResource(R.string.mode_favourite)
    val currentLabel = stringResource(R.string.mode_current)
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .heightIn(min = 56.dp)
            .semantics {
                role = Role.Button
                this.selected = selected
                stateDescription = listOfNotNull(
                    if (selected) currentLabel else null,
                    if (mode.isFavorite) favouriteLabel else null,
                ).joinToString(", ")
            }
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mode.isFavorite) {
                Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                mode.name.ifBlank { stringResource(R.string.mode_slot_number, mode.index) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
