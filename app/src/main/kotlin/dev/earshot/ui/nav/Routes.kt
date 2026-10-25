package dev.earshot.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.ui.graphics.vector.ImageVector
import dev.earshot.R

/**
 * A deep link from the notification, tile or widget: the [tab] to show and, optionally, a
 * sub-screen [route] in it (the notification's "Find" opens Tools → Find).
 */
data class NavRequest(val tab: Tab, val route: String? = null)

/** Navigation graph. Sub-routes are prefixed with their tab so the bottom bar can stay highlighted. */
object Routes {
    const val HOME = "home"
    const val SOUND = "sound"
    const val TOOLS = "tools"
    const val MORE = "more"

    const val MODES = "home/modes"

    const val CONNECTION = "tools/connection"
    const val CODEC = "tools/codec"
    const val CALL_AUDIO = "tools/call"
    const val MIC_CHECK = "tools/mic"
    const val FIND = "tools/find"
    const val TIMELINE = "tools/timeline"
    const val LAB = "tools/lab"
    const val SHORTCUTS_REMAPPER = "tools/shortcuts-remapper"

    const val DEVICES = "more/devices"
    const val AUTOMATIONS = "more/automations"
    const val AUTOMATION_EDIT = "more/automations/edit?id={id}"
    const val PREFERENCES = "more/preferences"
    const val ABOUT = "more/about"

    fun automationEdit(id: String?): String = if (id == null) "more/automations/edit" else "more/automations/edit?id=$id"
}

enum class Tab(
    val route: String,
    @param:StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME(Routes.HOME, R.string.tab_home, Icons.Outlined.Headphones, Icons.Filled.Headphones),
    SOUND(Routes.SOUND, R.string.tab_sound, Icons.Outlined.GraphicEq, Icons.Filled.GraphicEq),
    TOOLS(Routes.TOOLS, R.string.tab_tools, Icons.Outlined.Handyman, Icons.Filled.Handyman),
    MORE(Routes.MORE, R.string.tab_more, Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
    ;

    companion object {
        /** Maps the `dev.earshot.EXTRA_OPEN_TAB` intent extra (home/sound/tools/more) to a tab. */
        fun fromExtra(value: String?): Tab? = entries.firstOrNull { it.route.equals(value, ignoreCase = true) }
        fun forRoute(route: String?): Tab? = entries.firstOrNull { route == it.route || route?.startsWith(it.route + "/") == true }
    }
}
