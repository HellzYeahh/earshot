package dev.earshot.protocol.qcultra2

import dev.earshot.protocol.FunctionAddress

/**
 * Catalogue for the Bose QuietComfort Ultra Headphones (2nd Gen), codename "wolverine".
 *
 * Every (block, function) address Earshot talks to, as a named constant, plus the transport
 * identifiers. Byte layouts live next to their parsers (see `Settings.kt`, `AudioModes.kt`,
 * `DeviceManagement.kt`, ...). Human-readable labels for *every* function the Bose app 13.3.4
 * knows are in [FunctionNames]. Names follow the Bose app (`docs/bmap-from-bose-app-13.3.4.md`).
 *
 * Operator model (Bose app 13.3.4, confirmed by the fw 10.12 scan): GET works everywhere; every
 * write the app makes is SET_GET (op 2) or START (op 5); plain SET answers error 5, which means
 * "this operator is not implemented for this function" — there is no login or handshake gate on
 * any BMAP write the app performs.
 */
object QcUltra2 {
    const val NAME = "Bose QuietComfort Ultra Headphones (2nd Gen)"
    const val CODENAME = "wolverine"
    const val PLATFORM = "OTG-QCC-384"
    const val PRODUCT_ID = 0x4082
    const val VARIANT = 0x01
    const val BOSE_USB_VENDOR_ID = 0x05A7

    /** bosectl's catalogue says this sibling (firmware 8.x, same MAC prefix) uses the same layout. */
    const val PRODUCT_ID_QC_HEADPHONES_2ND_GEN = 0x4083

    /** QC Ultra Earbuds (2nd Gen), codename "edith": same feature layout, multi-record battery. */
    const val PRODUCT_ID_EARBUDS = 0x4062

    /** BMAP control is on RFCOMM channel 2. Channel 1 refuses, 14 is a beacon, 22 a log dump. */
    const val RFCOMM_CHANNEL = 2

    /** Standard Serial Port Profile UUID; the Bose app's SppConnectionManager uses it. */
    const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

    /** Bose's own BMAP service UUID from the SDP record. SDP may resolve it to the wrong channel. */
    const val BMAP_UUID = "00000000-deca-fade-deca-deafdecacaff"

    /** `[0.1]` on firmware 10.12.12. The Bose app treats `< 1.2.0` as "assume every AudioManagement feature". */
    const val BMAP_VERSION_FW_10_12 = "1.2.0"

    // ── ProductInfo [0.x] ────────────────────────────────────────────────────────────────
    val PRODUCT_INFO_FBLOCK_INFO = FunctionAddress(0, 0)   // "1.1.0"
    val BMAP_VERSION = FunctionAddress(0, 1)               // "1.2.0" on fw 10.12
    val ALL_FUNCTION_BLOCKS = FunctionAddress(0, 2)        // big-endian block bitset (AllFblocks)
    val PRODUCT_ID_VARIANT = FunctionAddress(0, 3)         // 40 82 01
    val FIRMWARE_VERSION = FunctionAddress(0, 5)           // "10.12.12+ga927c9a"
    val MAC_ADDRESS = FunctionAddress(0, 6)                // the headphones' own BT address
    val SERIAL_NUMBER = FunctionAddress(0, 7)
    val GUID = FunctionAddress(0, 12)
    val PRODUCT_NAME = FunctionAddress(0, 15)              // OriginalProductName "Bose QC Ultra 2 HP"
    val COMPONENT_ID = FunctionAddress(0, 17)              // [componentId][MAC]
    val LE_AUDIO_SUPPORTED = FunctionAddress(0, 23)        // [1] on fw 10.12

    // ── Settings [1.x] — GET + SET_GET ───────────────────────────────────────────────────
    val SETTINGS_FBLOCK_INFO = FunctionAddress(1, 0)
    val CUSTOM_NAME = FunctionAddress(1, 2)                // ProductName. GET: [flag, utf8...]; SET_GET: utf8 only
    val VOICE_PROMPTS = FunctionAddress(1, 3)
    val STANDBY_TIMER = FunctionAddress(1, 4)              // absent on fw 10.12
    val CNC = FunctionAddress(1, 5)                        // GET only here (SET_GET → error 5); write via [31.10]
    val EQ = FunctionAddress(1, 7)                         // RangeControl
    val BUTTONS = FunctionAddress(1, 9)
    val MULTIPOINT = FunctionAddress(1, 10)
    val SIDETONE = FunctionAddress(1, 11)
    val SETUP_COMPLETE = FunctionAddress(1, 12)
    val AUTO_PLAY_PAUSE = FunctionAddress(1, 24)
    val AUTO_ANSWER = FunctionAddress(1, 27)
    val DISABLE_CAPTOUCH = FunctionAddress(1, 34)          // [1] = touch controls off (fw 10.12 reads 01)
    val LE_AUDIO_CONTROL = FunctionAddress(1, 37)          // [0|1]; writing it restarts the headphones

    // ── Status [2.x] ─────────────────────────────────────────────────────────────────────
    val BATTERY = FunctionAddress(2, 2)
    /** `[2.3]` is AuxCableDetection in the Bose app; FuncNotSupp on wolverine fw 10.12. */
    val AUX_CABLE_DETECTION = FunctionAddress(2, 3)
    /** Quiet Rebellion called `[2.3]` "charging state"; the Bose app says it is the aux cable. Use [CHARGER_DETECT]. */
    @Deprecated("[2.3] is AuxCableDetection; charging is [2.5] ChargerDetect", ReplaceWith("CHARGER_DETECT"))
    val CHARGING_STATE = AUX_CABLE_DETECTION
    val CHARGER_DETECT = FunctionAddress(2, 5)             // [1] = on the charger
    val STATUS_BUTTON = FunctionAddress(2, 12)             // button events (pushed), [1.9] layout
    val OPERATION_MODE = FunctionAddress(2, 16)            // 0 Unknown, 1 Normal, 2 OtaOnly
    val MIC_MUTE = FunctionAddress(2, 21)                  // [1] = true

    // ── DeviceManagement [4.x] ───────────────────────────────────────────────────────────
    val DEVICE_MGMT_FBLOCK_INFO = FunctionAddress(4, 0)
    val DM_CONNECT = FunctionAddress(4, 1)
    val DM_DISCONNECT = FunctionAddress(4, 2)
    val DM_REMOVE_DEVICE = FunctionAddress(4, 3)
    val PAIRED_DEVICES = FunctionAddress(4, 4)             // ListDevices: [connected bitmask, mac×N]
    val DEVICE_INFO = FunctionAddress(4, 5)                // Info: GET with a 6-byte MAC payload
    val DEVICE_EXTENDED_INFO = FunctionAddress(4, 6)       // ExtendedInfo: GET with a 6-byte MAC payload
    val DM_CLEAR_DEVICE_LIST = FunctionAddress(4, 7)
    val PAIRING_MODE = FunctionAddress(4, 8)               // START [1]=enter, [0]=leave
    /** AppAddress: the phone's MAC as the headphones see it (NOTES.md called it "local MAC"). */
    val APP_ADDRESS = FunctionAddress(4, 9)
    @Deprecated("Bose app name is AppAddress", ReplaceWith("APP_ADDRESS"))
    val LOCAL_MAC_ADDRESS = APP_ADDRESS
    /** START [0x82 + mac]. GET answers FuncNotSupp on fw 10.12 (START untested there). */
    val ROUTING = FunctionAddress(4, 12)
    val DM_FEATURES = FunctionAddress(4, 14)               // bit0 cTKD, bit1 carousel, bit2 barge-in
    val AVAILABLE_TO_CONNECT = FunctionAddress(4, 18)      // 0 NotAvailable, 1 Available, 2 Unknown
    val LE_AUDIO_CHECK = FunctionAddress(4, 19)            // START → [supported, preferred, prohibited bits]

    // ── AudioManagement [5.x] ────────────────────────────────────────────────────────────
    val AUDIO_SOURCE = FunctionAddress(5, 1)               // GET only
    val AUDIO_CONTROL = FunctionAddress(5, 3)              // GET → supported bitset; START [TransportControl]
    val AUDIO_STATUS = FunctionAddress(5, 4)               // [AudioStatus][trackPosition s16]
    val VOLUME = FunctionAddress(5, 5)                     // [max][current]; SET_GET [level]
    val NOW_PLAYING = FunctionAddress(5, 6)                // START → streamed attributes (layout not documented)
    val LATENCY = FunctionAddress(5, 7)                    // [min s16][max s16][current s16]; SET_GET [s16]

    // ── Control [7.x] ────────────────────────────────────────────────────────────────────
    val CONTROL_GET_ALL = FunctionAddress(7, 1)            // GET → function bitset (0873)
    val POWER = FunctionAddress(7, 4)                      // START [0]=off, [1]=on

    // ── Notification [9.x] ───────────────────────────────────────────────────────────────
    val NOTIFICATION_RESET = FunctionAddress(9, 1)
    val NOTIFY_BY_FBLOCK = FunctionAddress(9, 2)           // GET → block bitset; SET_GET [op][bitset]
    val NOTIFY_BY_FUNCTION = FunctionAddress(9, 3)

    // ── DataCollection [13.x] ────────────────────────────────────────────────────────────
    val DATA_COLLECTION_GET_ALL = FunctionAddress(13, 1)   // GET → function bitset (ff83)

    // ── Authentication [18.x] — read-only for us ─────────────────────────────────────────
    val AUTH_CAPABILITIES = FunctionAddress(18, 1)         // GetAll: function bitset 0339083e07
    val AUTH_DEVICE_PUBLIC_KEY = FunctionAddress(18, 9)    // P-384 PEM
    val AUTH_OTP_KEY_TYPE = FunctionAddress(18, 11)
    val AUTH_PRODUCT_NAME = FunctionAddress(18, 12)        // "wolverine"
    val AUTH_PLATFORM_NAME = FunctionAddress(18, 13)       // "OTG-QCC-384"
    val AUTH_IDENTITY_KEYPAIR = FunctionAddress(18, 19)
    val AUTH_UNENCRYPTED_PRODUCT_IRK = FunctionAddress(18, 24)
    @Deprecated("[18.24] is UnencryptedProductIrk", ReplaceWith("AUTH_UNENCRYPTED_PRODUCT_IRK"))
    val AUTH_PRODUCT_IRK = AUTH_UNENCRYPTED_PRODUCT_IRK

    // ── Print [22.x] — firmware debug console ────────────────────────────────────────────
    val PRINT_OUTPUT = FunctionAddress(22, 2)              // START → RESULT [u8]
    val PRINT_BIND = FunctionAddress(22, 3)                // SET_GET UTF-8
    val PRINT_UNBIND = FunctionAddress(22, 4)              // SET_GET UTF-8
    val PRINT_VERBOSITY = FunctionAddress(22, 5)           // SET_GET UTF-8

    // ── AudioModes [31.x] — SET_GET and START ────────────────────────────────────────────
    val AUDIO_MODES_FBLOCK_INFO = FunctionAddress(31, 0)
    val MODES_GET_ALL = FunctionAddress(31, 1)             // START → PROCESSING + state dump
    val MODES_CAPABILITIES = FunctionAddress(31, 2)
    val CURRENT_MODE = FunctionAddress(31, 3)              // GET → [index]; START [index, announce]
    val DEFAULT_MODE = FunctionAddress(31, 4)              // absent on fw 10.12
    val MODE_CONFIG = FunctionAddress(31, 6)               // GET [index]; START → 48-byte STATUS per slot; SET_GET 40 bytes
    val FAVORITES = FunctionAddress(31, 8)                 // [totalModes, BE bitset]
    val MODES_RESET = FunctionAddress(31, 9)               // START [index] → resets one slot
    val AUDIO_SETTINGS = FunctionAddress(31, 10)           // live [cnc, autoCnc, spatial, wind, anc]

    // ── Mode slots ───────────────────────────────────────────────────────────────────────
    const val MODE_SLOT_COUNT = 11

    /** Firmware presets; SET_GET on these answers Runtime error 8. */
    val PRESET_MODE_SLOTS = 0..3

    /**
     * Slots that accept the 40-byte `[31.6]` SET_GET. Slot 4 ("Home" on the reference unit)
     * was user-created in the Bose app, so it is editable like 5..10; whether a given slot is
     * really writable is in [ModeConfig.editable].
     */
    val EDITABLE_MODE_SLOTS = 4..10

    val PRESET_MODE_NAMES: Map<Int, String> = mapOf(0 to "Quiet", 1 to "Aware", 2 to "Immersion", 3 to "Cinema")

    /** Name bosectl writes into a slot to free it; `isEmptySlot` treats it as unused. */
    const val EMPTY_SLOT_NAME = "None"

    /** `[31.3]` GET returns this when the device is not on any saved slot (manual tweak). */
    const val NO_SAVED_MODE = 0xFF

    const val CNC_WIRE_MAX = 10
    const val EQ_MIN = -10
    const val EQ_MAX = 10
    const val MODE_NAME_FIELD_BYTES = 32
    const val MODE_NAME_MAX_BYTES = 31
    /** bosectl applies the same limit to the custom device name `[1.2]`. */
    const val CUSTOM_NAME_MAX_BYTES = 31

    /** `[5.5]` maximum on fw 10.12 (scan `1f16` = 22 of 31). The frame carries its own max; use that. */
    const val VOLUME_MAX_FW_10_12 = 31
}
