package dev.earshot.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.earshot.ui.home.HomeScreen
import dev.earshot.ui.modes.ModesScreen
import dev.earshot.ui.more.AboutScreen
import dev.earshot.ui.more.AutomationEditorScreen
import dev.earshot.ui.more.AutomationsScreen
import dev.earshot.ui.more.DevicesScreen
import dev.earshot.ui.more.MoreScreen
import dev.earshot.ui.more.PreferencesScreen
import dev.earshot.ui.sound.SoundScreen
import dev.earshot.ui.tools.CallAudioScreen
import dev.earshot.ui.tools.CodecScreen
import dev.earshot.ui.tools.ConnectionScreen
import dev.earshot.ui.tools.LabScreen
import dev.earshot.ui.tools.MicCheckScreen
import dev.earshot.ui.tools.ShortcutsRemapperScreen
import dev.earshot.ui.tools.ToolsScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * The navigation graph with a subtle cross-fade between destinations; the bottom bar stays put.
 *
 * Insets: the root Scaffold's bottom padding (the NavigationBar) is applied here once and
 * consumed, so sub-screens with their own top bar neither hide under the bar nor double it.
 * Tab screens receive only the top padding (status bar).
 */
@Composable
fun EarshotNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
    tabRequests: StateFlow<Tab?>,
    onTabRequestHandled: () -> Unit,
) {
    val bottom = contentPadding.calculateBottomPadding()
    val tabPadding = PaddingValues(top = contentPadding.calculateTopPadding())

    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = bottom)
            .consumeWindowInsets(PaddingValues(bottom = bottom)),
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(120)) },
        ) {
            composable(Routes.HOME) {
                HomeScreen(contentPadding = tabPadding, onEditModes = { navController.navigate(Routes.MODES) })
            }
            composable(Routes.MODES) { ModesScreen(onBack = navController::popBackStack) }

            composable(Routes.SOUND) { SoundScreen(contentPadding = tabPadding) }

            composable(Routes.TOOLS) {
                ToolsScreen(
                    contentPadding = tabPadding,
                    onOpenConnection = { navController.navigate(Routes.CONNECTION) },
                    onOpenCodec = { navController.navigate(Routes.CODEC) },
                    onOpenCallAudio = { navController.navigate(Routes.CALL_AUDIO) },
                    onOpenMicCheck = { navController.navigate(Routes.MIC_CHECK) },
                    onOpenLab = { navController.navigate(Routes.LAB) },
                    onOpenShortcuts = { navController.navigate(Routes.SHORTCUTS_REMAPPER) },
                )
            }
            composable(Routes.CONNECTION) {
                ConnectionScreen(
                    onBack = navController::popBackStack,
                    onOpenCodec = { navController.navigate(Routes.CODEC) },
                    onOpenCallAudio = { navController.navigate(Routes.CALL_AUDIO) },
                )
            }
            composable(Routes.CODEC) {
                CodecScreen(onBack = navController::popBackStack, onOpenCallAudio = { navController.navigate(Routes.CALL_AUDIO) })
            }
            composable(Routes.CALL_AUDIO) { CallAudioScreen(onBack = navController::popBackStack) }
            composable(Routes.MIC_CHECK) { MicCheckScreen(onBack = navController::popBackStack) }
            composable(Routes.LAB) { LabScreen(onBack = navController::popBackStack) }
            composable(Routes.SHORTCUTS_REMAPPER) { ShortcutsRemapperScreen(onBack = navController::popBackStack) }

            composable(Routes.MORE) {
                MoreScreen(
                    contentPadding = tabPadding,
                    onOpenDevices = { navController.navigate(Routes.DEVICES) },
                    onOpenAutomations = { navController.navigate(Routes.AUTOMATIONS) },
                    onOpenPreferences = { navController.navigate(Routes.PREFERENCES) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                )
            }
            composable(Routes.DEVICES) { DevicesScreen(onBack = navController::popBackStack) }
            composable(Routes.AUTOMATIONS) {
                AutomationsScreen(
                    onBack = navController::popBackStack,
                    onEdit = { id -> navController.navigate(Routes.automationEdit(id)) },
                )
            }
            composable(
                Routes.AUTOMATION_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                AutomationEditorScreen(ruleId = entry.arguments?.getString("id"), onBack = navController::popBackStack)
            }
            composable(Routes.PREFERENCES) { PreferencesScreen(onBack = navController::popBackStack) }
            composable(Routes.ABOUT) { AboutScreen(onBack = navController::popBackStack) }
        }
    }

    // Runs after NavHost has set its graph, so a cold-start deep link can never navigate too early.
    LaunchedEffect(navController, tabRequests) {
        tabRequests.filterNotNull().collect { tab ->
            navController.navigateToTab(tab)
            onTabRequestHandled()
        }
    }
}

/** Bottom-bar style navigation: one back-stack per tab, state saved and restored. */
fun NavHostController.navigateToTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
