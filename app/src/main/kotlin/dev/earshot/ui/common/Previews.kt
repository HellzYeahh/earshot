package dev.earshot.ui.common

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.earshot.ui.LocalUiDependencies
import dev.earshot.ui.UiDependencies
import dev.earshot.ui.fakeUiDependencies
import dev.earshot.ui.theme.EarshotTheme

/** Light + dark phone previews in one annotation. */
@Preview(name = "Light", showBackground = true, heightDp = 860)
@Preview(name = "Dark", showBackground = true, heightDp = 860, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class LightDarkPreview

/** Theme + fake dependencies for `@Preview`s. Dynamic colour is off so previews are stable. */
@Composable
fun EarshotPreview(
    dependencies: UiDependencies = fakeUiDependencies(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalUiDependencies provides dependencies) {
        EarshotTheme(dynamicColor = false) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
