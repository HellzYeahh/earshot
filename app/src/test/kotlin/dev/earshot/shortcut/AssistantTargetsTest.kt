package dev.earshot.shortcut

import dev.earshot.shortcut.AssistantCandidate.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantTargetsTest {
    private val own = "dev.earshot"
    private val earshot = AssistantCandidate(own, "dev.earshot.shortcut.ShortcutTriggerActivity", "Earshot", Kind.VOICE_COMMAND)
    private val googleHandsFree = AssistantCandidate(
        "com.google.android.googlequicksearchbox", "com.google.android.voicesearch.handsfree.HandsFreeActivity", "Google", Kind.VOICE_COMMAND,
    )
    private val googleAssist = AssistantCandidate("com.google.android.googlequicksearchbox", "com.google.android.apps.gsa.VoiceAssist", "Gemini", Kind.VOICE_ASSIST)
    private val bixby = AssistantCandidate("com.samsung.android.bixby.agent", "com.samsung.android.bixby.receiver.HeadSetReceiver", "Bixby", Kind.VOICE_COMMAND)

    private val galaxy = listOf(earshot, bixby, googleHandsFree, googleAssist)

    @Test
    fun `the user's pick wins when still installed`() {
        assertEquals(bixby, AssistantTargets.choose(bixby.component, galaxy, "com.google.android.googlequicksearchbox", own))
    }

    @Test
    fun `automatic picks the role holder's own VOICE_COMMAND handler first`() {
        assertEquals(googleHandsFree, AssistantTargets.choose(null, galaxy, "com.google.android.googlequicksearchbox", own))
        assertEquals(googleAssist, AssistantTargets.choose(null, listOf(bixby, googleAssist), "com.google.android.googlequicksearchbox", own))
    }

    @Test
    fun `without a role holder the first other VOICE_COMMAND handler`() {
        assertEquals(bixby, AssistantTargets.choose(null, galaxy, null, own))
        assertEquals(googleAssist, AssistantTargets.choose(null, listOf(earshot, googleAssist), null, own))
    }

    @Test
    fun `a missing pick falls back to automatic`() {
        assertEquals(googleHandsFree, AssistantTargets.choose("gone.app/.Main", galaxy, "com.google.android.googlequicksearchbox", own))
    }

    @Test
    fun `never Earshot, whatever was stored or resolved`() {
        assertEquals(bixby, AssistantTargets.choose(earshot.component, galaxy, own, own))
        assertNull(AssistantTargets.choose(earshot.component, listOf(earshot), own, own))
        assertNull(AssistantTargets.choose(null, emptyList(), null, own))
        // Every possible outcome over every input combination stays out of our package.
        val chosen = listOf(null, earshot.component, bixby.component, "x/y")
        val holders = listOf(null, own, bixby.packageName, "other")
        for (c in chosen) for (h in holders) {
            val pick = AssistantTargets.choose(c, galaxy, h, own)
            assertTrue(pick == null || pick.packageName != own)
        }
    }

    @Test
    fun `merge drops Earshot and duplicates, VOICE_COMMAND handlers first`() {
        val merged = AssistantTargets.merge(listOf(earshot, bixby, googleHandsFree), listOf(googleAssist, bixby.copy(kind = Kind.VOICE_ASSIST)), own)
        assertEquals(listOf(bixby, googleHandsFree, googleAssist), merged)
    }

    @Test
    fun `package of a flattened component`() {
        assertEquals("com.google.android.googlequicksearchbox", AssistantTargets.packageOf("com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService"))
        assertEquals("a.b", AssistantTargets.packageOf("a.b"))
        assertNull(AssistantTargets.packageOf(""))
        assertNull(AssistantTargets.packageOf(null))
    }
}
