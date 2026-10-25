package dev.earshot.ui.tools

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.appContainer
import dev.earshot.bt.BtTimeline
import dev.earshot.bt.LastSeen
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.find.FindPlayer
import dev.earshot.find.FindSide
import dev.earshot.find.FindState
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.LocalUiDependencies
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.UiMessenger
import dev.earshot.ui.common.earshotViewModel
import kotlinx.coroutines.flow.StateFlow

class FindViewModel(private val find: FindPlayer, messenger: UiMessenger) : EarshotViewModel(messenger) {
    val state: StateFlow<FindState> = find.state

    // On the app's scope (FindPlayer.start), so the notification's Find uses the same ring. The
    // screen keeps the display on while it rings: Android 17 silences an app that isn't visible.
    fun ring(side: FindSide) = find.start(side)
    fun stop() = find.stop()
}

/** Tools → Find my earbuds (roadmap B6): a tone in the headset, one side or both, plus when it was last seen. */
@Composable
fun FindScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val vm = earshotViewModel { FindViewModel(container.find, it.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
    val headphones by LocalUiDependencies.current.headphones.state.collectAsStateWithLifecycle()
    val events by remember { container.timeline.store.events }.collectAsStateWithLifecycle()
    val address = headphones.connection.deviceAddress
    // Ringing needs Earshot visible on Android 17 (background audio hardening): keep the display on.
    val view = LocalView.current
    val ringing = state.ringing != null
    DisposableEffect(ringing) {
        view.keepScreenOn = ringing
        onDispose { view.keepScreenOn = false }
    }
    FindContent(
        state = state,
        headsetName = headphones.connection.deviceName,
        earbuds = HeadphoneLabels.isEarbuds(HeadphoneLabels.formFactor(headphones)),
        connectedNow = headphones.isAudioActive,
        lastSeen = address?.let { BtTimeline.lastSeen(events, it) },
        onBack = onBack,
        onRing = vm::ring,
        onStop = vm::stop,
    )
}

@Composable
fun FindContent(
    state: FindState,
    headsetName: String?,
    earbuds: Boolean,
    connectedNow: Boolean,
    lastSeen: LastSeen?,
    onBack: () -> Unit,
    onRing: (FindSide) -> Unit,
    onStop: () -> Unit,
) {
    val title = stringResource(if (earbuds) R.string.tools_find_earbuds else R.string.tools_find_headphones)
    SubScreenScaffold(title = title, onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (earbuds) R.string.find_intro else R.string.find_intro_headphones),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            SectionCard {
                val name = headsetName ?: stringResource(R.string.find_this_headset)
                Text(
                    when {
                        headsetName == null -> stringResource(R.string.find_no_headset)
                        connectedNow -> stringResource(R.string.find_connected_now, name)
                        lastSeen != null -> stringResource(
                            R.string.find_last_seen,
                            name,
                            DateUtils.getRelativeTimeSpanString(lastSeen.timeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                        )
                        else -> stringResource(R.string.find_never_seen, name)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(12.dp))
                if (state.ringing != null) {
                    Text(stringResource(R.string.find_ringing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Button(shape = MaterialTheme.shapes.medium, onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Default.Stop, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.action_stop))
                    }
                } else if (earbuds) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { onRing(FindSide.LEFT) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.find_left))
                        }
                        Button(shape = MaterialTheme.shapes.medium, onClick = { onRing(FindSide.BOTH) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.find_both))
                        }
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { onRing(FindSide.RIGHT) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.find_right))
                        }
                    }
                } else {
                    Button(shape = MaterialTheme.shapes.medium, onClick = { onRing(FindSide.BOTH) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.find_ring))
                    }
                }
            }
            state.problem?.let { InfoBanner(it, tone = BannerTone.WARNING) }
            InfoBanner(stringResource(R.string.find_hint))
        }
    }
}

@LightDarkPreview
@Composable
private fun FindPreview() = EarshotPreview {
    FindContent(
        state = FindState(),
        headsetName = "Bose QC Ultra Earbuds",
        earbuds = true,
        connectedNow = false,
        lastSeen = LastSeen(System.currentTimeMillis() - 42 * 60_000, connected = false),
        onBack = {},
        onRing = {},
        onStop = {},
    )
}
