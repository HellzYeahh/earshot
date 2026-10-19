package dev.earshot.protocol.qcultra2

import dev.earshot.protocol.BmapError
import dev.earshot.protocol.BmapException
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.hexToBytes
import dev.earshot.protocol.toHex
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Firmware 10.12 real capture: payloads Earshot itself read from a QC Ultra 2 running
 * 10.12.12+ga927c9a, two major versions past the 8.2.20 unit bosectl documented. Anything that
 * differs from the bosectl scans is called out in the test names. The serial number is masked
 * after its model prefix, as in bosectl's notes.
 */
class Firmware1012CaptureTest {
    private object Fw1012 {
        const val BATTERY = "3cffff00"
        const val PRODUCT_ID_VARIANT = "408201"
        const val FIRMWARE = "31302e31322e31322b6761393237633961"
        const val SERIAL_PREFIX = "30383539353854"          // "085958T" + masked tail
        const val PRODUCT_NAME = "426f736520514320556c7472612032204850"
        const val MAC = "68f21f000001"
        const val CUSTOM_NAME = "01426f736520514320556c7472612032204850"
        const val EQ = "f60a0300f60a0001f60a0202"
        const val SIDETONE = "01030f"
        const val MULTIPOINT = "07"
        const val AUTO_PLAY_PAUSE = "01"
        const val AUTO_ANSWER = "01"
        const val VOICE_PROMPTS = "41000081020000"
        const val BUTTONS = "800913000940020001000201011001"
        /** `[2.3]` answered ERROR FuncNotSupp instead of a STATUS. */
        val CHARGING_STATE_REPLY = BmapPacket(2, 3, BmapOperator.ERROR, byteArrayOf(4))

        /** `[4.4]`: header 01, the streaming phone first, then an absent paired device. */
        const val PAIRED_LIST = "012cda4600000210b1df000003"
        /** `[4.5]` for the connected phone: flags 03, then `02 03`, then the name. */
        const val DEVICE_INFO_PHONE = "2cda460000020302034d792047616c61787920466f6c64"
        /** `[4.5]` for a paired-but-absent device: flags 00, then `01 01`, then the name. */
        const val DEVICE_INFO_ABSENT = "10b1df0000030001014f4c445f4c4150544f50"
        /** `[5.1]`: supported mask 000f, Bluetooth source, the phone's MAC. */
        const val AUDIO_SOURCE = "000f012cda46000002"
        /** `[31.8]`: 11 slots, favourites Quiet, Aware, Immersion, slot 4 and slot 5. */
        const val FAVORITES = "0b0037"
    }

    @Test
    fun `paired list and device info decode both real record shapes`() {
        val list = PairedDeviceList.parse(Fw1012.PAIRED_LIST.hexToBytes())!!
        assertEquals(listOf("2C:DA:46:00:00:02", "10:B1:DF:00:00:03"), list.macs)

        val phone = DeviceInfoQuery.parse(Fw1012.DEVICE_INFO_PHONE.hexToBytes())!!
        assertEquals("2C:DA:46:00:00:02", phone.mac)
        assertEquals("My Galaxy Fold", phone.name)
        assertTrue(phone.looksConnected)

        val absent = DeviceInfoQuery.parse(Fw1012.DEVICE_INFO_ABSENT.hexToBytes())!!
        assertEquals("OLD_LAPTOP", absent.name)
        assertFalse(absent.looksConnected)

        val source = AudioSourceQuery.parse(Fw1012.AUDIO_SOURCE.hexToBytes())!!
        assertEquals("2C:DA:46:00:00:02", source.mac)
    }

    @Test
    fun `favourites 0b0037 are slots 0 1 2 4 5`() {
        assertEquals(setOf(0, 1, 2, 4, 5), FavoritesCodec.parse(Fw1012.FAVORITES.hexToBytes())!!.indices)
    }

    @Test
    fun `battery 3cffff00 is 60 percent`() {
        assertEquals(60, Battery.parsePercent(Fw1012.BATTERY.hexToBytes()))
        assertEquals(60, Battery.parse(Fw1012.BATTERY.hexToBytes()).percent)
    }

    @Test
    fun `2_3 is AuxCableDetection and not supported on this firmware`() {
        val ex = assertFailsWith<BmapException> { Replies.pick(listOf(Fw1012.CHARGING_STATE_REPLY), QcUltra2.AUX_CABLE_DETECTION) }
        assertEquals(BmapError.FUNC_NOT_SUPPORTED, ex.error)
        assertFalse(ex.operatorNotSupported)
        assertFalse(FunctionNames.isKnownReadable(QcUltra2.AUX_CABLE_DETECTION))
        assertEquals("AuxCableDetection", FunctionNames.functionName(QcUltra2.AUX_CABLE_DETECTION))
    }

    @Test
    fun `product info reads the newer firmware string and the full MAC`() {
        val id = ProductInfo.parseProductIdVariant(Fw1012.PRODUCT_ID_VARIANT.hexToBytes())!!
        assertEquals(QcUltra2.PRODUCT_ID, id.productId)
        assertEquals(1, id.variant)
        assertEquals("10.12.12+ga927c9a", ProductInfo.parseFirmwareVersion(Fw1012.FIRMWARE.hexToBytes()))
        assertEquals("085958T", ProductInfo.parseString(Fw1012.SERIAL_PREFIX.hexToBytes()))
        assertEquals("Bose QC Ultra 2 HP", ProductInfo.parseString(Fw1012.PRODUCT_NAME.hexToBytes()))
        assertEquals("68:F2:1F:00:00:01", ProductInfo.parseMac(Fw1012.MAC.hexToBytes()))
    }

    @Test
    fun `custom name 1_2 has flag 01 and the factory name on a never-renamed unit`() {
        val state = CustomName.parseState(Fw1012.CUSTOM_NAME.hexToBytes())!!
        assertEquals(1, state.flag)
        assertEquals("Bose QC Ultra 2 HP", state.name)
        assertTrue(state.isFactoryDefault)
        assertEquals("Bose QC Ultra 2 HP", CustomName.parse(Fw1012.CUSTOM_NAME.hexToBytes()))
        // and the bosectl unit, renamed to "Fargo", carried flag 00
        assertFalse(CustomName.parseState("00466172676f".hexToBytes())!!.isFactoryDefault)
    }

    @Test
    fun `EQ f60a0300 f60a0001 f60a0202 is bass +3 mid 0 treble +2`() {
        val bands = Eq.parse(Fw1012.EQ.hexToBytes())
        assertEquals(listOf(3, 0, 2), bands.map { it.value })
        assertEquals(3, Eq.valueOf(bands, EqBandId.BASS))
        assertEquals(2, Eq.valueOf(bands, EqBandId.TREBLE))
        assertEquals("0300", Eq.buildBand(3, EqBandId.BASS).toHex())
    }

    @Test
    fun `sidetone 01030f is persistent low with all four levels supported`() {
        val s = Sidetone.parse(Fw1012.SIDETONE.hexToBytes())!!
        assertTrue(s.persist)
        assertEquals(3, s.levelCode)
        assertEquals(SidetoneLevel.LOW, s.level)
        assertEquals(0x0f, s.supportedLevelsMask)
        assertEquals(setOf(0, 1, 2, 3), s.supportedLevelCodes)
        assertEquals(listOf(SidetoneLevel.OFF, SidetoneLevel.HIGH, SidetoneLevel.MEDIUM, SidetoneLevel.LOW), s.supportedLevels)
        // a 2-byte frame still parses, assuming every level
        assertEquals(setOf(0, 1, 2, 3), Sidetone.parse("0102".hexToBytes())!!.supportedLevelCodes)
    }

    @Test
    fun `toggles and voice prompts are unchanged from 8_2_20`() {
        assertEquals(true, Multipoint.parse(Fw1012.MULTIPOINT.hexToBytes()))
        assertEquals(true, Toggle.parse(Fw1012.AUTO_PLAY_PAUSE.hexToBytes()))
        assertEquals(true, Toggle.parse(Fw1012.AUTO_ANSWER.hexToBytes()))
        val vp = VoicePrompts.parse(Fw1012.VOICE_PROMPTS.hexToBytes())!!
        assertEquals(VoicePromptLanguage.US_ENGLISH, vp.language)
        assertEquals(setOf(1, 8, 15), vp.installedLanguageCodes)
    }

    @Test
    fun `buttons 15-byte frame has spatial audio selected and the mask contains it`() {
        val cfg = Buttons.parse(Fw1012.BUTTONS.hexToBytes())!!
        assertEquals(ButtonId.SHORTCUT, cfg.button)
        assertEquals(ButtonEvent.LONG_PRESS, cfg.event)
        assertEquals(19, cfg.actionCode)
        assertEquals(ButtonAction.SPATIAL_AUDIO_MODE, cfg.action)
        // 0x00094002 big-endian → {1, 14, 16, 19}: the shortcut-button menu, current action included
        assertEquals(setOf(1, 14, 16, 19), cfg.supportedActionCodes)
        assertEquals(listOf(ButtonAction.VPA, ButtonAction.DISABLED, ButtonAction.SPOTIFY_GO, ButtonAction.SPATIAL_AUDIO_MODE), cfg.supportedActions)
        assertTrue(cfg.isConsistent)
        // 0x00010002 → {1 VPA, 16 SpotifyGo}: read as "unavailable until their apps are set up" (guess)
        assertEquals(setOf(1, 16), cfg.unavailableActionCodes)
        assertEquals(setOf(14, 19), cfg.availableActionCodes)
        // the last four bytes are kept raw
        assertEquals("01011001", cfg.tail.toHex())
        assertEquals(15, cfg.raw.size)
        // the SET_GET we would send to pick another choice is still three bytes
        assertEquals("80090e", Buttons.build(ButtonId.SHORTCUT, ButtonEvent.LONG_PRESS, ButtonAction.DISABLED).toHex())
        assertEquals("800913", Buttons.build(cfg.buttonCode, cfg.eventCode, cfg.actionCode).toHex())
    }

    @Test
    fun `the old byte-wise mask reading would have excluded the current action`() {
        // Regression guard for the bit order: bosectl's little-endian-per-byte reading of
        // 00094002 was {8, 11, 22, 25}, which cannot be right for a frame whose current action is 19.
        val cfg = Buttons.parse(Fw1012.BUTTONS.hexToBytes())!!
        assertFalse(8 in cfg.supportedActionCodes)
        assertFalse(22 in cfg.supportedActionCodes)
        // short frames degrade gracefully: no mask bytes → no choices, never a crash
        val short = Buttons.parse("800913".hexToBytes())!!
        assertTrue(short.supportedActionCodes.isEmpty())
        assertTrue(short.unavailableActionCodes.isEmpty())
        assertTrue(Buttons.parse("8009130009".hexToBytes())!!.supportedActionCodes.isEmpty())
    }

    // ── Deep scan 2026-10-17 (docs/reference/captures-galaxy/deep-scan-fw10.12-2026-10-17.txt) ──

    private object Scan {
        const val BMAP_VERSION = "312e322e30"
        const val ALL_FBLOCKS = "87cc23ff"
        const val LE_AUDIO_SUPPORTED = "01"
        const val COMPONENT_ID = "0068f21f000001"
        const val DISABLE_CAPTOUCH = "01"
        const val LE_AUDIO_CONTROL = "01"
        const val BATTERY = "5affff00"
        const val CHARGER = "00"
        const val OPERATION_MODE = "01"
        const val MIC_MUTE = "01"
        const val LIST_DEVICES = "0310b1df0000032cda46000002"
        const val APP_ADDRESS = "2cda46000002"
        const val FEATURES = "01"
        const val AVAILABLE_TO_CONNECT = "01"
        const val SOURCE = "000f0110b1df000003"
        const val CONTROL = "1f"
        const val AUDIO_STATUS = "01ffff"
        const val VOLUME = "1f16"
        const val LATENCY = "0000020800d2"
        const val CONTROL_GET_ALL = "0873"
        const val NOTIFY_BY_FBLOCK = "00000000"
        const val DATA_COLLECTION_GET_ALL = "ff83"
        const val AUTH_GET_ALL = "0339083e07"
        const val FAVORITES = "0b0037"
    }

    @Test
    fun `bitsets are big-endian - 7_1 0873 is Control functions 0 1 4 5 6 11`() {
        // Exactly the functions that answered: 7.0/7.1/7.4 STATUS, 7.5/7.6/7.11 OP_NOT_SUPPORTED.
        assertEquals(setOf(0, 1, 4, 5, 6, 11), SupportedFunctions.parse(Scan.CONTROL_GET_ALL.hexToBytes()))
        assertEquals(setOf(0, 1) + (7..15), SupportedFunctions.parse(Scan.DATA_COLLECTION_GET_ALL.hexToBytes()))
        assertEquals(setOf(0, 1, 2, 9, 10, 11, 12, 13, 19, 24, 27, 28, 29, 32, 33), SupportedFunctions.parse(Scan.AUTH_GET_ALL.hexToBytes()))
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 13, 18, 19, 22, 23, 24, 25, 26, 31),
            ProductInfo.parseAllFunctionBlocks(Scan.ALL_FBLOCKS.hexToBytes()))
        assertEquals(emptySet(), Notifications.parseByFblock(Scan.NOTIFY_BY_FBLOCK.hexToBytes()))
        // [31.8] is a count followed by a big-endian bitset: 0037 → {0, 1, 2, 4, 5}
        assertEquals(setOf(0, 1, 2, 4, 5), FavoritesCodec.parse(Scan.FAVORITES.hexToBytes())!!.indices)
        // round trip of the encoder on the same frames
        assertEquals(Scan.CONTROL_GET_ALL, Bitset.encode(setOf(0, 1, 4, 5, 6, 11), 2).toHex())
        assertEquals(Scan.AUTH_GET_ALL, Bitset.encode(SupportedFunctions.parse(Scan.AUTH_GET_ALL.hexToBytes()), 5).toHex())
    }

    @Test
    fun `9_2 enable payload is the Bose app's 01 80 04 22 b7 for wolverine`() {
        val blocks = Notifications.blocksToEnable(ProductInfo.parseAllFunctionBlocks(Scan.ALL_FBLOCKS.hexToBytes()))
        assertEquals(setOf(0, 1, 2, 4, 5, 7, 9, 13, 18, 31), blocks)
        assertEquals(Notifications.WOLVERINE_BLOCKS, blocks)
        assertEquals("01800422b7", Notifications.byFblockPayload(blocks).toHex())
        assertEquals("09020205" + "01800422b7", dev.earshot.protocol.BmapPacket.setGet(QcUltra2.NOTIFY_BY_FBLOCK,
            Notifications.byFblockPayload(blocks)).encode().toHex())
        assertEquals(blocks, Notifications.parseByFblock("800422b7".hexToBytes()))
        assertEquals(blocks, Notifications.parseByFblock("01800422b7".hexToBytes()))
        assertEquals("000000", Notifications.byFblockPayload(emptySet(), Notifications.OP_OVERWRITE).toHex())
        assertEquals(Notifications.WOLVERINE_BLOCKS, Notifications.blocksToEnable(null))
    }

    @Test
    fun `5_7 latency 0000 0208 00d2 is 0 to 520 current 210`() {
        val l = Latency.parse(Scan.LATENCY.hexToBytes())!!
        assertEquals(LatencyLevel(0, 520, 210), l)
        assertEquals(0..520, l.range)
        assertEquals("00d2", Latency.build(210).toHex())
        assertEquals("ffff", Latency.build(-1).toHex())
        assertEquals(null, Latency.parse("00000208".hexToBytes()))
    }

    @Test
    fun `5_5 volume 1f16 is 22 of 31 and 5_4 01ffff is playing with unknown position`() {
        assertEquals(VolumeState(max = 31, current = 22), Volume.parse(Scan.VOLUME.hexToBytes()))
        assertEquals("10", Volume.build(16).toHex())
        assertFailsWith<IllegalArgumentException> { Volume.build(32) }
        val st = AudioStatusQuery.parse(Scan.AUDIO_STATUS.hexToBytes())!!
        assertEquals(PlaybackStatus.PLAYING, st.status)
        assertEquals(null, st.trackPosition)
        assertEquals(42, AudioStatusQuery.parse("02002a".hexToBytes())!!.trackPosition)
        assertEquals(setOf(0, 1, 2, 3, 4), AudioControl.parseSupported(Scan.CONTROL.hexToBytes()))
        assertEquals("01", AudioControl.startPayload(TransportControl.PLAY).toHex())
        val src = AudioSourceQuery.parse(Scan.SOURCE.hexToBytes())!!
        assertEquals(setOf(0, 1, 2, 3), src.supportedTypeCodes)
        assertEquals("10:B1:DF:00:00:03", src.mac)
    }

    @Test
    fun `status block 2_5 2_16 2_21 and battery 5affff00`() {
        assertEquals(false, ChargerDetect.parse(Scan.CHARGER.hexToBytes()))
        assertEquals(OperationModeCode.NORMAL, OperationMode.parse(Scan.OPERATION_MODE.hexToBytes()))
        assertEquals(true, MicMute.parse(Scan.MIC_MUTE.hexToBytes()))
        assertEquals(90, Battery.parse(Scan.BATTERY.hexToBytes()).percent)
        assertEquals(null, Battery.parseReadings(Scan.BATTERY.hexToBytes()).single().remainingMinutes)
        assertEquals(125, Battery.parseReadings("50007d00".hexToBytes()).single().remainingMinutes)
    }

    @Test
    fun `LE audio 0_23 and 1_37 are on, touch 1_34 reads on, BMAP 1_2_0`() {
        assertEquals(true, ProductInfo.parseLeAudioSupported(Scan.LE_AUDIO_SUPPORTED.hexToBytes()))
        assertEquals(true, LeAudioControl.parse(Scan.LE_AUDIO_CONTROL.hexToBytes()))
        assertEquals("00", LeAudioControl.build(false).toHex())
        // "DisableCaptouch" = 1 → touch controls are ON, despite the name (Bose app parser; verified on
        // the QC Ultra 2 on 2026-10-18 when 00 left the strip dead and the Bose app restored 01).
        assertEquals(true, TouchControls.parseEnabled(Scan.DISABLE_CAPTOUCH.hexToBytes()))
        assertEquals("01", TouchControls.build(enabled = true).toHex())
        assertEquals("00", TouchControls.build(enabled = false).toHex())
        assertEquals(QcUltra2.BMAP_VERSION_FW_10_12, ProductInfo.parseBmapVersion(Scan.BMAP_VERSION.hexToBytes()))
        assertEquals(BatteryComponent.SINGLE.code, ProductInfo.parseComponentId(Scan.COMPONENT_ID.hexToBytes()))
        assertEquals("68:F2:1F:00:00:01", ProductInfo.parseMac(Scan.COMPONENT_ID.hexToBytes()))
        val check = LeAudioCheck.parse("03".hexToBytes())!!
        assertEquals(LeAudioCheckResult(supported = true, preferred = true, prohibited = false), check)
    }

    @Test
    fun `device management 4_4 connected mask, 4_9 app address, 4_18`() {
        val list = PairedDeviceList.parse(Scan.LIST_DEVICES.hexToBytes())!!
        assertEquals(setOf("10:B1:DF:00:00:03", "2C:DA:46:00:00:02"), list.connectedMacs)
        val one = PairedDeviceList.parse(Fw1012.PAIRED_LIST.hexToBytes())!! // header 01
        assertTrue(one.isConnected("2c:da:46:00:00:02"))
        assertFalse(one.isConnected("10:B1:DF:00:00:03"))
        assertEquals("2C:DA:46:00:00:02", AppAddress.parse(Scan.APP_ADDRESS.hexToBytes()))
        assertEquals(AvailableToConnectCode.AVAILABLE, AvailableToConnect.parse(Scan.AVAILABLE_TO_CONNECT.hexToBytes()))
        // [4.5] flags 03 on the phone running Earshot: connected + isLocalDevice
        val phone = DeviceInfoQuery.parse(Fw1012.DEVICE_INFO_PHONE.hexToBytes())!!
        assertTrue(phone.isConnected && phone.isLocalDevice && !phone.isBoseProduct)
        assertFalse(DeviceInfoQuery.parse(Fw1012.DEVICE_INFO_ABSENT.hexToBytes())!!.isLocalDevice)
    }

    @Test
    fun `function names come from the Bose app table`() {
        assertEquals("AppAddress", FunctionNames.functionName(QcUltra2.APP_ADDRESS))
        assertEquals("UnencryptedProductIrk", FunctionNames.functionName(QcUltra2.AUTH_UNENCRYPTED_PRODUCT_IRK))
        assertEquals("ChargerDetect", FunctionNames.functionName(QcUltra2.CHARGER_DETECT))
        assertEquals("ComponentId", FunctionNames.functionName(QcUltra2.COMPONENT_ID))
        assertEquals("LeAudioControl", FunctionNames.functionName(QcUltra2.LE_AUDIO_CONTROL))
        assertEquals("Latency", FunctionNames.functionName(QcUltra2.LATENCY))
        assertEquals("DataCollection", FunctionNames.blockName(13))
        assertEquals("Experimental", FunctionNames.blockName(19))
        assertEquals("Print", FunctionNames.blockName(22))
        assertEquals("Bind", FunctionNames.functionName(QcUltra2.PRINT_BIND))
        for (b in 23..26) assertEquals("Unknown (error 24)", FunctionNames.blockName(b))
        assertEquals("FblockInfo", FunctionNames.functionName(6, 0))
        assertEquals(BmapError.SECURE_SESSION_REQUIRED, BmapError.fromCode(24))
        assertTrue("guess" in BmapError.SECURE_SESSION_REQUIRED.description)
    }
}
