@file:OptIn(ExperimentalMaterial3Api::class)

package dev.earshot.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.LightDarkPreview

/** The 0.5.0 highlights, once after updating. Dismissing (button, swipe or back) marks them seen. */
@Composable
fun WhatsNewSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, contentWindowInsets = { WindowInsets(0) }) {
        WhatsNewContent(onDone = onDismiss, modifier = Modifier.navigationBarsPadding())
    }
}

private data class Highlight(val icon: ImageVector, val text: Int)

private val HIGHLIGHTS = listOf(
    Highlight(Icons.Default.Gesture, R.string.whats_new_controls),
    Highlight(Icons.Default.Equalizer, R.string.whats_new_actions),
    Highlight(Icons.Default.TouchApp, R.string.whats_new_detect),
    Highlight(Icons.Default.Headphones, R.string.whats_new_headphone_controls),
    Highlight(Icons.Default.Build, R.string.whats_new_touch_fix),
    Highlight(Icons.Default.Call, R.string.whats_new_call_link),
)

@Composable
fun WhatsNewContent(onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Celebration, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.whats_new_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.whats_new_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            HIGHLIGHTS.forEach { h ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(h.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(h.text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.whats_new_done))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@LightDarkPreview
@Composable
private fun WhatsNewPreview() {
    EarshotPreview { WhatsNewContent(onDone = {}, modifier = Modifier.padding(top = 24.dp)) }
}
