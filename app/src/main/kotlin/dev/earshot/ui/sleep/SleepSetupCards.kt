package dev.earshot.ui.sleep

import dev.earshot.core.SleepPermissions

/**
 * The cards of the idle Sleep tab, top to bottom (docs/sleep-mode.md §5).
 * Each is one keyed item of the tab's LazyColumn; the names are the item keys, so keep them
 * stable (a key must survive in a Bundle, and a renamed key drops that card's saved state).
 */
enum class SleepSetupCard { HEADSET, ALARMS, PERMISSIONS, TONIGHT, SOUNDS, NIGHT_OPTIONS }

/**
 * Whether [SleepPermissionBanners] shows anything. Do Not Disturb access is not one of them: it is
 * asked for in the night options, only when that extra is wanted.
 */
val SleepPermissions.hasBanner: Boolean
    get() = !(exactAlarms && fullScreenAlarm && notifications)

/**
 * The cards to show for [permissions]. The banners card is left out when it has nothing to say,
 * not emitted empty: a LazyColumn puts its 12 dp gap after every item but the last, empty ones
 * included, while the old Column only spaced the children that were really there.
 */
fun sleepSetupCards(permissions: SleepPermissions): List<SleepSetupCard> =
    SleepSetupCard.entries.filter { it != SleepSetupCard.PERMISSIONS || permissions.hasBanner }
