package dev.earshot.extras.automation

import dev.earshot.core.AppPreferences
import dev.earshot.core.AutomationRule
import dev.earshot.core.ModeRef
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * The JSON in the "earshot_automation" store, pure so the saved format is unit-tested
 * (AutomationJsonCompatTest holds literal 0.75 strings).
 *
 * Lists are read element by element: a rule this build can't read (corrupt, or written by a newer
 * build after a downgrade) no longer empties the whole list, which the next upsert then wrote back
 * for good. The unreadable elements are also kept, unchanged, at the end when the list is written,
 * so the newer build finds them again after an upgrade.
 */
internal object AutomationJson {
    /** 0.75's settings: unknown keys are ignored (that is what lets a 0.75 build read 0.8 rules). */
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** [corrupt]: the stored text was not a JSON list at all; it is then dropped, as before. */
    class Rules(val rules: List<AutomationRule>, val unreadable: List<JsonElement>, val corrupt: Boolean = false)

    fun decodeRules(raw: String?): Rules {
        if (raw.isNullOrBlank()) return Rules(emptyList(), emptyList())
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return Rules(emptyList(), emptyList(), corrupt = true)
        val rules = ArrayList<AutomationRule>(array.size)
        val unreadable = ArrayList<JsonElement>()
        for (element in array) {
            runCatching { json.decodeFromJsonElement(AutomationRule.serializer(), element) }
                .onSuccess { rules += it }
                .onFailure { unreadable += element }
        }
        return Rules(rules, unreadable)
    }

    /** [rules] plus the [unreadable] elements read with them, which this build must not lose. */
    fun encodeRules(rules: List<AutomationRule>, unreadable: List<JsonElement> = emptyList()): String {
        val elements = rules.map { json.encodeToJsonElement(AutomationRule.serializer(), it) } + unreadable
        return json.encodeToString(JsonArray.serializer(), JsonArray(elements))
    }

    fun encodeTileCycle(refs: List<ModeRef>): String = json.encodeToString(ListSerializer(ModeRef.serializer()), refs)

    /** Element by element too: one unreadable entry drops only itself. */
    fun decodeTileCycle(raw: String?): List<ModeRef> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element -> runCatching { json.decodeFromJsonElement(ModeRef.serializer(), element) }.getOrNull() }
    }

    /** 0.75's `tile_cycle_modes`: comma-separated slot numbers. */
    fun decodeLegacyTileCycle(raw: String?): List<Int>? = raw?.split(',')?.mapNotNull { it.trim().toIntOrNull() }

    fun encodeLegacyTileCycle(slots: List<Int>): String = slots.joinToString(",")

    /**
     * 0.8 writes the tile refs' slots to `tile_cycle_modes` next to the refs. Slots that no longer
     * match them were written by an older build after a downgrade (it only knows the slots): that
     * later choice wins, so the refs count as not migrated and the migration reads the slots again.
     */
    fun tileRefsCurrent(legacySlots: List<Int>?, refs: List<ModeRef>): Boolean =
        legacySlots == null || legacySlots == AppPreferences(tileCycle = refs, modeRefsMigrated = true).legacyTileSlots
}
