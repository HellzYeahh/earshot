package dev.earshot.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The `sleep` store's JSON lists, handled element by element so nothing this build can't read is lost. */
class OwnSoundListTest {

    private val phone = """[{"id":"custom:p1","name":"Mine"},{"future":"element"}]"""

    @Test fun `ids skip what can't be read`() {
        assertEquals(listOf("a1", "a2"), OwnSoundList.ids("""[{"id":"a1","hour":7},{"id":7},{"x":1},"text",{"id":"a2"}]"""))
        assertEquals(emptyList<String>(), OwnSoundList.ids(null))
        assertEquals(emptyList<String>(), OwnSoundList.ids("not json"))
        assertEquals(emptyList<String>(), OwnSoundList.ids("""{"id":"an object, not a list"}"""))
    }

    @Test fun `merge adds the backup's sounds after the phone's, never removes`() {
        val backup = """[{"id":"custom:b1","name":"Rain"},{"id":"custom:p1","name":"Backup's name"},{"id":"custom:gone","name":"No file"},{"id":"rain","name":"not own"}]"""
        assertEquals(
            """[{"id":"custom:p1","name":"Mine"},{"future":"element"},{"id":"custom:b1","name":"Rain"}]""",
            OwnSoundList.merge(backup, phone, files = setOf("p1", "b1")),
        )
    }

    @Test fun `merge with nothing to add keeps the phone's list exactly`() {
        assertEquals(phone, OwnSoundList.merge(null, phone, files = setOf("p1")))
        assertEquals(phone, OwnSoundList.merge("garbage", phone, files = setOf("p1")))
        assertNull(OwnSoundList.merge(null, null, files = emptySet()))
    }

    @Test fun `merge onto a phone without a list`() {
        assertEquals("""[{"id":"custom:b1"}]""", OwnSoundList.merge("""[{"id":"custom:b1"},{"id":"custom:b1"}]""", null, files = setOf("b1")))
    }

    @Test fun `keepOnly lists only the sounds that travel`() {
        val list = """[{"id":"custom:a","name":"A"},{"id":"custom:b","name":"B"},{"other":true}]"""
        assertEquals("""[{"id":"custom:b","name":"B"}]""", OwnSoundList.keepOnly(list, setOf("b")))
        assertNull(OwnSoundList.keepOnly(list, emptySet()))
        assertNull(OwnSoundList.keepOnly(null, setOf("b")))
    }

    @Test fun `names come from the list by file name`() {
        assertEquals(mapOf("p1" to "Mine"), OwnSoundList.namesByFile(phone))
    }
}
