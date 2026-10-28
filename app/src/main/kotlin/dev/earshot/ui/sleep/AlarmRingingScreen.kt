package dev.earshot.ui.sleep

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.SleepSession
import dev.earshot.core.fake.FakeSleepRepository
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.theme.EarshotTheme
import java.time.Instant
import java.time.ZoneId

/**
 * The ringing alarm, full screen. Hosted by the lock-screen `sleep.AlarmActivity` and by the
 * Sleep tab while it rings, so it holds no ViewModel: the host passes the session and the two
 * actions.
 *
 * Always light on black, whatever the theme: it's read half-asleep in a dark room, and on the
 * lock screen the app's theme may not apply. It pads itself for the system bars (safe drawing
 * insets) behind its own black background, so the host passes no padding.
 */
@Composable
fun AlarmRingingScreen(
    session: SleepSession.Ringing,
    onStop: () -> Unit,
    onSnooze: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow(1_000)
    val time = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime()
    val (clock, amPm) = SleepLogic.clockParts(time.hour, time.minute, rememberIs24Hour(), currentLocale())
    val accent = EarshotTheme.colors.accent
    val onAccent = EarshotTheme.colors.onAccent

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        // At least a screen tall, so Stop sits at the bottom where the thumb is; scrolls on a small one.
        val screen = maxHeight
        // Sized from the width, not the font scale, so "10:30 AM" stays on one line on a 360 dp phone.
        val clockSize = fixedSize(minOf(112.dp, (maxWidth - 48.dp) * 0.34f))
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = screen)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(32.dp))
                Icon(Icons.Default.Alarm, contentDescription = null, tint = accent, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    session.label?.takeIf { it.isNotBlank() } ?: stringResource(R.string.sleep_ringing_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextStrong,
                    textAlign = TextAlign.Center,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        clock,
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = clockSize, lineHeight = clockSize * 1.05f),
                        color = TextStrong,
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (amPm != null) {
                        Text(amPm, style = MaterialTheme.typography.titleLarge, color = TextMuted, maxLines = 1, modifier = Modifier.padding(start = 8.dp, bottom = 20.dp))
                    }
                }
                session.phoneFallbackReason?.let { reason ->
                    Text(reason, style = MaterialTheme.typography.bodyLarge, color = TextMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onStop,
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                ) {
                    Text(stringResource(R.string.sleep_ringing_stop), style = MaterialTheme.typography.displaySmall)
                }
                if (session.canSnooze) {
                    OutlinedButton(
                        onClick = onSnooze,
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, TextMuted),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextStrong),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    ) {
                        Icon(Icons.Default.Snooze, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        val label = session.snoozeMinutes?.let { stringResource(R.string.sleep_ringing_snooze_minutes, it) }
                            ?: stringResource(R.string.sleep_ringing_snooze)
                        Text(label, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
}

private val TextStrong = Color(0xFFF2F4EC)
private val TextMuted = Color(0xFFF2F4EC).copy(alpha = 0.68f)

// ---- Previews ---------------------------------------------------------------------------------

// Read outside composition (lint: StateFlow.value in a composable).
private val previewRinging = FakeSleepRepository().apply { ring() }.state.value.session as SleepSession.Ringing
private val previewFallback = FakeSleepRepository().apply { ring("The earbuds aren't connected, so it's ringing on the phone.") }
    .state.value.session as SleepSession.Ringing

@LightDarkPreview
@Composable
private fun AlarmRingingPreview() = EarshotPreview {
    AlarmRingingScreen(previewRinging.copy(snoozeMinutes = 10), onStop = {}, onSnooze = {})
}

@LightDarkPreview
@Composable
private fun AlarmRingingFallbackPreview() = EarshotPreview {
    AlarmRingingScreen(previewFallback.copy(label = "Work", canSnooze = false), onStop = {}, onSnooze = {})
}
