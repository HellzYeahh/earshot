package dev.earshot.extras.widget

import dev.earshot.ui.theme.FieldDarkScheme
import dev.earshot.ui.theme.FieldLightScheme
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
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
import dev.earshot.extras.HeadphoneLabels
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
                val buds = HeadphoneLabels.hasBudBatteries(state)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Earbuds: "L 64% · R 71%" is too wide for the big figure; it takes the whole line.
                    Text(
                        batteryText(state),
                        style = TextStyle(fontSize = if (buds) 18.sp else 26.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                        maxLines = 1,
                    )
                    if (!buds) {
                        Spacer(GlanceModifier.width(8.dp))
                        Text(secondaryLine(state), style = title(), maxLines = 1)
                    }
                }
                if (buds) Text(secondaryLine(state), style = body(), maxLines = 1)
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
                Text(HeadphoneLabels.displayName(state) ?: "Earshot", style = title(), maxLines = 1)
                Text(
                    if (state.isConnected) {
                        listOfNotNull(
                            secondaryLine(state).takeIf { state.capabilities.listeningModes },
                            "EQ ${s.eqPresetName ?: "flat"}",
                            HeadphoneLabels.caseShort(state.battery?.case),
                        ).joinToString(" · ")
                    } else statusLine(state),
                    style = body(), maxLines = 1,
                )
            }
            if (state.isConnected) {
                Text(
                    batteryText(state),
                    style = TextStyle(fontSize = if (HeadphoneLabels.hasBudBatteries(state)) 18.sp else 24.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.primary),
                    maxLines = 1,
                )
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        if (state.isConnected) BatteryBar(state)
        Spacer(GlanceModifier.defaultWeight())
        if (!state.isConnected) {
            Button(text = if (state.connection.status == ConnectionStatus.CONNECTING) "Connecting…" else "Connect",
                onClick = actionRunCallback<ConnectAction>(), modifier = GlanceModifier.fillMaxWidth(),
                enabled = state.connection.status != ConnectionStatus.CONNECTING)
        } else if (state.capabilities.listeningModes) {
            ModeButtons(state, s.cycle)
        }
    }

    @Composable
    private fun ModeButtons(state: HeadphoneState, cycle: List<Int>) {
        // Only slots with a real name: a "Mode 5" button (or a QC Ultra preset name on another
        // model) would switch to something the user can't recognise.
        val slots = TileCycle.order(cycle, state.modes)
            .mapNotNull { index -> HeadphoneLabels.modeName(state, index)?.let { index to it } }
            .take(3)
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            slots.forEachIndexed { i, (index, name) ->
                if (i > 0) Spacer(GlanceModifier.width(6.dp))
                val selected = state.currentModeIndex == index
                Button(
                    text = name,
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

    /** "72%" (headphones) or "L 64% · R 71%" (earbuds), with a bolt while charging. */
    private fun batteryText(state: HeadphoneState): String =
        HeadphoneLabels.batteryShort(state)?.let { it + if (state.isCharging == true) " ⚡" else "" } ?: "—"

    /** The current mode, or for a model without modes its name, so the line is never a dash. */
    private fun secondaryLine(state: HeadphoneState): String = when {
        !state.capabilities.listeningModes -> HeadphoneLabels.displayName(state) ?: "Connected"
        else -> state.currentMode?.name?.takeIf { it.isNotBlank() } ?: "—"
    }

    private fun statusLine(state: HeadphoneState): String = when (state.connection.status) {
        ConnectionStatus.CONNECTING -> "Connecting…"
        ConnectionStatus.NO_PERMISSION -> "Bluetooth permission needed"
        ConnectionStatus.NO_PAIRED_DEVICE -> "No headphones paired"
        else -> "Disconnected"
    }

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
    /** Field's own schemes (ui/theme/FieldPalette.kt), so the widget can't drift from the app. */
    @Composable
    fun providers() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else ColorProviders(FieldLightScheme, FieldDarkScheme)
}
