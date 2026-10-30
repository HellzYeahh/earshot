@file:OptIn(ExperimentalMaterial3Api::class)

package dev.earshot.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.HeadphoneState
import dev.earshot.core.SupportTier
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.ui.LocalUiDependencies
import dev.earshot.ui.theme.EarshotTheme
import dev.earshot.ui.tools.DiagnosticsReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * The support tier as one component for every brand (1.0 plan item 4, roadmap 0.9 B3 and B8): a
 * small tag, and a tap that opens a sheet saying in a sentence or two what works in that tier. An
 * Experimental headset's sheet adds "Send a report", which hands the connection report to the
 * Android share menu. Nothing leaves the phone except through the app the user picks there.
 */

/**
 * The connected headset's tier tag with its sheet; renders nothing while [SupportTier.of] has no
 * tier (not connected, or the model not read yet), so a layout around it doesn't jump on a guess.
 */
@Composable
fun SupportTierChip(state: HeadphoneState, modifier: Modifier = Modifier) {
    val tier = SupportTier.of(state) ?: return
    var open by rememberSaveable { mutableStateOf(false) }
    SupportTierTag(tier, onClick = { open = true }, modifier = modifier)
    if (!open) return

    val context = LocalContext.current
    val messenger = LocalUiDependencies.current.messenger
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    // The model when the driver knows it ("QuietComfort Ultra Headphones · 2nd Gen"), else the headset's own name.
    val headsetName = HeadphoneLabels.modelLine(state.info) ?: HeadphoneLabels.displayName(state)
    val subject = headsetName?.let { stringResource(R.string.support_report_subject, it) } ?: stringResource(R.string.support_report_subject_plain)
    val prompt = stringResource(R.string.support_report_prompt)
    val chooserTitle = stringResource(R.string.support_report_chooser)
    SupportTierSheet(
        tier = tier,
        headsetName = headsetName,
        sending = sending,
        onSendReport = {
            if (!sending) {
                sending = true
                // The scope ends with the sheet: dismissing it while the report is built just drops the share.
                scope.launch {
                    try {
                        shareReport(context, subject, prompt, chooserTitle)
                        open = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        messenger.report(e)
                    } finally {
                        sending = false
                    }
                }
            }
        },
        onDismiss = { open = false },
    )
}

/**
 * The tag itself: a hairline-framed legend with a lamp (green for Tested, the orange indicator for
 * Experimental, grey otherwise), in the monospace capitals of the status chips. The frame is drawn
 * at the text's size; the touch target is 48 dp.
 */
@Composable
fun SupportTierTag(tier: SupportTier, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(tier.labelRes())
    val description = stringResource(R.string.support_tag_description, label)
    val action = stringResource(R.string.support_tag_action)
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .border(1.dp, EarshotTheme.colors.hairline, shape)
            .clickable(onClickLabel = action, role = Role.Button, onClick = onClick)
            // One TalkBack stop: "Support: Experimental", not the capitals and the icon one by one.
            .clearAndSetSemantics { contentDescription = description }
            .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TierLamp(tier)
        Spacer(Modifier.width(6.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(4.dp))
        // Says "there is more behind this", like the arrow next to the headset's name.
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun TierLamp(tier: SupportTier, size: Dp = 8.dp) {
    Box(Modifier.size(size).background(tierColor(tier), CircleShape))
}

@Composable
private fun tierColor(tier: SupportTier): Color = when (tier) {
    SupportTier.TESTED -> EarshotTheme.colors.ok
    SupportTier.EXPERIMENTAL -> EarshotTheme.colors.accent
    SupportTier.BATTERY_ONLY, SupportTier.ANY_HEADSET -> MaterialTheme.colorScheme.outline
}

/** The tier's sheet: its name, the headset, what works, and for an Experimental headset the report loop. */
@Composable
fun SupportTierSheet(
    tier: SupportTier,
    headsetName: String?,
    sending: Boolean,
    onSendReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, contentWindowInsets = { WindowInsets(0) }) {
        SupportTierSheetContent(
            tier = tier,
            headsetName = headsetName,
            sending = sending,
            onSendReport = onSendReport,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@Composable
fun SupportTierSheetContent(
    tier: SupportTier,
    headsetName: String?,
    sending: Boolean,
    onSendReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TierLamp(tier, size = 10.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(tier.labelRes()),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (headsetName != null) {
            Text(
                headsetName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(tier.whatWorksRes()), style = MaterialTheme.typography.bodyLarge)
        if (tier.wantsReports) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.support_report_why),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onSendReport,
                enabled = !sending,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                } else {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.support_report_send))
            }
            Text(
                stringResource(R.string.support_report_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@StringRes
fun SupportTier.labelRes(): Int = when (this) {
    SupportTier.TESTED -> R.string.support_tier_tested
    SupportTier.EXPERIMENTAL -> R.string.support_tier_experimental
    SupportTier.BATTERY_ONLY -> R.string.support_tier_battery_only
    SupportTier.ANY_HEADSET -> R.string.support_tier_any_headset
}

@StringRes
fun SupportTier.whatWorksRes(): Int = when (this) {
    SupportTier.TESTED -> R.string.support_what_tested
    SupportTier.EXPERIMENTAL -> R.string.support_what_experimental
    SupportTier.BATTERY_ONLY -> R.string.support_what_battery_only
    SupportTier.ANY_HEADSET -> R.string.support_what_any_headset
}

/** The shared text, pure for `SupportReportTest`. */
object SupportReport {
    /** [prompt] on the first line, room to answer under it, then the report as [DiagnosticsReport] built it. */
    fun text(prompt: String, report: String): String = "$prompt\n\n\n\n${report.trimEnd()}\n"
}

/**
 * Build the connection report and hand it to the Android share menu (ACTION_SEND, text/plain). The
 * report is what Lab's "Copy connection report" copies; the user picks where it goes, or nowhere.
 */
private suspend fun shareReport(context: Context, subject: String, prompt: String, chooserTitle: String) {
    val report = DiagnosticsReport.build(context)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, SupportReport.text(prompt, report))
    val chooser = Intent.createChooser(send, chooserTitle)
    // From an Activity the chooser stacks on top of Earshot; any other context needs a task of its own.
    if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ---- Previews ---------------------------------------------------------------------------------

@LightDarkPreview
@Composable
private fun SupportTierTagsPreview() {
    EarshotPreview {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SupportTier.entries.forEach { SupportTierTag(it, onClick = {}) }
        }
    }
}

@LightDarkPreview
@Composable
private fun SupportTierSheetPreview() {
    EarshotPreview {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                SupportTierSheetContent(
                    tier = SupportTier.EXPERIMENTAL,
                    headsetName = "Sony WH-1000XM5",
                    sending = false,
                    onSendReport = {},
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                SupportTierSheetContent(
                    tier = SupportTier.BATTERY_ONLY,
                    headsetName = "AirPods Pro",
                    sending = false,
                    onSendReport = {},
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }
    }
}
