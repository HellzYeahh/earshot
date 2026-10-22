package dev.earshot.extras

import dev.earshot.core.BatteryLevel
import dev.earshot.core.DeviceInfo
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.bragi.BragiModel
import dev.earshot.protocol.soundcore.SoundcoreModel

/**
 * Plain-Kotlin naming and battery helpers shared by Home, the widget, the tile, the low-battery
 * alert and (once the integrator wires it) the persistent notification, so every surface calls
 * the product the same thing. No Android or Compose types: unit-tested in `HeadphoneLabelsTest`.
 */
object HeadphoneLabels {

    /**
     * Earbuds on LE Audio appear as a second bonded device named "LE-<name>" (0.3.1 connection
     * report). The prefix is Android's, not the user's, so it is dropped wherever a name is shown.
     */
    private val LE_PREFIX = Regex("^LE[-_ ]+", RegexOption.IGNORE_CASE)

    fun stripLePrefix(name: String): String = name.trim().replace(LE_PREFIX, "").trim()

    /** The user's name for the product: `[1.2]` custom name, else the Bluetooth name without "LE-". */
    fun displayName(state: HeadphoneState): String? =
        state.info?.customName?.trim()?.takeIf { it.isNotEmpty() }
            ?: state.connection.deviceName?.let(::stripLePrefix)?.takeIf { it.isNotEmpty() }

    /** "QuietComfort Ultra Earbuds · 2nd Gen" (family + generation), else the model name, else null. */
    fun modelLine(info: DeviceInfo?): String? {
        if (info == null) return null
        val family = info.family?.trim()?.takeIf { it.isNotEmpty() }
        val generation = info.generation?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            family != null && generation != null -> "$family · $generation"
            family != null -> family
            else -> info.modelName?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * The form factor to word things by. While connected the capabilities know; while not, the
     * capabilities are reset to the headphone defaults, so fall back on the Bluetooth name
     * ("Bose QC Ultra Earbuds", "Bose Ultra Open Earbuds").
     */
    fun formFactor(state: HeadphoneState): FormFactor =
        if (state.isConnected) state.capabilities.formFactor else formFactorFromName(state.connection.deviceName)

    fun formFactorFromName(name: String?): FormFactor {
        val n = name?.lowercase() ?: return FormFactor.HEADPHONES
        // Models a brand table knows, whose names don't say "buds" ("Method 360 ANC", "Liberty 4 NC").
        SoundcoreModel.fromName(name)?.let { return if (it.earbuds) FormFactor.EARBUDS else FormFactor.HEADPHONES }
        BragiModel.fromBluetoothName(name)?.let { return if (it.features.earbuds) FormFactor.EARBUDS else FormFactor.HEADPHONES }
        // "MOMENTUM TW 4", "ACCENTUM TW", "MOMENTUM SPORT": Sennheiser's earbud names don't say it either.
        dev.earshot.protocol.sennheiser.SennheiserModel.fromName(name)?.let { return if (it.earbuds) FormFactor.EARBUDS else FormFactor.HEADPHONES }
        val buds = "bud" in n || EARBUD_WORDS.containsMatchIn(n)
        return when {
            "open" in n && buds -> FormFactor.OPEN_EARBUDS
            buds -> FormFactor.EARBUDS
            else -> FormFactor.HEADPHONES
        }
    }

    /** Earbud names that don't say "buds": AirPods, "Nothing Ear (2)", "… Earphones", "TWS". AirPods Max are headphones. */
    private val EARBUD_WORDS = Regex("""pods(?!\s*max)|\bear\b|earphone|\btws\b""")

    fun isEarbuds(formFactor: FormFactor): Boolean = formFactor != FormFactor.HEADPHONES

    /** True when the state carries a per-bud reading worth showing as L / R (and case). */
    fun hasBudBatteries(state: HeadphoneState): Boolean {
        val b = state.battery ?: return false
        return b.left?.percent != null || b.right?.percent != null
    }

    /**
     * The figure the low-battery alert watches: for earbuds the lower bud (the one that dies
     * first; the combined record would hide it), otherwise the headline percent.
     */
    fun alertPercent(state: HeadphoneState): Int? {
        if (hasBudBatteries(state)) {
            val buds = listOfNotNull(state.battery?.left?.percent, state.battery?.right?.percent)
            buds.minOrNull()?.let { return it }
        }
        return state.batteryPercent
    }

    /** Which bud [alertPercent] picked: "left", "right", or null for headphones / a tie / unknown. */
    fun lowerBud(state: HeadphoneState): Bud? {
        if (!hasBudBatteries(state)) return null
        val l = state.battery?.left?.percent
        val r = state.battery?.right?.percent
        return when {
            l != null && r != null && l < r -> Bud.LEFT
            l != null && r != null && r < l -> Bud.RIGHT
            l != null && r == null -> Bud.LEFT
            r != null && l == null -> Bud.RIGHT
            else -> null
        }
    }

    enum class Bud { LEFT, RIGHT }

    /**
     * Listening time left in minutes: the headphones' own estimate, or for earbuds the bud that
     * runs out first. Null when the firmware gives no estimate.
     */
    fun minutesLeft(state: HeadphoneState): Int? {
        val b = state.battery ?: return null
        val buds = listOfNotNull(b.left?.minutesLeft, b.right?.minutesLeft)
        return buds.minOrNull() ?: b.overall?.minutesLeft
    }

    /** Compact battery text for the widget and tile: "L 80% · R 74%" for earbuds, "72%" otherwise. */
    fun batteryShort(state: HeadphoneState): String? {
        if (hasBudBatteries(state)) {
            val b = state.battery!!
            return listOfNotNull(
                b.left?.percent?.let { "L $it%" },
                b.right?.percent?.let { "R $it%" },
            ).joinToString(" · ")
        }
        return state.batteryPercent?.let { "$it%" }
    }

    /** "Case 60%" when the case reported a level (last known: earbuds report it only when docked). */
    fun caseShort(level: BatteryLevel?): String? = level?.percent?.let { "Case $it%" }

    /** A mode's own name, or null when the slot has none: never "Mode N" on a surface. */
    fun modeName(state: HeadphoneState, index: Int): String? =
        state.modes.firstOrNull { it.index == index }?.name?.trim()?.takeIf { it.isNotEmpty() }
}
