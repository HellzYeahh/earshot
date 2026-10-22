package dev.earshot.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import dev.earshot.ui.theme.EarshotTheme

/**
 * Determinate progress drawn like [FieldSlider]'s track: flat, square-ended, olive on sand,
 * without Material 3's gap and end dot. [progress] is read in the draw phase, as Material's is.
 */
@Composable
fun FieldProgressBar(progress: () -> Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier.fillMaxWidth().height(4.dp),
        color = EarshotTheme.colors.fill,
        trackColor = EarshotTheme.colors.track,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
