package dev.earshot.ui.sleep

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.theme.EarshotTheme

/**
 * One row of big choice tiles that scrolls sideways and snaps, in the style of Home's mode
 * carousel: the chosen tile orange with an ink outline, the others card-coloured with a hairline.
 * Used for the sleep timer and the noise-cancelling timer, where a wrapped grid of chips took
 * two or three rows. The chosen tile is scrolled into view, also when it changes elsewhere.
 */
@Composable
fun <T> SleepCarousel(
    options: List<T>,
    isSelected: (T) -> Boolean,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val selectedIndex = options.indexOfFirst(isSelected)
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }
    LazyRow(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(listState),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
        modifier = modifier,
    ) {
        itemsIndexed(options) { _, option ->
            CarouselTile(text = label(option), selected = isSelected(option), onClick = { onSelect(option) })
        }
    }
}

@Composable
private fun CarouselTile(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = EarshotTheme.colors
    val container by animateColorAsState(if (selected) colors.accent else MaterialTheme.colorScheme.surfaceContainer, label = "tileBg")
    val content by animateColorAsState(if (selected) colors.onAccent else MaterialTheme.colorScheme.onSurface, label = "tileFg")
    val outline by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else colors.hairline, label = "tileBorder")
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, outline),
        modifier = Modifier
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- Previews ---------------------------------------------------------------------------------

@LightDarkPreview
@Composable
private fun SleepCarouselPreview() = EarshotPreview {
    SleepCarousel(options = listOf(30, 60, 120, 180, 240), isSelected = { it == 60 }, label = { durationText(it) }, onSelect = {})
}
