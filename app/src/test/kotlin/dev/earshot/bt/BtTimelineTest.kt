package dev.earshot.bt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The diagnostics timeline's file format, capacity, last-seen rule and labels. */
class BtTimelineTest {

    private val headset = "68:F2:1F:38:DB:0F"

    @Test fun `events survive the file format, tabs and newlines in details included`() {
        val e = TimelineEvent(1_792_969_445_058, headset.lowercase(), TimelineKind.PROFILE_DOWN, "A2DP\tSDP\nfailed")
        val back = TimelineEvent.decode(e.encode())!!
        assertEquals(1_792_969_445_058, back.timeMs)
        assertEquals(headset, back.address, "addresses are stored upper case")
        assertEquals(TimelineKind.PROFILE_DOWN, back.kind)
        assertEquals("A2DP SDP failed", back.detail)
    }

    @Test fun `unreadable lines are skipped`() {
        assertNull(TimelineEvent.decode(""))
        assertNull(TimelineEvent.decode("abc\tAA\tLINK_UP"))
        assertNull(TimelineEvent.decode("1\tAA\tFROM_THE_FUTURE\tx"), "a kind from a newer version")
        assertEquals("", TimelineEvent.decode("1\tAA\tLINK_UP")!!.detail)
    }

    @Test fun `trim keeps the newest events`() {
        val events = (1..10).map { TimelineEvent(it.toLong(), headset, TimelineKind.CODEC, "$it") }
        assertEquals((7..10).map { "$it" }, BtTimeline.trim(events, capacity = 4).map { it.detail })
        assertEquals(events, BtTimeline.trim(events, capacity = 50))
    }

    @Test fun `last seen is the newest link or profile event for that headset`() {
        val events = listOf(
            TimelineEvent(1, headset, TimelineKind.LINK_UP),
            TimelineEvent(2, "11:22:33:44:55:66", TimelineKind.LINK_DOWN),
            TimelineEvent(3, headset, TimelineKind.LINK_DOWN),
            TimelineEvent(4, headset, TimelineKind.CODEC, "AAC"),
        )
        assertEquals(LastSeen(3, connected = false), BtTimeline.lastSeen(events, headset.lowercase()))
        assertNull(BtTimeline.lastSeen(events, "AA:BB:CC:DD:EE:FF"))
        assertEquals(LastSeen(5, connected = true), BtTimeline.lastSeen(events + TimelineEvent(5, headset, TimelineKind.PROFILE_UP, "A2DP"), headset))
    }

    @Test fun `the same news within a few seconds is a repeat`() {
        val recent = listOf(TimelineEvent(10_000, headset, TimelineKind.CODEC, "AAC"))
        assertTrue(BtTimeline.isRepeat(recent, TimelineEvent(11_000, headset.lowercase(), TimelineKind.CODEC, "AAC")))
        assertTrue(!BtTimeline.isRepeat(recent, TimelineEvent(11_000, headset, TimelineKind.CODEC, "SBC")), "a different codec is news")
        assertTrue(!BtTimeline.isRepeat(recent, TimelineEvent(20_000, headset, TimelineKind.CODEC, "AAC")), "the same codec later is a new event")
    }

    @Test fun `a quick drop and reconnect keeps its second link-up`() {
        val recent = listOf(
            TimelineEvent(0, headset, TimelineKind.LINK_UP),
            TimelineEvent(1_000, headset, TimelineKind.LINK_DOWN),
            TimelineEvent(1_100, headset, TimelineKind.PROFILE_UP, "A2DP"),
            TimelineEvent(1_200, headset, TimelineKind.PROFILE_DOWN, "A2DP"),
        )
        assertTrue(!BtTimeline.isRepeat(recent, TimelineEvent(2_000, headset, TimelineKind.LINK_UP)))
        assertTrue(!BtTimeline.isRepeat(recent, TimelineEvent(2_100, headset, TimelineKind.PROFILE_UP, "A2DP")))
        assertTrue(BtTimeline.isRepeat(recent, TimelineEvent(1_300, headset, TimelineKind.PROFILE_DOWN, "A2DP")))
        // A different profile going down doesn't cancel an A2DP repeat.
        val withHfp = recent + TimelineEvent(1_300, headset, TimelineKind.PROFILE_DOWN, "HFP")
        assertTrue(BtTimeline.isRepeat(withHfp, TimelineEvent(1_400, headset, TimelineKind.PROFILE_DOWN, "A2DP")))
    }

    @Test fun `a codec after a disconnect is news even without a recorded link-up`() {
        val history = listOf(
            TimelineEvent(1, headset, TimelineKind.LINK_UP),
            TimelineEvent(2, headset, TimelineKind.CODEC, "AAC, 44.1 kHz"),
            TimelineEvent(3, headset, TimelineKind.LINK_DOWN),
        )
        assertTrue(!BtTimeline.isUnchanged(history, TimelineEvent(4, headset, TimelineKind.CODEC, "AAC, 44.1 kHz")))
    }

    @Test fun `a codec is recorded when it changes or after a new link-up`() {
        val history = listOf(
            TimelineEvent(1, headset, TimelineKind.LINK_UP),
            TimelineEvent(2, headset, TimelineKind.CODEC, "AAC, 44.1 kHz"),
        )
        assertTrue(BtTimeline.isUnchanged(history, TimelineEvent(60_000, headset, TimelineKind.CODEC, "AAC, 44.1 kHz")))
        assertTrue(!BtTimeline.isUnchanged(history, TimelineEvent(60_000, headset, TimelineKind.CODEC, "SBC, 44.1 kHz")))
        val relinked = history + TimelineEvent(70_000, headset, TimelineKind.LINK_UP)
        assertTrue(!BtTimeline.isUnchanged(relinked, TimelineEvent(71_000, headset, TimelineKind.CODEC, "AAC, 44.1 kHz")), "news again after a link-up")
        assertTrue(!BtTimeline.isUnchanged(history, TimelineEvent(60_000, headset, TimelineKind.LINK_UP)), "link events are always recorded")
    }

    @Test fun `Android's codes read as words`() {
        assertEquals("service discovery (SDP) failed", BtTimeline.disconnectReason(1400))
        assertNull(BtTimeline.disconnectReason(BtTimeline.NO_CODE))
        assertEquals("reason 7", BtTimeline.disconnectReason(7))
        assertEquals("the RFCOMM connection failed", BtTimeline.disconnectReason(1405))
        assertNull(BtTimeline.disconnectReason(0), "SUCCESS is an ordinary disconnect")
        assertEquals("unknown reason", BtTimeline.disconnectReason(Int.MAX_VALUE))
        assertEquals("LE encryption failed", BtTimeline.bondLossReason(3))
        assertEquals("AES, 16-byte key", BtTimeline.encryption(enabled = true, keySize = 16, algorithm = 2))
        assertEquals("off", BtTimeline.encryption(enabled = false, keySize = 16, algorithm = 2))
        assertEquals("mSBC (wideband)", BtTimeline.callCodecLabel("CODEC_TYPE_MSBC"))
        assertNull(BtTimeline.callCodecLabel("CODEC_TYPE_UNSUPPORTED"))
        assertTrue(BtTimeline.describe(TimelineEvent(1, headset, TimelineKind.KEY_MISSING, "LE encryption failed")).startsWith("Pairing key missing"))
    }
}
