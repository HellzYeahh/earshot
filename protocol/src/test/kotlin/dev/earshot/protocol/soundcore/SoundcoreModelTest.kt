package dev.earshot.protocol.soundcore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The model table: UUID suffix derivation, names, serial prefixes and the checks that keep two models apart. */
class SoundcoreModelTest {
    private fun uuid(suffix: String) = "${SoundcoreModel.SERVICE_UUID_PREFIX}$suffix"

    @Test fun `the UUID suffix follows the model code with bit 16 set`() {
        // Captured on real hardware per the notes: …b3947, …b3958, …d1301, …d1402; derivation for the rest.
        assertEquals("b3947", SoundcoreModel.LIBERTY_4_NC.uuidSuffix)
        assertEquals("b3958", SoundcoreModel.A30I.uuidSuffix)
        assertEquals("d1301", SoundcoreModel.SLEEP_A30.uuidSuffix)
        assertEquals("d1402", SoundcoreModel.SPACE_2.uuidSuffix)
        assertEquals("d1202", SoundcoreModel.P31I.uuidSuffix)
        assertEquals("b3035", SoundcoreModel.SPACE_ONE.uuidSuffix)
        assertEquals("b6611", SoundcoreModel.SLEEP_A20.uuidSuffix)
    }

    @Test fun `every A-code and D-code model is found by its own vendor UUID`() {
        for (model in SoundcoreModel.entries) {
            for (suffix in model.uuidSuffixes) {
                assertEquals(
                    if (model == SoundcoreModel.R60I_NC && suffix == "d1202") SoundcoreModel.P31I else model,
                    SoundcoreModel.fromServiceUuid(uuid(suffix)),
                    "${model.code} $suffix",
                )
            }
        }
    }

    @Test fun `the two UUID exceptions from the captures`() {
        assertEquals(SoundcoreModel.R50I_NC, SoundcoreModel.fromServiceUuid(uuid("b395b")))
        assertEquals(SoundcoreModel.R50I_NC, SoundcoreModel.fromServiceUuid(uuid("b3959")))
        assertEquals(SoundcoreModel.R60I_NC, SoundcoreModel.fromServiceUuid(uuid("1202c")))
    }

    @Test fun `a UUID on the same base that belongs to JBL or to an undriven model is not a Soundcore we drive`() {
        assertNull(SoundcoreModel.fromServiceUuid(uuid("b3850"))) // JBL's service on the shared base
        assertNull(SoundcoreModel.fromServiceUuid(uuid("b3138"))) // Boom 2: a speaker
        assertNull(SoundcoreModel.fromServiceUuid("00001101-0000-1000-8000-00805f9b34fb"))
        assertNull(SoundcoreModel.fromServiceUuid(SoundcoreModel.SERVICE_UUID_PREFIX + "b39470")) // too long
        assertNull(SoundcoreModel.fromServiceUuid("66666666-6666-6666-6666-666666666666")) // the BES firmware-update service
    }

    @Test fun `identify skips a JBL by name even when the UUID would match`() {
        val uuids = listOf("66666666-6666-6666-6666-666666666666", uuid("B3947"))
        assertEquals(SoundcoreModel.LIBERTY_4_NC to uuid("b3947"), SoundcoreModel.identify(uuids, "soundcore Liberty 4 NC"))
        assertEquals(SoundcoreModel.LIBERTY_4_NC to uuid("b3947"), SoundcoreModel.identify(uuids, "Alex's earbuds"))
        assertNull(SoundcoreModel.identify(uuids, "JBL Tour Pro 2"))
        assertNull(SoundcoreModel.identify(listOf("66666666-6666-6666-6666-666666666666"), "soundcore Liberty 4 NC"))
    }

    @Test fun `names pick the right model, and look-alikes stay generic`() {
        val cases = mapOf(
            "soundcore Liberty 4 NC" to SoundcoreModel.LIBERTY_4_NC,
            "Soundcore Liberty 4 Pro" to SoundcoreModel.LIBERTY_4_PRO,
            "soundcore Liberty 4" to SoundcoreModel.LIBERTY_4,
            "Soundcore Liberty 3 Pro" to SoundcoreModel.LIBERTY_3_PRO,
            "soundcore Liberty 5" to SoundcoreModel.LIBERTY_5,
            "soundcore Liberty 5 Pro" to SoundcoreModel.LIBERTY_5_PRO,
            "soundcore Liberty 5 Pro Max" to SoundcoreModel.LIBERTY_5_PRO_MAX,
            "soundcore Space One" to SoundcoreModel.SPACE_ONE,
            "soundcore Space One Pro" to SoundcoreModel.SPACE_ONE_PRO,
            "soundcore Space Q45" to SoundcoreModel.SPACE_Q45,
            "soundcore Space A40" to SoundcoreModel.SPACE_A40,
            "soundcore Space 2" to SoundcoreModel.SPACE_2,
            "soundcore P40i" to SoundcoreModel.P40I,
            "soundcore R50i NC" to SoundcoreModel.R50I_NC,
            "soundcore R50i" to SoundcoreModel.P20I,
            "soundcore A30i" to SoundcoreModel.A30I,
            "soundcore P31i" to SoundcoreModel.P31I,
            "soundcore R60i NC" to SoundcoreModel.R60I_NC,
            "soundcore Life Note 3" to SoundcoreModel.LIFE_NOTE_3,
            "soundcore Life Note 3S" to SoundcoreModel.LIFE_NOTE_3S,
            "soundcore AeroFit 2" to SoundcoreModel.AEROFIT_2,
            "soundcore AeroFit 2 Pro" to SoundcoreModel.AEROFIT_2_PRO,
            "soundcore Sleep A20" to SoundcoreModel.SLEEP_A20,
            "soundcore Sleep A30" to SoundcoreModel.SLEEP_A30,
        )
        for ((name, model) in cases) assertEquals(model, SoundcoreModel.fromName(name), name)
        for (name in listOf("Liberty 4 NC", "Space Ultra", "Space 2", "QCY-T13", "AirPods Pro", "soundcore Boom 2", "soundcore Motion+", "JBL Soundcore Clone", "Alex's headphones")) {
            assertNull(SoundcoreModel.fromName(name), name)
        }
    }

    @Test fun `codes, suffixes and serial prefixes are unique where they must be`() {
        assertEquals(SoundcoreModel.entries.size, SoundcoreModel.entries.map { it.code }.toSet().size)
        // R60i NC derives the same suffix as the P31i on purpose; its real UUID is the extra one.
        val derived = SoundcoreModel.entries.map { it.uuidSuffix }
        assertEquals(setOf("d1202"), derived.groupBy { it }.filter { it.value.size > 1 }.keys)
        val extras = SoundcoreModel.entries.flatMap { it.uuidSuffixes.drop(1) }
        assertTrue(extras.none { it in derived }, "exceptions are not derivable suffixes")
        assertEquals("3947", SoundcoreModel.LIBERTY_4_NC.serialPrefix)
        assertEquals("1301", SoundcoreModel.SLEEP_A30.serialPrefix)
        assertEquals("1202", SoundcoreModel.R60I_NC.serialPrefix)
        assertEquals(SoundcoreModel.LIBERTY_5, SoundcoreModel.fromSerial("3957F49D8AC20334"))
        assertEquals(SoundcoreModel.SPACE_2, SoundcoreModel.fromSerial("1402AABBCCDDEEFF"))
        assertNull(SoundcoreModel.fromSerial("9999AABBCCDDEEFF"))
        assertNull(SoundcoreModel.fromSerial(null))
    }

    @Test fun `battery scales map the way the notes say and reject the absent byte`() {
        assertEquals(60, SoundcoreBattery.FIVE.percent(3))
        assertNull(SoundcoreBattery.FIVE.percent(6))
        assertEquals(80, SoundcoreBattery.NINE.percent(7))
        assertNull(SoundcoreBattery.NINE.percent(10))
        assertEquals(70, SoundcoreBattery.TEN.percent(7))
        assertEquals(100, SoundcoreBattery.PERCENT.percent(100))
        for (scale in SoundcoreBattery.entries) assertNull(scale.percent(255), scale.name)
    }

    @Test fun `every layout is internally consistent`() {
        for (model in SoundcoreModel.entries) {
            val layout = model.layout ?: continue
            layout.stateSize?.let { size ->
                assertTrue(layout.modeBlockAt + layout.codec.size <= size, "${model.code} mode block fits")
                for (s in layout.settings) assertTrue(s.at + (if (s.autoOff) 2 else 1) <= size, "${model.code} ${s.id} fits")
                layout.caseBatteryAt?.let { assertTrue(it < size) }
            }
            layout.lengthByteAt?.let { assertTrue(it < layout.modeBlockAt, "${model.code} length byte precedes the block") }
            val offsets = layout.settings.flatMap { s -> if (s.autoOff) listOf(s.at, s.at + 1) else listOf(s.at) }
            assertEquals(offsets.size, offsets.toSet().size, "${model.code} settings do not overlap")
            assertTrue(offsets.none { it in layout.modeBlockAt until layout.modeBlockAt + layout.codec.size }, "${model.code} settings stay out of the mode block")
            assertTrue(layout.settings.map { it.id }.toSet().size == layout.settings.size, "${model.code} setting ids are unique")
            if (layout.settings.any { it.autoOff }) assertTrue(layout.autoOffMinutes.isNotEmpty(), model.code)
            // A model that may be written has everything the checks need.
            if (layout.writable) {
                assertNotNull(layout.stateSize, model.code)
                assertTrue(layout.lengthByteAt != null || model.header == SoundcoreHeader.G2H, "${model.code} is confirmed by a length byte or, for headphones, size and serial")
            }
        }
    }

    @Test fun `only the documented families are writable, everything else is read-only`() {
        val writable = SoundcoreModel.entries.filter { it.canWrite }.map { it.code }.toSet()
        assertEquals(
            setOf("A3947", "A3957", "A3955", "A3959", "A3958", "D1202", "D1202C", "A3936", "A3952", "A3035", "A3062", "A3040"),
            writable,
        )
        for (code in listOf("D1402", "A3953", "A3954", "D1203", "D1204", "D1206", "D1205", "A3875", "A3878", "A6611", "A3968")) {
            assertTrue(SoundcoreModel.entries.single { it.code == code }.canWrite.not(), code)
        }
    }
}
