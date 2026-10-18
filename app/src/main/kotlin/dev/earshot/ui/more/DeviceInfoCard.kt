package dev.earshot.ui.more

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.HeadphoneState
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.SectionCard

/**
 * Model, generation, firmware and serial of the connected product, plus a gentle note when its
 * layout is a best guess rather than confirmed on hardware. Renders nothing before `[0.x]` is read.
 */
@Composable
fun DeviceInfoCard(state: HeadphoneState, modifier: Modifier = Modifier) {
    val info = state.info ?: return
    // The family is the model without the generation; show the full marketing name when known.
    val model = info.modelName ?: info.family ?: info.productName
    val rows = listOfNotNull(
        model?.let { R.string.info_model to it },
        info.generation?.let { R.string.info_generation to it },
        info.firmwareVersion?.let { R.string.info_firmware to it },
        info.serialNumber?.let { R.string.info_serial to it },
    )
    if (rows.isEmpty()) return
    val buds = HeadphoneLabels.isEarbuds(HeadphoneLabels.formFactor(state))
    SectionCard(
        modifier = modifier,
        title = stringResource(if (buds) R.string.settings_device_info_buds else R.string.settings_device_info),
    ) {
        rows.forEach { (label, value) -> InfoRow(stringResource(label), value) }
        if (state.isConnected && !state.capabilities.verified) {
            InfoBanner(
                stringResource(R.string.info_unverified),
                tone = BannerTone.INFO,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        // Selectable: the serial and firmware are what a support request asks for.
        SelectionContainer(Modifier.weight(1.4f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
