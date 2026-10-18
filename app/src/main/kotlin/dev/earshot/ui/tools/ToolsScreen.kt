package dev.earshot.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.appContainer
import dev.earshot.core.CodecState
import dev.earshot.core.HeadphoneState
import dev.earshot.core.fake.FakeCodecRepository
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.shortcut.ShortcutAction
import dev.earshot.shortcut.ShortcutButtonPolicy
import dev.earshot.shortcut.ShortcutSettings
import dev.earshot.ui.LocalUiDependencies
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.earshotViewModel
import dev.earshot.ui.more.SettingsViewModel

/** Where the Tools tab's rows lead. */
data class ToolsNavigation(
    val onOpenShortcuts: () -> Unit = {},
    val onOpenDevices: () -> Unit = {},
    val onOpenConnection: () -> Unit = {},
    val onOpenCodec: () -> Unit = {},
    val onOpenCallAudio: () -> Unit = {},
    val onOpenMicCheck: () -> Unit = {},
    val onOpenLab: () -> Unit = {},
)

@Composable
fun ToolsScreen(contentPadding: PaddingValues, navigation: ToolsNavigation) {
    val deps = LocalUiDependencies.current
    val codec by deps.codec.state.collectAsStateWithLifecycle()
    val vm = earshotViewModel { SettingsViewModel(it.headphones, it.messenger) }
    val headphones by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shortcutSettings by remember { context.appContainer.shortcuts.prefs.settings }.collectAsStateWithLifecycle(ShortcutSettings())
    // The choice for the headphones Earshot is connected to (what the remapper shows first), else the default.
    val connected = headphones.isConnected
    val shortcut = ShortcutButtonPolicy.displayedChoice(
        shortcutSettings,
        headphones.connection.deviceAddress.takeIf { connected },
        headphones.shortcutButton.takeIf { connected },
    )
    ToolsContent(
        codec = codec,
        headphones = headphones,
        shortcut = shortcut,
        contentPadding = contentPadding,
        navigation = navigation,
        controls = HeadphoneControlsCallbacks(
            onTouchControls = vm::setTouchControls,
            onVoicePrompts = vm::setVoicePrompts,
            onRename = vm::rename,
            onPowerOff = vm::powerOff,
        ),
    )
}

@Composable
fun ToolsContent(
    codec: CodecState,
    headphones: HeadphoneState,
    shortcut: ShortcutAction,
    contentPadding: PaddingValues,
    navigation: ToolsNavigation,
    controls: HeadphoneControlsCallbacks,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_tools), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        ToolCard(
            icon = Icons.Default.TouchApp,
            title = stringResource(R.string.tools_shortcuts),
            subtitle = stringResource(
                R.string.tools_shortcuts_summary,
                shortcutSummary(shortcut, HeadphoneLabels.isEarbuds(HeadphoneLabels.formFactor(headphones))),
            ),
            onClick = navigation.onOpenShortcuts,
        )
        HeadphoneControlsSection(headphones, controls, navigation.onOpenDevices)
        ToolCard(
            icon = Icons.Default.Bluetooth,
            title = stringResource(R.string.tools_connection),
            subtitle = stringResource(R.string.tools_connection_subtitle),
            onClick = navigation.onOpenConnection,
        )
        ToolCard(
            icon = Icons.Default.SettingsInputAntenna,
            title = stringResource(R.string.tools_codec),
            subtitle = codec.current?.let { "${it.codecName} · ${describeRate(it.sampleRateHz)} · ${it.bitsPerSample ?: "?"} bit" }
                ?: stringResource(R.string.codec_not_streaming),
            onClick = navigation.onOpenCodec,
        )
        ToolCard(
            icon = Icons.Default.Call,
            title = stringResource(R.string.tools_call_audio),
            subtitle = stringResource(R.string.tools_call_audio_subtitle),
            onClick = navigation.onOpenCallAudio,
        )
        ToolCard(
            icon = Icons.Default.Mic,
            title = stringResource(R.string.tools_mic_check),
            subtitle = stringResource(R.string.tools_mic_check_subtitle),
            onClick = navigation.onOpenMicCheck,
        )
        ToolCard(
            icon = Icons.Default.Science,
            title = stringResource(R.string.tools_lab),
            subtitle = stringResource(R.string.tools_lab_subtitle),
            onClick = navigation.onOpenLab,
        )
    }
}

@Composable
private fun ToolCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun describeRate(hz: Int?): String = when {
    hz == null -> "?"
    hz % 1000 == 0 -> "${hz / 1000} kHz"
    else -> String.format(java.util.Locale.US, "%.1f kHz", hz / 1000.0)
}

@LightDarkPreview
@Composable
private fun ToolsPreview() {
    val state by remember { FakeCodecRepository() }.state.collectAsStateWithLifecycle()
    EarshotPreview {
        ToolsContent(
            state, FakeHeadphoneRepository.connectedState(), ShortcutAction.CycleEq, PaddingValues(0.dp),
            ToolsNavigation(), HeadphoneControlsCallbacks(),
        )
    }
}

/** Earbuds: earbud wording, only the rows the model has. */
@LightDarkPreview
@Composable
private fun ToolsEarbudsPreview() {
    val state by remember { FakeCodecRepository() }.state.collectAsStateWithLifecycle()
    EarshotPreview {
        ToolsContent(
            state, FakeHeadphoneRepository.connectedEarbudsState(), ShortcutAction.OpenAssistant(), PaddingValues(0.dp),
            ToolsNavigation(), HeadphoneControlsCallbacks(),
        )
    }
}
