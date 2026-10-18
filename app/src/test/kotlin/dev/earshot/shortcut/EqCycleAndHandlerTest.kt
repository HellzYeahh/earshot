package dev.earshot.shortcut

import dev.earshot.dsp.BuiltInPresets
import dev.earshot.dsp.EqPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EqCycleAndHandlerTest {
    private fun preset(id: String) = EqPreset(id, id.uppercase(), emptyList())
    private val presets = listOf(preset("flat"), preset("bass"), preset("vocal"), preset("mine"))

    @Test
    fun `next preset steps forward and wraps`() {
        assertEquals("bass", EqCycle.next(presets, "flat")?.id)
        assertEquals("mine", EqCycle.next(presets, "vocal")?.id)
        assertEquals("flat", EqCycle.next(presets, "mine")?.id)
    }

    @Test
    fun `custom bands or a deleted preset start at the first`() {
        assertEquals("flat", EqCycle.next(presets, null)?.id)
        assertEquals("flat", EqCycle.next(presets, "deleted")?.id)
        assertNull(EqCycle.next(emptyList(), "flat"))
        assertEquals("flat", EqCycle.next(listOf(preset("flat")), "flat")?.id)
    }

    @Test
    fun `cycles through every built-in preset once`() {
        val ids = generateSequence(BuiltInPresets.all.first()) { EqCycle.next(BuiltInPresets.all, it.id) }
            .take(BuiltInPresets.all.size).map { it.id }.toList()
        assertEquals(BuiltInPresets.all.map { it.id }, ids)
    }

    @Test
    fun `handler status from resolveActivity`() {
        val own = "dev.earshot"
        assertEquals(HandlerStatus.Earshot, HandlerStatus.classify(own, "dev.earshot.shortcut.ShortcutTriggerActivity", "Earshot", own))
        // What the Galaxy logged with Google and Bixby both installed and no default.
        assertEquals(HandlerStatus.Chooser, HandlerStatus.classify("android", "com.android.internal.app.ResolverActivity", "Android System", own))
        assertEquals(
            HandlerStatus.OtherApp("com.samsung.android.bixby.agent", "Bixby Voice"),
            HandlerStatus.classify("com.samsung.android.bixby.agent", "com.samsung.android.bixby.receiver.HeadSetReceiver", "Bixby Voice", own),
        )
        assertEquals(HandlerStatus.OtherApp("x.y", "x.y"), HandlerStatus.classify("x.y", "x.y.Main", " ", own))
        assertEquals(HandlerStatus.Unknown, HandlerStatus.classify(null, null, null, own))
    }
}
