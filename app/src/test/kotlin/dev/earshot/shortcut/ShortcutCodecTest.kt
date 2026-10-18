package dev.earshot.shortcut

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortcutCodecTest {
    private val all = listOf(
        ShortcutAction.CycleEq,
        ShortcutAction.VoiceMemo,
        ShortcutAction.LaunchApp("com.spotify.music", "Spotify"),
        ShortcutAction.LaunchApp(),
        ShortcutAction.OpenAssistant("com.google.android.googlequicksearchbox/com.google.android.voicesearch.handsfree.HandsFreeActivity", "Google"),
        ShortcutAction.OpenAssistant(),
    )

    @Test
    fun `every action round-trips`() {
        all.forEach { assertEquals(it, ShortcutCodec.decodeAction(ShortcutCodec.encodeAction(it))) }
    }

    @Test
    fun `stored format uses stable type names`() {
        assertEquals("""{"type":"cycle_eq"}""", ShortcutCodec.encodeAction(ShortcutAction.CycleEq))
        assertEquals("""{"type":"voice_memo"}""", ShortcutCodec.encodeAction(ShortcutAction.VoiceMemo))
        assertEquals(
            """{"type":"launch_app","packageName":"com.spotify.music","label":"Spotify"}""",
            ShortcutCodec.encodeAction(ShortcutAction.LaunchApp("com.spotify.music", "Spotify")),
        )
        assertEquals("""{"type":"open_assistant"}""", ShortcutCodec.encodeAction(ShortcutAction.OpenAssistant()))
    }

    @Test
    fun `unknown, corrupt or missing actions decode to null`() {
        assertNull(ShortcutCodec.decodeAction("""{"type":"switch_mode","index":2}"""))
        assertNull(ShortcutCodec.decodeAction("not json"))
        assertNull(ShortcutCodec.decodeAction(null))
    }

    @Test
    fun `fields added by a newer version are ignored`() {
        assertEquals(
            ShortcutAction.LaunchApp("a.b", "B"),
            ShortcutCodec.decodeAction("""{"type":"launch_app","packageName":"a.b","label":"B","extra":1}"""),
        )
    }

    @Test
    fun `overrides round-trip and an unknown action drops only its own entry`() {
        val list = listOf(
            HeadsetOverride("AA:BB:CC:DD:EE:01", "Buds", ShortcutAction.VoiceMemo),
            HeadsetOverride("AA:BB:CC:DD:EE:02", null, ShortcutAction.LaunchApp("x.y", "Y")),
        )
        assertEquals(list, ShortcutCodec.decodeOverrides(ShortcutCodec.encodeOverrides(list)))

        val mixed = """[{"address":"AA:BB:CC:DD:EE:01","action":{"type":"play_pause"}},""" +
            """{"address":"AA:BB:CC:DD:EE:02","name":"QC","action":{"type":"cycle_eq"}}]"""
        assertEquals(listOf(HeadsetOverride("AA:BB:CC:DD:EE:02", "QC", ShortcutAction.CycleEq)), ShortcutCodec.decodeOverrides(mixed))
        assertTrue(ShortcutCodec.decodeOverrides("garbage").isEmpty())
        assertTrue(ShortcutCodec.decodeOverrides(null).isEmpty())
    }

    @Test
    fun `the default is open my assistant, so choosing Earshot keeps the assistant`() {
        assertEquals(ShortcutAction.OpenAssistant(), ShortcutSettings().defaultAction)
    }

    @Test
    fun `per-headset override wins, matched case-insensitively, and can be removed`() {
        val s = ShortcutSettings(defaultAction = ShortcutAction.CycleEq)
            .withOverride("aa:bb:cc:dd:ee:01", "Buds", ShortcutAction.VoiceMemo)
        assertEquals(ShortcutAction.VoiceMemo, s.actionFor("AA:BB:CC:DD:EE:01"))
        assertEquals(ShortcutAction.CycleEq, s.actionFor("AA:BB:CC:DD:EE:02"))
        assertEquals(ShortcutAction.CycleEq, s.actionFor(null))

        val replaced = s.withOverride("AA:BB:CC:DD:EE:01", "Buds", ShortcutAction.CycleEq)
        assertEquals(1, replaced.overrides.size)
        assertEquals(ShortcutAction.CycleEq, replaced.actionFor("aa:bb:cc:dd:ee:01"))

        assertTrue(s.withOverride("AA:BB:CC:DD:EE:01", null, null).overrides.isEmpty())
    }
}
