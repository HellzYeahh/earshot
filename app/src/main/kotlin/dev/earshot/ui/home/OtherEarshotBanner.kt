package dev.earshot.ui.home

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.earshot.R
import dev.earshot.distribution.LegacyApp
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.InfoBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Another Earshot on this phone (GitHub build only, [LegacyApp]), shown next to the connection
 * status because that is where it bites: only one app can hold the headset's control channel, and
 * the one that loses only sees a busy or silent headset. The pre-1.0 app gets the short migration
 * hint (the full one is on Back up and restore), the Google Play build a neutral note. Both offer
 * that app's App info page. "Not now" hides it until Earshot starts again.
 */
@Composable
fun OtherEarshotBanner(modifier: Modifier = Modifier) {
    if (OtherEarshotDismissal.dismissed) return
    val context = LocalContext.current
    // Re-checked on every return to the app: the user may just have removed the other one.
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }
    // A package manager read with the signer's certificate: off the main thread.
    val kind by produceState<LegacyApp.Kind?>(null, checks) {
        value = withContext(Dispatchers.IO) { LegacyApp.find(context) }
    }
    val found = kind ?: return
    val legacy = found == LegacyApp.Kind.LEGACY
    InfoBanner(
        text = stringResource(if (legacy) R.string.home_legacy_app else R.string.backup_other_earshot),
        modifier = modifier,
        tone = if (legacy) BannerTone.WARNING else BannerTone.INFO,
        action = {
            TextButton(onClick = { OtherEarshotDismissal.dismissed = true }) { Text(stringResource(R.string.action_not_now)) }
            TextButton(onClick = { LegacyApp.openAppInfo(context) }) {
                Text(stringResource(if (legacy) R.string.backup_legacy_open else R.string.backup_other_earshot_open))
            }
        },
    )
}

/** "Not now" for this run of the app: back at the next start (a tab switch or rotation keeps it hidden). */
private object OtherEarshotDismissal {
    var dismissed by mutableStateOf(false)
}
