package dev.earshot.extras.widget

import android.content.Context
import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.earshot.MainActivity
import dev.earshot.appContainer
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.extras.tile.TileCycle

/**
 * Home-screen widget. Two layouts chosen by the cell size the launcher gives us:
 * compact (~2x1: battery + mode) and regular (4x2: header, battery bar, EQ, mode buttons).
 *
 * Reads repository state directly at render time; the engine's [WidgetUpdater] throttle keeps
 * it fresh while the app process lives. When the process is dead the launcher only asks for a
 * redraw every `updatePeriodMillis` (30 min), which is an Android limit, not ours.
 */
class EarshotWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, REGULAR))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = context.appContainer
        val snapshot = Snapshot(
            state = container.headphones.state.value,
            eqPresetName = container.tuning.state.value.activePreset?.name,
            cycle = container.automation.preferences.value.tileCycleModes,
        )
        provideContent {
            GlanceTheme(colors = WidgetColors.providers()) {
                val compact = LocalSize.current.width < REGULAR.width
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(if (compact) 10.dp else 14.dp),
                ) {
                    if (compact) Compact(snapshot) else Regular(snapshot)
                }
            }
        }
    }

    private data class Snapshot(val state: HeadphoneState, val eqPresetName: String?, val cycle: List<Int>)

    // ---- layouts ---------------------------------------------------------------------------

    @Composable
    private fun Compact(s: Snapshot) {
        val state = s.state
        Column(
            modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!state.isConnected) {
                Text(statusLine(state), style = title())
                Spacer(GlanceModifier.height(6.dp))
                Button(text = "Connect", onClick = actionRunCallback<ConnectAction>(), modifier = GlanceModifier.fillMaxWidth())
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(batteryText(state), style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
                    Spacer(GlanceModifier.width(8.dp))
                    Text(state.currentMode?.name ?: "—", style = title())
                }
                Spacer(GlanceModifier.height(6.dp))
                BatteryBar(state)
            }
        }
    }

    @Composable
    private fun Regular(s: Snapshot) = Column(modifier = GlanceModifier.fillMaxSize()) {
        val state = s.state
        Row(
            modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(state.connection.deviceName ?: "Earshot", style = title(), maxLines = 1)
                Text(
                    if (state.isConnected) "${state.currentMode?.name ?: "—"} · EQ ${s.eqPresetName ?: "flat"}" else statusLine(state),
                    style = body(), maxLines = 1,
                )
            }
            if (state.isConnected) {
                Text(batteryText(state), style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.primary))
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        if (state.isConnected) BatteryBar(state)
        Spacer(GlanceModifier.defaultWeight())
        if (!state.isConnected) {
            Button(text = if (state.connection.status == ConnectionStatus.CONNECTING) "Connecting…" else "Connect",
                onClick = actionRunCallback<ConnectAction>(), modifier = GlanceModifier.fillMaxWidth(),
                enabled = state.connection.status != ConnectionStatus.CONNECTING)
        } else {
            ModeButtons(state, s.cycle)
        }
    }

    @Composable
    private fun ModeButtons(state: HeadphoneState, cycle: List<Int>) {
        val slots = TileCycle.order(cycle, state.modes).take(3)
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            slots.forEachIndexed { i, index ->
                if (i > 0) Spacer(GlanceModifier.width(6.dp))
                val selected = state.currentModeIndex == index
                Button(
                    text = modeName(state, index),
                    onClick = actionRunCallback<SetModeAction>(actionParametersOf(SetModeAction.MODE_INDEX to index)),
                    modifier = GlanceModifier.defaultWeight(),
                    enabled = !state.isBusy,
                    maxLines = 1,
                    colors = if (selected) ButtonDefaults.buttonColors(
                        backgroundColor = GlanceTheme.colors.primary, contentColor = GlanceTheme.colors.onPrimary,
                    ) else ButtonDefaults.buttonColors(
                        backgroundColor = GlanceTheme.colors.secondaryContainer, contentColor = GlanceTheme.colors.onSecondaryContainer,
                    ),
                )
            }
        }
    }

    @Composable
    private fun BatteryBar(state: HeadphoneState) {
        val pct = state.batteryPercent ?: return
        val low = pct <= 20 && state.isCharging != true
        LinearProgressIndicator(
            progress = pct / 100f,
            modifier = GlanceModifier.fillMaxWidth().height(6.dp),
            color = if (low) GlanceTheme.colors.error else GlanceTheme.colors.primary,
            backgroundColor = GlanceTheme.colors.surfaceVariant,
        )
    }

    // ---- helpers ---------------------------------------------------------------------------

    @Composable private fun title() = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface)
    @Composable private fun body() = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant)

    private fun batteryText(state: HeadphoneState): String =
        state.batteryPercent?.let { "$it%" + if (state.isCharging == true) "⚡" else "" } ?: "—"

    private fun statusLine(state: HeadphoneState): String = when (state.connection.status) {
        ConnectionStatus.CONNECTING -> "Connecting…"
        ConnectionStatus.NO_PERMISSION -> "Bluetooth permission needed"
        ConnectionStatus.NO_PAIRED_DEVICE -> "No Bose headphones paired"
        else -> "Disconnected"
    }

    private fun modeName(state: HeadphoneState, index: Int): String =
        state.modes.firstOrNull { it.index == index }?.name?.takeIf { it.isNotBlank() }
            ?: ListeningMode.PRESET_NAMES.getOrNull(index) ?: "Mode $index"

    companion object {
        val COMPACT = DpSize(110.dp, 50.dp)
        val REGULAR = DpSize(250.dp, 110.dp)
    }
}

/**
 * Material 3 colour providers for the widget. Android 12+ gets the wallpaper-derived dynamic
 * palette (the default `GlanceTheme.colors`); older phones get fixed light/dark schemes,
 * because the `system_accent*` resources do not exist there.
 */
internal object WidgetColors {
    private val light = lightColorScheme(
        primary = Color(0xFF3F5F9A), onPrimary = Color.White,
        secondaryContainer = Color(0xFFDCE4F4), onSecondaryContainer = Color(0xFF1A2742),
        surface = Color(0xFFF7F8FC), onSurface = Color(0xFF191C21), surfaceVariant = Color(0xFFE0E3EB),
        onSurfaceVariant = Color(0xFF44474F), error = Color(0xFFBA1A1A),
    )
    private val dark = darkColorScheme(
        primary = Color(0xFFAAC7FF), onPrimary = Color(0xFF0B305F),
        secondaryContainer = Color(0xFF3B4759), onSecondaryContainer = Color(0xFFD8E2F9),
        surface = Color(0xFF131519), onSurface = Color(0xFFE2E2E9), surfaceVariant = Color(0xFF2F3137),
        onSurfaceVariant = Color(0xFFC4C6D0), error = Color(0xFFFFB4AB),
    )

    @Composable
    fun providers() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else ColorProviders(light, dark)
}
