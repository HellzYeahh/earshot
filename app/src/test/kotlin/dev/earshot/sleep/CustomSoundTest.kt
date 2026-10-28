package dev.earshot.sleep

import dev.earshot.core.CustomSound
import dev.earshot.core.SleepLayer
import dev.earshot.sleep.SleepSoundPlayer.Companion.toSoundLayers
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The user's own sounds: ids, what reaches the mixer, and the saved list. */
class CustomSoundTest {
    @Test fun `own sounds reach the mixer, unknown ids don't`() {
        val layers = listOf(
            SleepLayer("rain", 0.5f),
            SleepLayer("${CustomSound.ID_PREFIX}1234-abcd", 0.8f),
            SleepLayer("thunder_from_a_newer_build", 0.3f),
        ).toSoundLayers()
        assertEquals(listOf("rain", "${CustomSound.ID_PREFIX}1234-abcd"), layers.map { it.id })
        assertEquals(0.8f, layers[1].gain)
    }

    @Test fun `custom ids are told apart from built-in ones`() {
        assertTrue(CustomSound.isCustom("${CustomSound.ID_PREFIX}x"))
        assertFalse(CustomSound.isCustom("rain"))
    }

    @Test fun `the saved list round-trips`() {
        val sounds = listOf(CustomSound("${CustomSound.ID_PREFIX}a", "Grandma's clock", 42), CustomSound("${CustomSound.ID_PREFIX}b", "Cat purring", 90))
        val json = SleepStore.json.encodeToString(ListSerializer(CustomSound.serializer()), sounds)
        assertEquals(sounds, SleepStore.json.decodeFromString(ListSerializer(CustomSound.serializer()), json))
    }
}
