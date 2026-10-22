@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package dev.earshot.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.HeadphoneState
import dev.earshot.core.OperationMode
import dev.earshot.core.SecureSessionStatus
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.theme.EarshotTheme

/**
 * Small status chips under the Home header: mic muted, charging, live updates. Renders nothing
 * when there's nothing to say, so the layout doesn't shift on an unknown state. The
 * firmware-update-mode warning is a full banner because it explains why settings stop working.
 */
@Composable
fun StatusChips(state: HeadphoneState, modifier: Modifier = Modifier) {
    val showMic = state.micMuted == true
    val showCharging = state.isCharging == true
    val showLive = state.livePushEnabled && state.isConnected
    val updateMode = state.operationMode == OperationMode.FIRMWARE_UPDATE_ONLY
    val secure = state.secureSession
    val secureProblem = state.isConnected &&
        (secure.status == SecureSessionStatus.FAILED || secure.status == SecureSessionStatus.DECLINED)
    if (!showMic && !showCharging && !showLive && !updateMode && !secureProblem) return

    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (updateMode) {
            InfoBanner(stringResource(R.string.firmware_update_mode_warning), tone = BannerTone.WARNING, icon = Icons.Default.Warning)
        }
        if (secureProblem) {
            InfoBanner(
                stringResource(
                    if (secure.status == SecureSessionStatus.DECLINED) R.string.secure_declined else R.string.secure_failed,
                    secure.message.orEmpty(),
                ),
                tone = BannerTone.WARNING,
                icon = Icons.Default.Warning,
            )
        }
        if (showMic || showCharging || showLive) {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showMic) StatusChip(stringResource(R.string.chip_mic_muted), icon = Icons.Default.MicOff)
                if (showCharging) StatusChip(stringResource(R.string.chip_charging), icon = Icons.Default.Bolt)
                if (showLive) {
                    val hint = stringResource(R.string.chip_live_updates_hint)
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(hint) } },
                        state = rememberTooltipState(),
                    ) {
                        StatusChip(stringResource(R.string.chip_live_updates), dot = true, description = hint)
                    }
                }
            }
        }
    }
}

/** An inverted tag: ink on sand by day, sand on ink at night, monospace capitals. */
@Composable
private fun StatusChip(label: String, icon: ImageVector? = null, dot: Boolean = false, description: String? = null) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = EarshotTheme.colors.chip,
        contentColor = EarshotTheme.colors.onChip,
        modifier = if (description != null) Modifier.semantics { contentDescription = "$label. $description" } else Modifier,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(7.dp))
            }
            if (dot) {
                // The orange lamp: "live" without an icon that reads as a warning.
                androidx.compose.foundation.layout.Box(Modifier.size(8.dp).background(EarshotTheme.colors.accent, CircleShape))
                Spacer(Modifier.width(7.dp))
            }
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall)
        }
    }
}
