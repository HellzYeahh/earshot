package dev.earshot.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.earshot.ui.theme.EarshotTheme

/**
 * The bottom tab bar (five tabs since the 0.9 Sleep tab) in Field's clothes: a hairline on top,
 * a squared orange indicator behind the chosen icon and monospace labels. Material's
 * `NavigationBar` only offers a pill indicator, so this is its own layout; the selection
 * semantics are the same.
 */
@Composable
fun FieldNavigationBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column {
            Box(Modifier.fillMaxWidth().height(1.dp).background(EarshotTheme.colors.hairline))
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                    .height(BarHeight)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.Top,
                content = content,
            )
        }
    }
}

@Composable
fun RowScope.FieldNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = EarshotTheme.colors
    val scheme = MaterialTheme.colorScheme
    val indicator by animateColorAsState(if (selected) colors.accent else Color.Transparent, label = "navIndicator")
    val iconTint by animateColorAsState(if (selected) colors.onAccent else scheme.onSurfaceVariant, label = "navIcon")
    val labelColor by animateColorAsState(if (selected) scheme.onSurface else scheme.onSurfaceVariant, label = "navLabel")
    Column(
        modifier
            .weight(1f)
            .height(BarHeight)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 30.dp).background(indicator, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides iconTint, content = icon)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val BarHeight = 80.dp
