package dev.earshot.extras.automation

import dev.earshot.core.AppPreferences
import dev.earshot.core.AutomationAction
import dev.earshot.core.AutomationRule
import dev.earshot.core.ModeMeaning
import dev.earshot.core.ModeRef
import dev.earshot.core.SpatialAudio
import dev.earshot.core.Trigger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The saved automation format across versions. The strings below are what 0.75 wrote to
 * `rules_json` (fully qualified class names as the "type", no ModeRef); never edit them to make a
 * test pass, they are what users have on their phones.
 */
class AutomationJsonCompatTest {

    private val rules075 = """
        [{"id":"r1","name":"Commute","enabled":true,"trigger":{"type":"dev.earshot.core.Trigger.Schedule","hour":8,"minute":15,"days":[1,2,3,4,5]},"actions":[{"type":"dev.earshot.core.AutomationAction.SetMode","modeIndex":0},{"type":"dev.earshot.core.AutomationAction.SetNoise","cancellingLevel":6,"ancEnabled":true,"windBlock":false,"spatialAudioWire":2}]},
         {"id":"r2","name":"Hello","enabled":false,"trigger":{"type":"dev.earshot.core.Trigger.OnConnect"},"actions":[{"type":"dev.earshot.core.AutomationAction.SetEqPreset","presetId":null}]},
         {"id":"r3","name":"Office","enabled":true,"trigger":{"type":"dev.earshot.core.Trigger.WifiNetwork","ssid":"Office"},"actions":[{"type":"dev.earshot.core.AutomationAction.SetMode","modeIndex":5}]},
         {"id":"r4","name":"Music","enabled":true,"trigger":{"type":"dev.earshot.core.Trigger.ForegroundApp","packageName":"com.example.music","appLabel":"Music"},"actions":[{"type":"dev.earshot.core.AutomationAction.SetEqPreset","presetId":"bass"}]}]
    """.trimIndent()

    @Test fun `a literal 0_75 rules_json decodes`() {
        val decoded = AutomationJson.decodeRules(rules075)
        assertTrue(decoded.unreadable.isEmpty())
        assertEquals(
            listOf(
                AutomationRule("r1", "Commute", true, Trigger.Schedule(8, 15, setOf(1, 2, 3, 4, 5)), listOf(AutomationAction.SetMode(0), AutomationAction.SetNoise(6, true, false, 2))),
                AutomationRule("r2", "Hello", false, Trigger.OnConnect, listOf(AutomationAction.SetEqPreset(null))),
                AutomationRule("r3", "Office", true, Trigger.WifiNetwork("Office"), listOf(AutomationAction.SetMode(5))),
                AutomationRule("r4", "Music", true, Trigger.ForegroundApp("com.example.music", "Music"), listOf(AutomationAction.SetEqPreset("bass"))),
            ),
            decoded.rules,
        )
        // Unbound 0.75 rules: no ModeRef until the migration binds them.
        assertEquals(null, (decoded.rules[0].actions[0] as AutomationAction.SetMode).mode)
        // 0.75 stored the Bose wire code for spatial audio; the frozen table reads it the same way.
        assertEquals(SpatialAudio.HEAD, SpatialAudio.fromStoredId((decoded.rules[0].actions[1] as AutomationAction.SetNoise).spatialAudioWire))
    }

    @Test fun `re-writing a 0_75 rule keeps every type name, only adding an empty mode`() {
        val original = Json.parseToJsonElement(rules075).jsonArray
        val rewritten = Json.parseToJsonElement(AutomationJson.encodeRules(AutomationJson.decodeRules(rules075).rules)).jsonArray
        assertEquals(original.size, rewritten.size)
        for (i in original.indices) {
            assertEquals(original[i], withoutNullModes(rewritten[i].jsonObject), "rule $i")
        }
    }

    @Test fun `new JSON still carries modeIndex and the ref`() {
        val ref = ModeRef(ModeMeaning.AWARE, "Aware", "AA:BB:CC:DD:EE:FF", 1)
        val rule = AutomationRule("n1", "New", true, Trigger.OnConnect, listOf(AutomationAction.SetMode(1, ref)))
        val action = Json.parseToJsonElement(AutomationJson.encodeRules(listOf(rule))).jsonArray[0].jsonObject["actions"]!!.jsonArray[0].jsonObject
        assertEquals("dev.earshot.core.AutomationAction.SetMode", action["type"]!!.jsonPrimitive.content)
        assertEquals(1, action["modeIndex"]!!.jsonPrimitive.int)
        val mode = action["mode"]!!.jsonObject
        assertEquals("aware", mode["meaning"]!!.jsonPrimitive.content)
        assertEquals("AA:BB:CC:DD:EE:FF", mode["address"]!!.jsonPrimitive.content)
        assertEquals(rule, AutomationJson.decodeRules(AutomationJson.encodeRules(listOf(rule))).rules.single())
    }

    @Test fun `a downgraded 0_75 build reads the new JSON`() {
        val rules = listOf(
            AutomationRule("n1", "Bound", true, Trigger.Schedule(7, 0, setOf(6, 7)), listOf(
                AutomationAction.SetMode(2, ModeRef(ModeMeaning.CUSTOM, "Immersion", "AA:BB:CC:DD:EE:FF", 2, legacySlot = 2)),
                AutomationAction.SetNoise(4, true, true, SpatialAudio.ROOM.storedId),
            )),
            AutomationRule("n2", "Meaning only", true, Trigger.OnConnect, listOf(AutomationAction.SetMode(1, ModeRef(ModeMeaning.AWARE)))),
            AutomationRule("n3", "Legacy", true, Trigger.WifiNetwork("Home"), listOf(AutomationAction.SetMode(4))),
        )
        // 0.75 decoded the whole list at once with these settings: one failure would lose every rule.
        val old = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            .decodeFromString(ListSerializer(OldRule.serializer()), AutomationJson.encodeRules(rules))
        assertEquals(
            listOf(
                OldRule("n1", "Bound", true, OldTrigger.Schedule(7, 0, setOf(6, 7)), listOf(OldAction.SetMode(2), OldAction.SetNoise(4, true, true, 1))),
                OldRule("n2", "Meaning only", true, OldTrigger.OnConnect, listOf(OldAction.SetMode(1))),
                OldRule("n3", "Legacy", true, OldTrigger.WifiNetwork("Home"), listOf(OldAction.SetMode(4))),
            ),
            old,
        )
    }

    @Test fun `a downgraded build keeps its tile`() {
        val prefs = AppPreferences(tileCycleModes = listOf(0, 1)).withTileCycle(
            listOf(ModeRef(ModeMeaning.QUIET, "Quiet", "AA", 0), ModeRef(ModeMeaning.CUSTOM, legacySlot = 5), ModeRef(ModeMeaning.OFF)),
        )
        assertTrue(prefs.modeRefsMigrated)
        // What tile_cycle_modes gets: the refs' slots (a meaning-only ref has none to give).
        assertEquals(listOf(0, 5), prefs.legacyTileSlots)
        assertEquals(listOf(0, 5), prefs.tileCycleModes)
        assertEquals("0,5", AutomationJson.encodeLegacyTileCycle(prefs.legacyTileSlots))
        // Before the migration the 0.75 list is written back as it was.
        assertEquals(listOf(3, 1), AppPreferences(tileCycleModes = listOf(3, 1)).legacyTileSlots)
        assertEquals(listOf(0, 2), AutomationJson.decodeLegacyTileCycle("0, 2,x"))
    }

    @Test fun `one corrupt element drops only itself and is written back untouched`() {
        val raw = """
            [{"id":"a","name":"A","enabled":true,"trigger":{"type":"dev.earshot.core.Trigger.OnConnect"},"actions":[{"type":"dev.earshot.core.AutomationAction.SetMode","modeIndex":1}]},
             {"id":"future","name":"From 0.9","enabled":true,"trigger":{"type":"bluetooth_device","address":"AA"},"actions":[]},
             {"id":3},
             {"id":"b","name":"B","enabled":true,"trigger":{"type":"dev.earshot.core.Trigger.OnConnect"},"actions":[{"type":"dev.earshot.core.AutomationAction.SetEqPreset","presetId":"bass"}]}]
        """.trimIndent()
        val decoded = AutomationJson.decodeRules(raw)
        assertFalse(decoded.corrupt)
        assertEquals(listOf("a", "b"), decoded.rules.map { it.id })
        assertEquals(2, decoded.unreadable.size)

        // An upsert writes the readable rules plus the unreadable ones, so an upgrade finds them again.
        val written = AutomationJson.encodeRules(decoded.rules.filterNot { it.id == "b" }, decoded.unreadable)
        val again = AutomationJson.decodeRules(written)
        assertEquals(listOf("a"), again.rules.map { it.id })
        assertEquals(decoded.unreadable, again.unreadable)
        assertEquals("future", (Json.parseToJsonElement(written) as JsonArray)[1].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun `text that is not a list is dropped as before`() {
        val decoded = AutomationJson.decodeRules("{not json")
        assertTrue(decoded.corrupt)
        assertTrue(decoded.rules.isEmpty())
        assertTrue(AutomationJson.decodeRules(null).rules.isEmpty())
        assertFalse(AutomationJson.decodeRules("").corrupt)
    }

    @Test fun `the tile cycle refs decode element by element`() {
        val refs = listOf(ModeRef(ModeMeaning.QUIET, "Quiet", "AA", 0), ModeRef(ModeMeaning.CUSTOM, "Gym", "AA", 5, legacySlot = 5))
        assertEquals(refs, AutomationJson.decodeTileCycle(AutomationJson.encodeTileCycle(refs)))
        val withJunk = """[{"meaning":"quiet"},{"name":"no meaning"},42,{"meaning":"adaptive2","name":"Zen"}]"""
        assertEquals(
            listOf(ModeRef(ModeMeaning.QUIET), ModeRef(ModeMeaning.CUSTOM, "Zen", unknownMeaning = "adaptive2")),
            AutomationJson.decodeTileCycle(withJunk),
        )
        assertTrue(AutomationJson.decodeTileCycle("oops").isEmpty())
    }

    @Test fun `a tile list an older build changed after a downgrade counts as not migrated`() {
        val refs = listOf(ModeRef(ModeMeaning.QUIET, "Quiet", "AA", 0), ModeRef(ModeMeaning.CUSTOM, legacySlot = 5))
        // What 0.8 wrote next to the refs.
        assertTrue(AutomationJson.tileRefsCurrent(AutomationJson.decodeLegacyTileCycle("0,5"), refs))
        assertTrue(AutomationJson.tileRefsCurrent(AutomationJson.decodeLegacyTileCycle(""), emptyList()))
        assertTrue(AutomationJson.tileRefsCurrent(null, refs))
        // 0.75 picked Aware and Immersion meanwhile.
        assertFalse(AutomationJson.tileRefsCurrent(AutomationJson.decodeLegacyTileCycle("1,2"), refs))
    }

    @Test fun `the stored spatial audio codes are frozen`() {
        assertEquals(listOf(0, 1, 2), listOf(SpatialAudio.OFF, SpatialAudio.ROOM, SpatialAudio.HEAD).map { it.storedId })
        assertEquals(listOf(SpatialAudio.OFF, SpatialAudio.ROOM, SpatialAudio.HEAD, SpatialAudio.OFF), listOf(0, 1, 2, 9).map(SpatialAudio::fromStoredId))
    }

    private fun withoutNullModes(rule: JsonObject): JsonObject {
        val actions = rule["actions"]!!.jsonArray.map { a ->
            val o = a.jsonObject
            if (o["mode"]?.toString() == "null") JsonObject(o - "mode") else o
        }
        return JsonObject(rule + ("actions" to JsonArray(actions)))
    }

    // ---- 0.75's classes, as it compiled them: no @SerialName, so the type names were these FQNs ----

    @Serializable
    sealed interface OldTrigger {
        @Serializable @SerialName("dev.earshot.core.Trigger.OnConnect") data object OnConnect : OldTrigger
        @Serializable @SerialName("dev.earshot.core.Trigger.WifiNetwork") data class WifiNetwork(val ssid: String) : OldTrigger
        @Serializable @SerialName("dev.earshot.core.Trigger.ForegroundApp") data class ForegroundApp(val packageName: String, val appLabel: String) : OldTrigger
        @Serializable @SerialName("dev.earshot.core.Trigger.Schedule") data class Schedule(val hour: Int, val minute: Int, val days: Set<Int>) : OldTrigger
    }

    @Serializable
    sealed interface OldAction {
        @Serializable @SerialName("dev.earshot.core.AutomationAction.SetMode") data class SetMode(val modeIndex: Int) : OldAction
        @Serializable @SerialName("dev.earshot.core.AutomationAction.SetEqPreset") data class SetEqPreset(val presetId: String?) : OldAction
        @Serializable @SerialName("dev.earshot.core.AutomationAction.SetNoise")
        data class SetNoise(val cancellingLevel: Int, val ancEnabled: Boolean, val windBlock: Boolean, val spatialAudioWire: Int) : OldAction
    }

    @Serializable
    data class OldRule(val id: String, val name: String, val enabled: Boolean = true, val trigger: OldTrigger, val actions: List<OldAction>)
}
