package dev.earshot.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.earshot.ui.theme.JetBrainsMono
import dev.earshot.R
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    AboutContent(version = version, onBack = onBack)
}

@Composable
fun AboutContent(version: String, onBack: () -> Unit) {
    SubScreenScaffold(title = stringResource(R.string.settings_about), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_not_affiliated), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InfoBanner(stringResource(R.string.about_control_channel))
            SectionCard(title = stringResource(R.string.about_credits)) {
                Text(stringResource(R.string.about_credits_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                CreditLine("bosectl", "github.com/aaronsb/bosectl", "MIT")
                CreditLine("Quiet Rebellion", "github.com/MadEyez/quietrebellion", "MIT")
                CreditLine("soundcorebridge", "github.com/mervin008/soundcorebridge", "MIT")
                CreditLine("momentum4-control", "github.com/Ariazonaa/momentum4-control", "MIT")
                CreditLine("momentumctl", "codeberg.org/galen/momentumctl", "MIT")
                CreditLine("SonyHeadphonesClient", "github.com/mos9527/SonyHeadphonesClient", "MIT")
                CreditLine("jbl-control", "github.com/AhmadRun/jbl-control", "MIT")
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.about_autoeq_credit), style = MaterialTheme.typography.bodyMedium)
                Text("github.com/jaakkopasanen/AutoEq", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_fonts_credit), style = MaterialTheme.typography.bodyMedium)
                CreditLine("Barlow, Barlow Condensed", "github.com/jpt/barlow", "OFL 1.1")
                CreditLine("JetBrains Mono", "github.com/JetBrains/JetBrainsMono", "OFL 1.1")
            }
            SectionCard(title = stringResource(R.string.about_licence)) {
                Text(stringResource(R.string.about_licence_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(MIT_TEXT, style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard(title = stringResource(R.string.about_privacy)) {
                Text(stringResource(R.string.about_privacy_body), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CreditLine(name: String, url: String, licence: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text("$name ($licence)", style = MaterialTheme.typography.titleSmall)
        Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

private const val MIT_TEXT = """MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

@LightDarkPreview
@Composable
private fun AboutPreview() {
    EarshotPreview { AboutContent("0.1.0", {}) }
}
