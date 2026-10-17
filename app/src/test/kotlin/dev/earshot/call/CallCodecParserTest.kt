package dev.earshot.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * All samples are SYNTHETIC: written from the layout of AOSP's `HeadsetService.dump()` /
 * `HeadsetStateMachine.dump()` and `LeAudioService.dump()`, not captured from a device. Replace
 * them with a real `adb shell dumpsys bluetooth_manager` taken *during a call* on the user's Galaxy
 * once one exists (the repository logs the candidate lines when it can't resolve a codec).
 */
class CallCodecParserTest {

    private fun headsetSection(
        wbs: Boolean,
        lc3: Boolean = false,
        aptx: Boolean = false,
        state: String = "AudioOn",
        audioState: Int = 12,
        address: String = "4C:87:5D:AA:BB:CC",
    ) = """
        Profile: HeadsetService
          mMaxHeadsetConnections: 2
          DefaultMaxHeadsetConnections: 2
          mActiveDevice: $address
          isInbandRingingEnabled: true
          mAudioRouteAllowed: true
          mVoiceRecognitionStarted: false
          mVirtualCallStarted: false
          mForceScoAudio: false
          AudioManager.isBluetoothScoOn(): ${state == "AudioOn"}
          Telecom.isInCall(): true
          ==== StateMachine for $address ====
            mCurrentDevice: $address
            mCurrentState: $state
            mPrevState: Connected
            mConnectionState: 2
            mAudioState: $audioState
            mNeedDialingOutReply: false
            mSpeakerVolume: 15
            mMicVolume: 15
            mConnectingTimestampMs(uptimeMillis): 98765
            mHasWbsEnabled: $wbs
            mHasSwbLc3Enabled: $lc3
            mHasSwbAptXEnabled: $aptx
            mHasNrecEnabled: false
            StateMachine: HeadsetStateMachine:
             total records=3
             rec[0]: time=10-17 09:12:01.123 processed=Connected org=Connected dest=<null> what=STACK_EVENT AT+BAC=1,2
             rec[1]: time=10-17 09:12:01.456 processed=Connected org=Connected dest=<null> what=STACK_EVENT AT+BCS=2
             rec[2]: time=10-17 09:12:02.001 processed=AudioConnecting org=AudioConnecting dest=AudioOn what=STACK_EVENT
    """.trimIndent()

    private val a2dpSection = """
        Profile: A2dpService
          mActiveDevice: 4C:87:5D:AA:BB:CC
          codecConfigPriorities:
            SBC: 1001
            AAC: 2001
          === A2dpStateMachine for 4C:87:5D:AA:BB:CC ===
            mCodecConfig: {codecName:AAC,mCodecType:1,mCodecPriority:2001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}
    """.trimIndent()

    private val leSectionIdle = """
        Profile: LeAudioService
          isDualModeAudioEnabled: false
          Active Groups information:
            currentlyActiveGroupId: -1
            mActiveAudioOutDevice: null
            mActiveAudioInDevice: null
    """.trimIndent()

    private val leSectionInCall = """
        Profile: LeAudioService
          isDualModeAudioEnabled: false
          Active Groups information:
            currentlyActiveGroupId: 1
            mActiveAudioOutDevice: 4C:87:5D:AA:BB:CC
            mActiveAudioInDevice: 4C:87:5D:AA:BB:CC
            mExposedActiveDevice: 4C:87:5D:AA:BB:CC
          Group: 1
            Sampling frequency: 32000
    """.trimIndent()

    private fun dump(vararg sections: String) = (listOf("Bluetooth Status", "  enabled: true", "  state: ON") + sections).joinToString("\n")

    @Test fun `mSBC from the WBS flag while audio is on`() {
        val parsed = CallCodecParser.parse(dump(a2dpSection, headsetSection(wbs = true), leSectionIdle))
        assertEquals("4C:87:5D:AA:BB:CC", parsed.hfpActiveDevice)
        assertEquals(1, parsed.hfpDevices.size)
        assertEquals(true, parsed.hfpDevices[0].audioOn)
        assertEquals(true, parsed.scoOn)
        assertFalse(parsed.leAudioActive)

        val reading = assertNotNull(CallCodecParser.resolve(parsed, CallRoute.BLUETOOTH_SCO))
        assertEquals(VoiceCodec.MSBC, reading.codec)
        assertTrue(reading.wideband)
        assertEquals(16_000, reading.sampleRateHz)
    }

    @Test fun `CVSD when WBS is off, even though the history shows an old AT+BCS=2`() {
        val parsed = CallCodecParser.parse(dump(headsetSection(wbs = false)))
        val reading = assertNotNull(CallCodecParser.resolve(parsed, CallRoute.BLUETOOTH_SCO))
        assertEquals(VoiceCodec.CVSD, reading.codec)
        assertFalse(reading.wideband)
        assertEquals(8_000, reading.sampleRateHz)
    }

    @Test fun `LC3 super-wideband wins over the WBS flag`() {
        val parsed = CallCodecParser.parse(dump(headsetSection(wbs = true, lc3 = true)))
        val reading = assertNotNull(CallCodecParser.resolve(parsed, CallRoute.BLUETOOTH_SCO))
        assertEquals(VoiceCodec.LC3_SWB, reading.codec)
        assertEquals(32_000, reading.sampleRateHz)
    }

    @Test fun `aptX voice flag`() {
        val parsed = CallCodecParser.parse(dump(headsetSection(wbs = true, aptx = true)))
        assertEquals(VoiceCodec.APTX_SWB, CallCodecParser.resolve(parsed, CallRoute.BLUETOOTH_SCO)?.codec)
    }

    @Test fun `multipoint picks the headset whose audio is on`() {
        val text = """
            Profile: HeadsetService
              mActiveDevice: 11:22:33:44:55:66
              ==== StateMachine for 11:22:33:44:55:66 ====
                mCurrentDevice: 11:22:33:44:55:66
                mCurrentState: Connected
                mAudioState: 10
                mHasWbsEnabled: false
              ==== StateMachine for 4C:87:5D:AA:BB:CC ====
                mCurrentDevice: 4C:87:5D:AA:BB:CC
                mCurrentState: AudioOn
                mAudioState: 12
                mHasWbsEnabled: true
        """.trimIndent()
        val parsed = CallCodecParser.parse(text)
        assertEquals(2, parsed.hfpDevices.size)
        assertEquals(VoiceCodec.MSBC, CallCodecParser.resolve(parsed, CallRoute.BLUETOOTH_SCO)?.codec)
    }

    @Test fun `without audio-on, the Bose address wins, matched on the redacted tail`() {
        val text = """
            Profile: HeadsetService
              mActiveDevice: XX:XX:XX:XX:55:66
              ==== StateMachine for XX:XX:XX:XX:55:66 ====
                mCurrentState: Connected
                mHasWbsEnabled: false
              ==== StateMachine for XX:XX:XX:XX:00:11 ====
                mCurrentState: Connected
                mHasWbsEnabled: true
        """.trimIndent()
        val parsed = CallCodecParser.parse(text)
        assertEquals(VoiceCodec.MSBC, CallCodecParser.resolve(parsed, CallRoute.UNKNOWN, preferAddress = "E4:58:BC:00:00:11")?.codec)
        // No preference: falls back to the service's active device.
        assertEquals(VoiceCodec.CVSD, CallCodecParser.resolve(parsed, CallRoute.UNKNOWN)?.codec)
    }

    @Test fun `OEM explicit codec line beats the flags`() {
        val text = """
            Profile: HeadsetService
              mActiveDevice: XX:XX:XX:XX:00:11
              ==== StateMachine for XX:XX:XX:XX:00:11 ====
                mCurrentState: AudioOn
                mHasWbsEnabled: false
                mScoCodec: mSBC
        """.trimIndent()
        assertEquals(VoiceCodec.MSBC, CallCodecParser.resolve(CallCodecParser.parse(text), CallRoute.BLUETOOTH_SCO)?.codec)
    }

    @Test fun `numeric codec ids follow AT+BCS`() {
        fun codecFor(value: String) = CallCodecParser.resolve(
            CallCodecParser.parse(
                """
                Profile: HeadsetService
                  ==== StateMachine for XX:XX:XX:XX:00:11 ====
                    mCurrentState: AudioOn
                    mCodec: $value
                """.trimIndent(),
            ),
            CallRoute.BLUETOOTH_SCO,
        )?.codec
        assertEquals(VoiceCodec.CVSD, codecFor("1"))
        assertEquals(VoiceCodec.MSBC, codecFor("2"))
        assertEquals(VoiceCodec.LC3_SWB, codecFor("3"))
        assertNull(codecFor("0"))
    }

    @Test fun `service-level codec line is used when no device block has one`() {
        val text = """
            Profile: HeadsetService
              mActiveDevice: XX:XX:XX:XX:00:11
              Current SCO codec: CVSD
        """.trimIndent()
        assertEquals(VoiceCodec.CVSD, CallCodecParser.resolve(CallCodecParser.parse(text), CallRoute.BLUETOOTH_SCO)?.codec)
    }

    @Test fun `native stack SCO line is a last resort`() {
        val text = dump(a2dpSection) + "\n" + """
            BTM SCO
              Active eSCO link codec: mSBC
        """.trimIndent()
        val parsed = CallCodecParser.parse(text)
        assertEquals(VoiceCodec.MSBC, parsed.hfpServiceCodec)
        assertEquals(VoiceCodec.MSBC, CallCodecParser.resolve(parsed, CallRoute.BLUETOOTH_SCO)?.codec)
    }

    @Test fun `A2DP codec blocks are never read as the call codec`() {
        val parsed = CallCodecParser.parse(dump(a2dpSection))
        assertFalse(parsed.hasHfpSection)
        assertNull(CallCodecParser.resolve(parsed, CallRoute.BLUETOOTH_SCO))
    }

    @Test fun `LE Audio call reports LC3 with the dumped sample rate`() {
        val parsed = CallCodecParser.parse(dump(headsetSection(wbs = false, state = "Connected", audioState = 10), leSectionInCall))
        assertTrue(parsed.leAudioActive)
        assertEquals("4C:87:5D:AA:BB:CC", parsed.leActiveInDevice)
        assertEquals(32_000, parsed.leSampleRateHz)

        val viaRoute = assertNotNull(CallCodecParser.resolve(parsed, CallRoute.LE_AUDIO))
        assertEquals(VoiceCodec.LC3_LE, viaRoute.codec)
        assertTrue(viaRoute.wideband)
        assertEquals(32_000, viaRoute.sampleRateHz)
        // Route unknown: LE mic active and no SCO up → still LE.
        assertEquals(VoiceCodec.LC3_LE, CallCodecParser.resolve(parsed, CallRoute.UNKNOWN)?.codec)
    }

    @Test fun `Android says SCO, so an active LE group (media) is ignored`() {
        val parsed = CallCodecParser.parse(dump(headsetSection(wbs = true), leSectionInCall))
        assertEquals(VoiceCodec.MSBC, CallCodecParser.resolve(parsed, CallRoute.BLUETOOTH_SCO)?.codec)
    }

    @Test fun `kHz sample rate form and BOM-prefixed header`() {
        val text = "﻿Profile: LeAudioService\n  currentlyActiveGroupId: 2\n  mActiveAudioInDevice: XX:XX:XX:XX:00:11\n  codec config: LC3 16 kHz 30 octets"
        val parsed = CallCodecParser.parse(text)
        assertTrue(parsed.leAudioActive)
        assertEquals(16_000, parsed.leSampleRateHz)
    }

    @Test fun `diagnostic lines come only from the HFP and LE sections, without history`() {
        val lines = CallCodecParser.diagnosticLines(dump(a2dpSection, headsetSection(wbs = true), leSectionIdle))
        assertTrue(lines.any { it.startsWith("mHasWbsEnabled") })
        assertTrue(lines.any { it.startsWith("currentlyActiveGroupId") })
        assertTrue(lines.none { it.contains("codecName") || it.contains("rec[") })
    }

    @Test fun `normalize maps OEM spellings`() {
        assertEquals(VoiceCodec.MSBC, VoiceCodec.normalize("MSBC"))
        assertEquals(VoiceCodec.MSBC, VoiceCodec.normalize("WBS"))
        assertEquals(VoiceCodec.CVSD, VoiceCodec.normalize("cvsd,"))
        assertEquals(VoiceCodec.LC3_SWB, VoiceCodec.normalize("LC3_SWB"))
        assertEquals(VoiceCodec.APTX_SWB, VoiceCodec.normalize("aptX_SWB"))
        assertNull(VoiceCodec.normalize("true"))
        assertNull(VoiceCodec.normalize("null"))
    }
}
