package dev.earshot.ui.common

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.earshot.core.Appearance
import dev.earshot.ui.LocalUiDependencies
import dev.earshot.ui.UiDependencies
import dev.earshot.ui.fakeUiDependencies
import dev.earshot.ui.theme.EarshotTheme
import dev.earshot.ui.theme.earshotBackdrop

/** Light + dark phone previews in one annotation. */
@Preview(name = "Light", showBackground = true, heightDp = 860)
@Preview(name = "Dark", showBackground = true, heightDp = 860, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class LightDarkPreview

/**
 * Theme + fake dependencies for `@Preview`s. Field unless a preview asks for another look, never
 * the wallpaper palette, so previews are stable.
 */
@Composable
fun EarshotPreview(
    dependencies: UiDependencies = fakeUiDependencies(),
    appearance: Appearance = Appearance.FIELD,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalUiDependencies provides dependencies) {
        EarshotTheme(appearance = appearance) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .earshotBackdrop(),
            ) { content() }
        }
    }
}
