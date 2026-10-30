package dev.earshot.backup

import dev.earshot.core.CustomSound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The JSON lists inside the `sleep` store, handled element by element like `SleepStore` reads
 * them, so an element this build can't decode (written by a newer one) is carried, not lost:
 * `custom_sounds` (objects with `id` = `custom:<file name>`, `name`, `loopSeconds`) and `alarms`
 * (objects with an `id`).
 */
internal object OwnSoundList {
    private val json = Json { ignoreUnknownKeys = true }

    /** The `id` of each readable element of the JSON list [raw]. */
    fun ids(raw: String?): List<String> = parse(raw).orEmpty().mapNotNull(::idOf)

    /** File name (the id without `custom:`) → the name the user gave the sound, from the `custom_sounds` list [raw]. */
    fun namesByFile(raw: String?): Map<String, String> = parse(raw).orEmpty().mapNotNull { element ->
        val id = idOf(element) ?: return@mapNotNull null
        val name = ((element as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        id.removePrefix(CustomSound.ID_PREFIX) to name
    }.toMap()

    /** [raw] with only the sounds whose file name is in [files]; null when none is left (or [raw] holds no list). */
    fun keepOnly(raw: String?, files: Set<String>): String? {
        val list = parse(raw) ?: return null
        val kept = list.filter { element -> idOf(element)?.removePrefix(CustomSound.ID_PREFIX)?.let { it in files } == true }
        return if (kept.isEmpty()) null else JsonArray(kept).toString()
    }

    /**
     * Restoring own sounds adds, it never removes: this phone's list stays as it is (every
     * element, readable or not), and each sound of the [backup] list is added after it unless
     * the phone already lists that id (the phone's file is kept on a clash) or no file for it
     * will be there ([files], the file names in `sleep_custom` once the restore is done).
     * Returns [phone] unchanged when nothing is added.
     */
    fun merge(backup: String?, phone: String?, files: Set<String>): String? {
        val phoneList = parse(phone)
        val phoneIds = phoneList.orEmpty().mapNotNull(::idOf).toSet()
        val added = LinkedHashMap<String, JsonElement>()
        for (element in parse(backup).orEmpty()) {
            val id = idOf(element) ?: continue
            if (id in phoneIds || id in added) continue
            if (!CustomSound.isCustom(id) || id.removePrefix(CustomSound.ID_PREFIX) !in files) continue
            added[id] = element
        }
        if (added.isEmpty()) return phone
        return JsonArray(phoneList.orEmpty() + added.values).toString()
    }

    private fun parse(raw: String?): List<JsonElement>? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
    }

    private fun idOf(element: JsonElement): String? =
        ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
