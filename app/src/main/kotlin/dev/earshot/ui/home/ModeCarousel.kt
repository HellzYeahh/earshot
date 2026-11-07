@file:OptIn(ExperimentalFoundationApi::class)

package dev.earshot.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import dev.earshot.ui.common.glowShadow
import dev.earshot.ui.theme.EarshotTheme
import dev.earshot.ui.theme.legend

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
        Text(stringResource(R.string.home_modes_title).legend(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (canEdit) TextButton(onClick = onEditModes, modifier = Modifier.heightIn(min = 48.dp)) {
            // A monospace label underlined in orange: Field's link.
            val underline = EarshotTheme.colors.accent
            Text(
                stringResource(R.string.action_edit_modes).legend(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.drawBehind {
                    val y = size.height + 3.dp.toPx()
                    drawLine(underline, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                },
            )
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
        // Room above and below for Aurora's glow, which the row would otherwise clip (Field has none).
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = if (EarshotTheme.style.squareControls) 4.dp else 12.dp),
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
    val colors = EarshotTheme.colors
    val shape = MaterialTheme.shapes.medium
    // Field: orange with an ink outline. Aurora: the accent gradient with a glow, no outline.
    val gradient = !EarshotTheme.style.squareControls
    Surface(
        color = if (gradient) Color.Transparent else colors.accent,
        contentColor = colors.onAccent,
        shape = shape,
        border = if (gradient) null else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface),
        modifier = Modifier
            .heightIn(min = 56.dp)
            .then(if (gradient) Modifier.glowShadow(colors.glow, shape).background(colors.accentBrush, shape) else Modifier)
            .semantics { selected = true; stateDescription = currentLabel },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.mode_custom).legend(), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(stringResource(R.string.mode_custom_subtitle), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

/**
 * Field: selected is orange with an ink outline. Aurora: selected is the accent gradient with a
 * glow. Otherwise a card-coloured tile with a hairline.
 */
@Composable
private fun ModeChip(
    mode: ListeningMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val colors = EarshotTheme.colors
    val shape = MaterialTheme.shapes.medium
    val gradient = !EarshotTheme.style.squareControls
    val tile = EarshotTheme.cardFill
    // Aurora's gradient and glow fade with the label colour instead of appearing at once; the
    // tile fades to its own colour at zero alpha (animating to Color.Transparent passes through black).
    val lit by animateFloatAsState(if (selected && gradient) 1f else 0f, label = "chipLit")
    val container by animateColorAsState(
        if (selected) (if (gradient) tile.copy(alpha = 0f) else colors.accent) else tile,
        label = "chipBg",
    )
    val content by animateColorAsState(
        if (selected) colors.onAccent else MaterialTheme.colorScheme.onSurface,
        label = "chipFg",
    )
    val outline by animateColorAsState(
        if (selected) (if (gradient) colors.hairline.copy(alpha = 0f) else EarshotTheme.chosenOutline) else colors.hairline,
        label = "chipBorder",
    )
    val favouriteLabel = stringResource(R.string.mode_favourite)
    val currentLabel = stringResource(R.string.mode_current)
    Surface(
        color = container,
        contentColor = content,
        shape = shape,
        border = BorderStroke(1.dp, outline),
        modifier = Modifier
            .heightIn(min = 56.dp)
            .then(if (lit > 0f) Modifier.glowShadow(colors.glow, shape, intensity = lit).background(colors.accentBrush, shape, alpha = lit) else Modifier)
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
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mode.isFavorite) {
                Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                mode.name.ifBlank { stringResource(R.string.mode_slot_number, mode.index) }.legend(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
