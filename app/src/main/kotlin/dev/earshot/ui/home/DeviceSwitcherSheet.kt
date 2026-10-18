@file:OptIn(ExperimentalMaterial3Api::class)

package dev.earshot.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.HeadphoneCandidate
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.NavRow
import dev.earshot.ui.common.SectionHeading

/** Which bonded devices the switcher lists, and how. Pure, for `DeviceSwitcherListTest`. */
object DeviceSwitcherList {

    data class Entry(val candidate: HeadphoneCandidate, val label: String, val isCurrent: Boolean)

    data class Lists(val bose: List<Entry>, val otherAudio: List<Entry>)

    /**
     * Bose products first. Earbuds on LE Audio are bonded twice ("Bose QC Ultra Earbuds" and
     * "LE-Bose QC Ultra Earbuds"), which would read as the same product listed twice: one entry
     * per name, keeping the current address, else the Classic one (the BMAP channel lives there).
     * Other audio devices are listed only when nothing looks like Bose (a renamed product).
     */
    fun build(candidates: List<HeadphoneCandidate>, currentAddress: String?): Lists {
        fun entries(list: List<HeadphoneCandidate>): List<Entry> = list
            .groupBy { HeadphoneLabels.stripLePrefix(it.name ?: it.address).lowercase() }
            .map { (_, twins) ->
                val pick = twins.firstOrNull { it.address.equals(currentAddress, ignoreCase = true) }
                    ?: twins.firstOrNull { it.name?.let(HeadphoneLabels::stripLePrefix) == it.name?.trim() }
                    ?: twins.first()
                val current = twins.any { it.address.equals(currentAddress, ignoreCase = true) }
                Entry(pick, HeadphoneLabels.stripLePrefix(pick.name ?: pick.address), current)
            }
            .sortedWith(compareByDescending<Entry> { it.isCurrent }.thenBy { it.label.lowercase() })
        val bose = entries(candidates.filter { it.looksLikeBose })
        val other = if (bose.isEmpty()) entries(candidates.filter { !it.looksLikeBose && it.isAudioDevice }) else emptyList()
        return Lists(bose, other)
    }
}

/**
 * The quick switcher behind the Home header: the bonded Bose products, the current one marked,
 * a tap to connect to another, and a way to pair a new one in Android's settings.
 * [candidates] null = still loading.
 */
@Composable
fun DeviceSwitcherSheet(
    candidates: List<HeadphoneCandidate>?,
    currentAddress: String?,
    onPick: (HeadphoneCandidate) -> Unit,
    onPairNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, contentWindowInsets = { WindowInsets(0) }) {
        DeviceSwitcherContent(
            candidates = candidates,
            currentAddress = currentAddress,
            onPick = onPick,
            onPairNew = onPairNew,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@Composable
fun DeviceSwitcherContent(
    candidates: List<HeadphoneCandidate>?,
    currentAddress: String?,
    onPick: (HeadphoneCandidate) -> Unit,
    onPairNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
    ) {
        Text(stringResource(R.string.switcher_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.switcher_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        if (candidates == null) {
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.onboarding_scanning), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val lists = DeviceSwitcherList.build(candidates, currentAddress)
            if (lists.bose.isEmpty()) {
                Text(
                    stringResource(R.string.switcher_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            lists.bose.forEach { entry ->
                DeviceRow(entry, formFactorIcon(HeadphoneLabels.formFactorFromName(entry.label)), onPick)
            }
            if (lists.otherAudio.isNotEmpty()) {
                SectionHeading(stringResource(R.string.switcher_other_audio))
                lists.otherAudio.forEach { entry -> DeviceRow(entry, Icons.Default.Speaker, onPick) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        NavRow(
            title = stringResource(R.string.switcher_pair_new),
            subtitle = stringResource(R.string.switcher_pair_new_subtitle),
            icon = Icons.Default.Add,
            onClick = onPairNew,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DeviceRow(entry: DeviceSwitcherList.Entry, icon: ImageVector, onPick: (HeadphoneCandidate) -> Unit) {
    val currentLabel = stringResource(R.string.switcher_current)
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .semantics { selected = entry.isCurrent }
            // The current one is not a no-op: picking it again reconnects, which is what a user
            // tapping it while it shows "Not connected" wants.
            .clickable(role = Role.Button) { onPick(entry.candidate) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (entry.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (entry.isCurrent) currentLabel else entry.candidate.address,
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.isCurrent) {
            Icon(Icons.Default.CheckCircle, contentDescription = currentLabel, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@LightDarkPreview
@Composable
private fun DeviceSwitcherPreview() {
    EarshotPreview {
        DeviceSwitcherContent(
            candidates = listOf(
                HeadphoneCandidate(FakeHeadphoneRepository.DEVICE_ADDRESS, FakeHeadphoneRepository.DEVICE_NAME, looksLikeBose = true, isAudioDevice = true),
                HeadphoneCandidate(FakeHeadphoneRepository.EARBUDS_ADDRESS, FakeHeadphoneRepository.EARBUDS_NAME, looksLikeBose = true, isAudioDevice = true),
                HeadphoneCandidate("AC:BF:71:12:34:99", "Bose QC Ultra 2 Earbuds", looksLikeBose = true, isAudioDevice = true),
                HeadphoneCandidate("AA:BB:CC:DD:EE:03", "Car stereo", looksLikeBose = false, isAudioDevice = true),
            ),
            currentAddress = FakeHeadphoneRepository.DEVICE_ADDRESS,
            onPick = {}, onPairNew = {},
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@LightDarkPreview
@Composable
private fun DeviceSwitcherLoadingPreview() {
    EarshotPreview {
        DeviceSwitcherContent(candidates = null, currentAddress = null, onPick = {}, onPairNew = {}, modifier = Modifier.padding(top = 16.dp))
    }
}
