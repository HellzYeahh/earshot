package dev.earshot.virtual

import dev.earshot.protocol.BmapError
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.qcultra2.AudioSettings
import dev.earshot.protocol.qcultra2.Bitset
import dev.earshot.protocol.qcultra2.FavoritesCodec
import dev.earshot.protocol.qcultra2.Mac
import dev.earshot.protocol.qcultra2.ModeConfigs
import dev.earshot.protocol.qcultra2.ModeLayout
import dev.earshot.protocol.qcultra2.ModesCapabilities
import dev.earshot.protocol.qcultra2.Notifications
import dev.earshot.protocol.qcultra2.PlaybackStatus
import dev.earshot.protocol.qcultra2.QcUltra2
import dev.earshot.protocol.qcultra2.SidetoneLevel
import dev.earshot.protocol.qcultra2.SpatialMode
import dev.earshot.protocol.qcultra2.StandbyTimer
import dev.earshot.protocol.qcultra2.TransportControl

/**
 * A virtual Bose QC Ultra Headphones (2nd Gen) that answers BMAP on the *device* side.
 *
 * Pure Kotlin, no Android, no threads: [handle] turns one request into the frames the firmware
 * would write back, [tick] produces the unsolicited frames a real unit pushes on its own
 * (battery, mode changes), [greeting] is the burst some firmware sends right after connect.
 * [VirtualTransport] wraps it in a [dev.earshot.bt.ByteTransport] with realistic timing so the
 * whole app stack runs on an emulator; JVM tests drive [handle] directly.
 *
 * Modelled on the user's unit, firmware 10.12.12 (`docs/reference/captures-galaxy/deep-scan-
 * fw10.12-2026-10-17.txt`) with the layouts of `docs/bmap-from-bose-app-13.3.4.md`; older facts
 * from `docs/reference/bosectl/NOTES.md`. Where the sources are silent the behaviour is a guess
 * and the comment says so, so a later capture can correct it in one place.
 *
 * Operator model: GET works everywhere; SET_GET works on the settings the Bose app writes; START
 * on the actions; plain SET answers OpNotSupp (5) everywhere (the operator is simply not
 * implemented); an unknown function answers FuncNotSupp (4), an unknown block FblockNotSupp (3)
 * and the debug-ish blocks' FblockInfo the undocumented error 24.
 *
 * Pushes: like the real unit (`[9.2]` reads `00000000` after connect) nothing is pushed until the
 * app subscribes with `[9.2]` SET_GET; then every change in a subscribed block is pushed as a
 * STATUS, and the battery also on a timer.
 *
 * Thread-safety: every public method is synchronised, so the transport may call [handle] and
 * [tick] from different coroutines.
 *
 * Other models: [profile] switches the identity, the `[0.2]` blocks, the mode slots and their
 * `[31.6]` length, `[31.2]`, `[31.10]` / `[1.5]` behaviour, the battery records and `[1.4]`
 * (see [VirtualProfile]); the default is the reference unit.
 *
 * @param clock millisecond clock; tests pass a manual one so battery drain is deterministic.
 */
class VirtualQcUltra2(
    private val clock: () -> Long = System::currentTimeMillis,
    batteryPercent: Int = 72,
    /** The battery loses one percent every this many milliseconds; slow enough that a long demo
     *  session still looks healthy and never trips the low-battery alert by itself. */
    private val batteryDrainIntervalMs: Long = 15 * 60_000L,
    /** Unsolicited `[2.2]` STATUS cadence once block 2 is subscribed; the real unit pushes on change, this one also on a timer. */
    private val batteryPushIntervalMs: Long = 30_000L,
    /** How long after a `[31.3]` START / `[31.10]` write the follow-up push appears. */
    private val pushDelayMs: Long = 150L,
    /** Which product this unit plays. */
    val profile: VirtualProfile = VirtualProfile.QC_ULTRA_2,
) {
    // ── Identity (the reference unit, with a made-up tail where it was masked) ───────────
    var firmwareVersion = profile.firmwareVersion
    var serialNumber = "085958T53330200AB"
    var productName = profile.productName
    var customName = profile.customName
    var macAddress = "68:F2:1F:12:34:56"
    /** `[0.3]`; tests set another model's id (0x4066 for the QC Ultra 1st Gen, 0x4024 for the NC700). */
    var productId = profile.productId
    val variant = QcUltra2.VARIANT

    // ── Mode slots of this profile ───────────────────────────────────────────────────────
    /** Slots `0 until slotCount`: the presets first, then the custom slots. */
    val slotCount: Int = profile.slotCount
    private val presetRange: IntRange = 0 until profile.presets.size
    private val editableRange: IntRange = profile.presets.size until slotCount
    private val layout = profile.layout

    /** `[1.4]` auto-off minutes; null = absent on this firmware. */
    var standbyMinutes: Int? = profile.standbyMinutes
        private set

    /** The case's last known charge (earbuds), not drained. */
    var caseBatteryPercent: Int = 80

    // ── Older-firmware quirks (all off: the reference unit has none of them) ─────────────
    /**
     * Functions this firmware ignores: no frame at all, not even an ERROR. Older firmware is
     * suspected to do this for functions it lacks, which used to look like a dead link.
     */
    var silentFunctions: Set<FunctionAddress> = emptySet()

    /**
     * Every request in block 31 answers FblockNotSupp, GET included, while `[31.3]` is still
     * pushed on connect (the greeting): what bosectl #44 saw on one unit (BT Modalias 0x4024,
     * NC700 "goodyear", fw 2.0.4). `[1.5]` CNC GET keeps answering there.
     */
    var refuseAudioModesBlock = false

    /**
     * No `[31.10]` (FuncNotSupp) while the slots stay 48-byte: the noise settings live only in the
     * mode slots. The QC Ultra 1st Gen guess that the app must survive if it is wrong.
     */
    var withoutAudioSettings = !profile.liveAudioSettings

    private val requestCounts = HashMap<FunctionAddress, Int>()

    /** How many requests reached [address] (any operator, answered or not). Bounded by the address count. */
    @Synchronized
    fun requestsTo(address: FunctionAddress): Int = requestCounts[address] ?: 0

    /** Requests that reached [block]. */
    @Synchronized
    fun requestsToBlock(block: Int): Int = requestCounts.filterKeys { it.block == block }.values.sum()

    // ── Power / link ─────────────────────────────────────────────────────────────────────
    /** False after `[7.4]` START `[0]` or while restarting after `[1.37]`; the transport drops the link when it sees this. */
    var isPoweredOn: Boolean = true
        private set

    /** Turn the unit back on (what the user does by hand after a power-off demo). */
    @Synchronized
    fun powerOn() {
        isPoweredOn = true
    }

    // ── Battery / status [2.x] ───────────────────────────────────────────────────────────
    var batteryPercent: Int = batteryPercent.coerceIn(0, 100)
        private set
    private var lastDrainAt = clock()
    private var lastBatteryPushAt = clock()
    /** `[2.5]` ChargerDetect; scan `00`. */
    var onCharger = false
        private set
    /** `[2.21]` MicMute; scan `01`. */
    var micMuted = true
        private set

    // ── Settings block [1.x] ─────────────────────────────────────────────────────────────
    /** `[1.7]` bass, mid, treble, each -10..10 (capture: 0, -2, -6). */
    val eq = intArrayOf(0, -2, -6)
    /** `[1.11]` capture `01 02 0f`: persistent, medium, supported-levels mask. */
    var sidetonePersist = true
    var sidetoneLevel = SidetoneLevel.MEDIUM.code
    /** `[1.10]` capture `07`; bit 0x02 is "multipoint on", the other bits are unknown and kept. */
    var multipointByte = 0x07
    var autoPlayPause = true
    var autoAnswer = true
    /** `[1.3]` capture `41 00 00 81 02 00 00`; SET_GET rewrites byte 0 only. */
    val voicePrompts = byteArrayOf(0x41, 0x00, 0x00, 0x81.toByte(), 0x02, 0x00, 0x00)
    /**
     * `[1.9]` records, one STATUS frame each. Default: the capture `80 09 0e 00 09 40 02`
     * (Shortcut button, long press, Disabled); [VirtualProfile.buttonRecords] gives earbuds one per bud.
     */
    val buttonRecords: List<ByteArray> = profile.buttonRecords?.map { hex(it) } ?: listOf(byteArrayOf(0x80.toByte(), 0x09, 0x0e, 0x00, 0x09, 0x40, 0x02))
    /** The first `[1.9]` record (the one single-button products have). */
    val shortcutButton: ByteArray get() = buttonRecords.first()
    /** `[1.34]`; the wire bit is 1 when touch controls are on (scan `01`, verified on hardware 2026-10-18). */
    var touchEnabled = true
    /** `[1.37]` LeAudioControl; scan `01` = on. */
    var leAudioEnabled = true
        private set

    // ── AudioManagement [5.x] ────────────────────────────────────────────────────────────
    /** `[5.4]` scan `01 ffff`: playing, position unknown. */
    var playback: PlaybackStatus = PlaybackStatus.PLAYING
        private set
    /** `[5.5]` scan `1f 16`. */
    val volumeMax = QcUltra2.VOLUME_MAX_FW_10_12
    var volume = 22
        private set
    /** `[5.7]` scan `0000 0208 00d2`. */
    val latencyMin = 0
    val latencyMax = 520
    var latency = 210
        private set

    // ── Notifications [9.2] ──────────────────────────────────────────────────────────────
    /** Blocks the app subscribed to; empty after every connect, as on the real unit. */
    var notifyBlocks: Set<Int> = emptySet()
        private set

    // ── AudioModes block [31.x] ──────────────────────────────────────────────────────────
    /** Live `[31.10]`; starts as Quiet (wire cnc 0 = most cancelling, ANC on). */
    var live = AudioSettings(cncWire = 0, spatialCode = 0, windBlock = false, ancEnabled = true)
        private set
    /** `[31.3]`; [QcUltra2.NO_SAVED_MODE] once the live settings diverge from every slot. */
    var currentMode: Int = 0
        private set
    var defaultMode: Int = 0
    /** `[31.8]` favourites Quiet and Aware; stored as a slot set, written as the 2-byte big-endian mask. */
    var favorites: Set<Int> = setOf(0, 1)
    /**
     * The slots, each kept in the full 48-byte STATUS layout; the wire gets the profile's prefix of
     * it ([slotFrame]: 44 / 45 / 47 / 48 bytes, every shorter layout is a prefix of the longest).
     */
    val slots: Array<ByteArray> = Array(slotCount) { ByteArray(ModeConfigs.STATUS_SIZE) }

    /** Slot [index] as this profile's firmware sends it. */
    private fun slotFrame(index: Int): ByteArray = slots[index].copyOf(layout.statusSize)

    // ── Devices [4.x] / [5.x] ────────────────────────────────────────────────────────────
    data class Peer(val mac: String, val name: String)

    /** `[4.4]` capture lists two addresses while both were connected; byte 0 was `03`. */
    val pairedDevices = mutableListOf(
        Peer("C4:0F:08:11:22:33", "Pixel 9"),
        Peer("94:45:60:85:05:A7", "ThinkPad"),
    )
    /** The phone running the app ([4.9] AppAddress); the first peer. */
    val appAddress: String get() = pairedDevices.first().mac
    var activeSourceMac: String? = pairedDevices[0].mac
    var pairingMode = false
    /**
     * The firmware's routing table is only populated while the other device streams, so the
     * first `[4.12]` START answers FuncNotSupp. Here the first attempt "wakes" the table, as if
     * the user hit play on the other device, and the retry succeeds.
     */
    var routingTableReady = false

    // ── Deferred pushes ──────────────────────────────────────────────────────────────────
    private data class Deferred(val dueAt: Long, val frame: BmapPacket)
    private val deferred = ArrayList<Deferred>()

    init {
        resetSlots()
    }

    /**
     * The profile's presets locked, its saved custom modes, the other custom slots empty "None". On
     * the reference unit: presets 0-3, slot 4 "Home" user-created, 5-10 empty.
     */
    @Synchronized
    fun resetSlots() {
        // Flag byte 5 and word 40-41 are the notes' "?" fields; values are what the raw dump showed.
        profile.presets.forEachIndexed { i, p ->
            writeSlot(i, p.prompt.code, editable = false, configured = false, flag5 = p.flag5, word40 = p.word40 ?: 0,
                cnc = p.cncWire, spatial = p.spatial, wind = p.wind, anc = p.anc, name = p.name)
        }
        for (i in editableRange) {
            val m = profile.savedModes[i]
            if (m == null) emptySlot(i)
            else writeSlot(i, m.prompt.code, editable = true, configured = true, flag5 = m.flag5, word40 = m.word40 ?: USER_SLOT_WORD40,
                cnc = m.cncWire, spatial = m.spatial, wind = m.wind, anc = m.anc, name = m.name)
        }
    }

    /** What `[31.9]` and bosectl's clear leave behind; matches the fw 10.12 empty-slot tail `1d 0a 00 00 01 00 01`. */
    private fun emptySlot(index: Int) =
        writeSlot(index, 0, editable = true, configured = false, flag5 = 0, word40 = USER_SLOT_WORD40, cnc = 10, spatial = 0, wind = false, anc = true, name = QcUltra2.EMPTY_SLOT_NAME)

    private fun writeSlot(
        index: Int, promptCode: Int, editable: Boolean, configured: Boolean, flag5: Int, word40: Int,
        cnc: Int, spatial: Int, wind: Boolean, anc: Boolean, name: String,
    ) {
        val p = ByteArray(ModeConfigs.STATUS_SIZE)
        p[0] = index.toByte()
        p[1] = 0
        p[2] = promptCode.toByte()
        p[3] = if (editable) 1 else 0
        p[4] = if (configured) 1 else 0
        p[5] = flag5.toByte()
        ModeConfigs.encodeName(ModeConfigs.truncateName(name)).copyInto(p, 6)
        p[38] = 0; p[39] = 0
        // Byte 41 = changeable-field flags (0x1d on user slots), byte 40 is 0 in every dump.
        p[40] = (word40 shr 8).toByte(); p[41] = word40.toByte()
        p[42] = cnc.toByte()
        p[43] = 0 // autoCnc never sticks
        p[44] = spatial.toByte()
        // Byte 45 is 1 on every user slot of the fw 10.12 dump whatever the wind state; wind is 46.
        p[ModeConfigs.FLAGS45_OFFSET] = if (editable) 1 else 0
        p[ModeConfigs.WIND_OFFSET] = if (wind) 1 else 0
        p[47] = if (anc) 1 else 0
        slots[index] = p
    }

    private fun slotSettings(index: Int): AudioSettings {
        val p = slots[index]
        return AudioSettings(cncWire = p[42].toInt() and 0xFF, spatialCode = p[44].toInt() and 0xFF,
            windBlock = p[ModeConfigs.WIND_OFFSET] != 0.toByte(), ancEnabled = p[47] != 0.toByte())
    }

    /** Name of a slot as the dump reports it (for tests and the demo UI). */
    fun slotName(index: Int): String = ModeConfigs.parse(slots[index])?.name ?: ""

    // ── Things the user does on the headphones themselves (tests and demo hooks) ─────────

    /** Long-press the Shortcut button: runs its action (spatial audio toggle here) and pushes `[2.12]`. */
    @Synchronized
    fun pressShortcutButton() {
        push(status(QcUltra2.STATUS_BUTTON, shortcutButton.copyOfRange(0, 3)))
        if (shortcutButton[2].toInt() == 19) {
            applyLive(live.copy(spatialCode = if (live.spatialCode == 0) SpatialMode.ROOM.code else 0))
            if (!withoutAudioSettings) push(status(QcUltra2.AUDIO_SETTINGS, AudioSettings.build(live)))
        }
    }

    /** Put the headphones on / take them off the charger. */
    @Synchronized
    fun setOnCharger(on: Boolean) {
        onCharger = on
        push(status(QcUltra2.CHARGER_DETECT, byteArrayOf(if (on) 1 else 0)))
    }

    /** Toggle the mic-mute status. */
    @Synchronized
    fun setMicMuted(muted: Boolean) {
        micMuted = muted
        push(status(QcUltra2.MIC_MUTE, byteArrayOf(if (muted) 1 else 0)))
    }

    /**
     * Press the button of the `[1.9]` record [buttonId] (the first record by default): pushes
     * `[2.12]` with that record's id, event and action. For demo detection and the tests.
     */
    @Synchronized
    fun pressButton(buttonId: Int = shortcutButton[0].toInt() and 0xFF) {
        val record = buttonRecords.firstOrNull { (it[0].toInt() and 0xFF) == buttonId } ?: return
        if (record === shortcutButton) { pressShortcutButton(); return }
        push(status(QcUltra2.STATUS_BUTTON, record.copyOfRange(0, 3)))
    }

    /** Press play/pause on the headphones: the play state flips and `[5.4]` is pushed (the phone's player would follow over AVRCP). */
    @Synchronized
    fun pressPlayPause() {
        playback = if (playback == PlaybackStatus.PLAYING) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING
        push(status(QcUltra2.AUDIO_STATUS, byteArrayOf(playback.code.toByte(), 0xFF.toByte(), 0xFF.toByte())))
    }

    /** Swipe the touch strip: volume changes and is pushed. */
    @Synchronized
    fun swipeVolume(delta: Int) {
        volume = (volume + delta).coerceIn(0, volumeMax)
        push(status(QcUltra2.VOLUME, volumePayload()))
    }

    // ── Greeting and pushes ──────────────────────────────────────────────────────────────

    /**
     * A new RFCOMM session started: forget the previous session's `[9.2]` subscription and any
     * pushes still queued for it (the real unit reads `00000000` after every connect).
     */
    @Synchronized
    fun onSessionStart() {
        notifyBlocks = emptySet()
        deferred.clear()
        lastSession = java.lang.ref.WeakReference(this)
    }

    /**
     * What the unit may write right after the socket opens: a battery STATUS and the current
     * mode. Two valid STATUS frames, concatenated like one RFCOMM read.
     */
    @Synchronized
    fun greeting(): ByteArray {
        val battery = status(QcUltra2.BATTERY, batteryPayload()).encode()
        // No AudioModes block, no mode in the greeting (the #44 unit lists it and still pushes [31.3]).
        return if (AUDIO_MODES_BLOCK !in profile.blocks) battery
        else battery + status(QcUltra2.CURRENT_MODE, byteArrayOf(currentMode.toByte())).encode()
    }

    /**
     * Unsolicited frames that are due now: deferred pushes queued by [handle] (the `[31.10]`
     * STATUS after a mode switch, the `[31.3]` 0xFF after a manual tweak, …) and the periodic
     * battery STATUS, each only for blocks subscribed through `[9.2]`. Call it every few hundred
     * milliseconds. Returns an empty list while off.
     */
    @Synchronized
    fun tick(): List<BmapPacket> {
        if (!isPoweredOn) return emptyList()
        val now = clock()
        val out = ArrayList<BmapPacket>()
        while (now - lastDrainAt >= batteryDrainIntervalMs && batteryPercent > BATTERY_FLOOR_PERCENT) {
            batteryPercent--
            lastDrainAt += batteryDrainIntervalMs
        }
        if (now - lastBatteryPushAt >= batteryPushIntervalMs) {
            lastBatteryPushAt = now
            if (QcUltra2.BATTERY.block in notifyBlocks) out += status(QcUltra2.BATTERY, batteryPayload())
        }
        val it = deferred.iterator()
        while (it.hasNext()) {
            val d = it.next()
            if (d.dueAt <= now) { out += d.frame; it.remove() }
        }
        return out
    }

    /** Pending deferred pushes regardless of their due time (tests use it instead of waiting). */
    @Synchronized
    fun drainDeferred(): List<BmapPacket> = deferred.map { it.frame }.also { deferred.clear() }

    /** Queue [frame] as a push, if its block is subscribed. */
    private fun push(frame: BmapPacket) {
        if (frame.block !in notifyBlocks) return
        deferred += Deferred(clock() + pushDelayMs, frame)
    }

    // ── Request handling ─────────────────────────────────────────────────────────────────

    /**
     * The frames the firmware writes back for [request], in order, PROCESSING first where the
     * real unit sends one. An empty list means "no reply at all" (the `[31.4]` SET_GET that
     * timed out in bosectl's tests). While powered off nothing answers.
     */
    @Synchronized
    fun handle(request: BmapPacket): List<BmapPacket> {
        if (!isPoweredOn) return emptyList()
        val a = request.address
        requestCounts[a] = (requestCounts[a] ?: 0) + 1
        if (a in silentFunctions) return emptyList()
        if (refuseAudioModesBlock && a.block == AUDIO_MODES_BLOCK) return listOf(error(a, BmapError.FBLOCK_NOT_SUPPORTED))
        if (withoutAudioSettings && a == QcUltra2.AUDIO_SETTINGS) return listOf(error(a, BmapError.FUNC_NOT_SUPPORTED))
        if (a.block !in profile.blocks) return listOf(error(a, BmapError.FBLOCK_NOT_SUPPORTED))
        if (a in ERROR_24_FUNCTIONS) return listOf(error(a, BmapError.SECURE_SESSION_REQUIRED))
        if (!exists(a)) return listOf(error(a, BmapError.FUNC_NOT_SUPPORTED))
        return when (request.operator) {
            // Several records (earbuds): one STATUS per record, the way a per-bud reply would arrive.
            BmapOperator.GET -> if (a == QcUltra2.BUTTONS) buttonRecords.map { status(a, it.copyOf()) } else listOf(get(a, request.payload))
            BmapOperator.SET_GET -> setGet(a, request.payload)
            BmapOperator.START -> start(a, request.payload)
            // Plain SET is not implemented for any function the app uses (error 5).
            BmapOperator.SET -> listOf(error(a, BmapError.OP_NOT_SUPPORTED))
            // A device-side operator arriving from the phone: real firmware ignores it. Guess.
            else -> emptyList()
        }
    }

    /** The function exists on this profile's firmware (otherwise FuncNotSupp). */
    private fun exists(a: FunctionAddress): Boolean = when {
        // Bose Connect era: Earshot asks only ProductInfo and the battery there.
        profile.legacyOnly -> (a.block == 0 && a in KNOWN_FUNCTIONS) || a == QcUltra2.BATTERY
        a in profile.absent -> false
        a == QcUltra2.STANDBY_TIMER -> standbyMinutes != null
        else -> a in KNOWN_FUNCTIONS
    }

    // ── GET ──────────────────────────────────────────────────────────────────────────────

    private fun get(a: FunctionAddress, payload: ByteArray): BmapPacket = when (a) {
        // ProductInfo [0.x] — fw 10.12 scan values
        FunctionAddress(0, 0) -> text(a, "1.1.0")
        QcUltra2.BMAP_VERSION -> text(a, QcUltra2.BMAP_VERSION_FW_10_12)
        QcUltra2.ALL_FUNCTION_BLOCKS -> status(a, Bitset.encode(profile.blocks, 4)) // fw 10.12: 87cc23ff
        QcUltra2.PRODUCT_ID_VARIANT -> status(a, byteArrayOf((productId shr 8).toByte(), productId.toByte(), variant.toByte()))
        QcUltra2.FIRMWARE_VERSION -> text(a, firmwareVersion)
        QcUltra2.MAC_ADDRESS -> status(a, Mac.toBytes(macAddress))
        QcUltra2.SERIAL_NUMBER -> text(a, serialNumber)
        QcUltra2.GUID -> status(a, ByteArray(16) { (it * 17 + 3).toByte() }) // opaque GUID
        QcUltra2.PRODUCT_NAME -> text(a, productName)
        QcUltra2.COMPONENT_ID -> status(a, byteArrayOf(0) + Mac.toBytes(macAddress))
        QcUltra2.LE_AUDIO_SUPPORTED -> status(a, byteArrayOf(1))

        // Settings [1.x]
        QcUltra2.SETTINGS_FBLOCK_INFO -> text(a, "1.1.0")
        QcUltra2.CUSTOM_NAME -> status(a, byteArrayOf(0) + customName.toByteArray())
        QcUltra2.VOICE_PROMPTS -> status(a, voicePrompts.copyOf())
        QcUltra2.STANDBY_TIMER -> status(a, StandbyTimer.buildStatus(standbyMinutes ?: 0, twoByte = true))
        // [1.5] mirrors the slider on the world-volume scale (step 0 = most cancelling, docs/bose-models.md §C3).
        QcUltra2.CNC -> status(a, cncPayload())
        QcUltra2.EQ -> status(a, eqPayload())
        QcUltra2.BUTTONS -> status(a, shortcutButton.copyOf())
        QcUltra2.MULTIPOINT -> status(a, byteArrayOf(multipointByte.toByte()))
        QcUltra2.SIDETONE -> status(a, byteArrayOf(if (sidetonePersist) 1 else 0, sidetoneLevel.toByte(), 0x0f))
        QcUltra2.SETUP_COMPLETE -> status(a, byteArrayOf(1))
        QcUltra2.AUTO_PLAY_PAUSE -> status(a, byteArrayOf(if (autoPlayPause) 1 else 0))
        QcUltra2.AUTO_ANSWER -> status(a, byteArrayOf(if (autoAnswer) 1 else 0))
        QcUltra2.DISABLE_CAPTOUCH -> status(a, byteArrayOf(if (touchEnabled) 1 else 0))
        QcUltra2.LE_AUDIO_CONTROL -> status(a, byteArrayOf(if (leAudioEnabled) 1 else 0))

        // Status [2.x]
        FunctionAddress(2, 0) -> text(a, "1.1.0")
        QcUltra2.BATTERY -> status(a, batteryPayload())
        QcUltra2.CHARGER_DETECT -> status(a, byteArrayOf(if (onCharger) 1 else 0))
        QcUltra2.OPERATION_MODE -> status(a, byteArrayOf(1))
        QcUltra2.MIC_MUTE -> status(a, byteArrayOf(if (micMuted) 1 else 0))

        // FirmwareUpdate [3.x]
        FunctionAddress(3, 0) -> text(a, "1.2.0")
        FunctionAddress(3, 1) -> status(a, byteArrayOf(1))
        FunctionAddress(3, 4) -> status(a, ByteArray(5) + "0.0.0".toByteArray())
        FunctionAddress(3, 6) -> status(a, byteArrayOf(0, 1))
        FunctionAddress(3, 7), FunctionAddress(3, 16) -> status(a, byteArrayOf(0))
        FunctionAddress(3, 15) -> status(a, byteArrayOf(1))

        // DeviceManagement [4.x]
        QcUltra2.DEVICE_MGMT_FBLOCK_INFO -> text(a, "1.1.0")
        QcUltra2.DM_CONNECT -> status(a, byteArrayOf(0, 0, 3))
        QcUltra2.PAIRED_DEVICES -> status(a, pairedListPayload())
        QcUltra2.DEVICE_INFO -> deviceInfo(a, payload)
        QcUltra2.DEVICE_EXTENDED_INFO -> extendedInfo(a, payload)
        QcUltra2.PAIRING_MODE -> status(a, byteArrayOf(if (pairingMode) 1 else 0))
        // [4.9] AppAddress: the phone asking (here always the first peer), not the headphones.
        QcUltra2.APP_ADDRESS -> status(a, Mac.toBytes(appAddress))
        // fw 10.12: the GET answers FuncNotSupp whatever the routing table holds (START still works here).
        QcUltra2.ROUTING -> error(a, BmapError.FUNC_NOT_SUPPORTED)
        QcUltra2.DM_FEATURES, QcUltra2.AVAILABLE_TO_CONNECT -> status(a, byteArrayOf(1))
        QcUltra2.LE_AUDIO_CHECK -> error(a, BmapError.OP_NOT_SUPPORTED) // START-only

        // AudioManagement [5.x]
        FunctionAddress(5, 0) -> text(a, "1.1.0")
        QcUltra2.AUDIO_SOURCE -> status(a, audioSourcePayload())
        QcUltra2.AUDIO_CONTROL -> status(a, byteArrayOf(0x1f))
        QcUltra2.AUDIO_STATUS -> status(a, byteArrayOf(playback.code.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        QcUltra2.VOLUME -> status(a, volumePayload())
        QcUltra2.NOW_PLAYING -> error(a, BmapError.OP_NOT_SUPPORTED) // START-only
        QcUltra2.LATENCY -> status(a, latencyPayload())
        FunctionAddress(5, 13) -> status(a, hex("00d60000020800000208"))
        FunctionAddress(5, 17) -> status(a, byteArrayOf(0, 0))

        // CallManagement / Control / Debug / Notification / DataCollection
        FunctionAddress(6, 0), FunctionAddress(7, 0), FunctionAddress(9, 0), FunctionAddress(13, 0) -> text(a, "1.1.0")
        QcUltra2.CONTROL_GET_ALL -> status(a, hex("0873"))
        QcUltra2.POWER -> status(a, byteArrayOf(1))
        FunctionAddress(8, 7) -> status(a, byteArrayOf(4))
        FunctionAddress(8, 8), FunctionAddress(24, 13) -> status(a, hex("000000000100000000"))
        QcUltra2.NOTIFY_BY_FBLOCK -> status(a, Bitset.encode(notifyBlocks, 4))
        QcUltra2.DATA_COLLECTION_GET_ALL -> status(a, hex("ff83"))

        // Authentication [18.x] — read-only identity
        FunctionAddress(18, 0) -> text(a, "1.1.0")
        QcUltra2.AUTH_CAPABILITIES -> status(a, hex("0339083e07"))
        QcUltra2.AUTH_DEVICE_PUBLIC_KEY -> text(a, "-----BEGIN PUBLIC KEY-----\nVIRTUAL\n-----END PUBLIC KEY-----\n")
        QcUltra2.AUTH_OTP_KEY_TYPE -> status(a, byteArrayOf(3))
        QcUltra2.AUTH_PRODUCT_NAME -> text(a, QcUltra2.CODENAME)
        QcUltra2.AUTH_PLATFORM_NAME -> text(a, QcUltra2.PLATFORM)
        QcUltra2.AUTH_UNENCRYPTED_PRODUCT_IRK -> status(a, hex("3713b952000000000000000000000001"))

        // AudioModes [31.x]
        QcUltra2.AUDIO_MODES_FBLOCK_INFO -> text(a, "1.0.0")
        // fw 10.12: 04 07 00 00 00 7f 02 (4 presets, 7 custom, every flag, two favourites minimum).
        QcUltra2.MODES_CAPABILITIES -> status(a, ModesCapabilities.build(profile.presets.size, profile.customSlots, profile.modesFlags))
        QcUltra2.CURRENT_MODE -> status(a, byteArrayOf(currentMode.toByte()))
        QcUltra2.FAVORITES -> status(a, favoritesPayload())
        QcUltra2.AUDIO_SETTINGS -> status(a, AudioSettings.build(live))
        QcUltra2.MODE_CONFIG -> {
            // GET [index] → that slot's STATUS; a bare GET answers InvalidData (fw 10.12).
            val index = if (payload.isEmpty()) -1 else payload[0].toInt() and 0xFF
            if (index !in 0 until slotCount) error(a, BmapError.INVALID_DATA) else status(a, slotFrame(index))
        }
        FunctionAddress(31, 11) -> status(a, hex("1fffffffff"))

        // Functions that exist but are START/SET_GET-only answer OpNotSupp to a GET (fw 10.12 scan).
        else -> if (a in START_OR_SETGET_ONLY) error(a, BmapError.OP_NOT_SUPPORTED) else error(a, BmapError.FUNC_NOT_SUPPORTED)
    }

    /** `[4.5]` GET carries the MAC to describe; flags bit1 marks the asking phone. */
    private fun deviceInfo(a: FunctionAddress, payload: ByteArray): BmapPacket {
        if (payload.size < Mac.LENGTH) return error(a, BmapError.INVALID_DATA)
        val mac = Mac.format(payload, 0)
        val peer = pairedDevices.firstOrNull { it.mac.equals(mac, ignoreCase = true) }
            ?: return error(a, BmapError.INVALID_DATA) // guess: unknown address
        val flags = (if (isPeerConnected(peer)) 0x01 else 0) or (if (peer.mac == appAddress) 0x02 else 0)
        // Bytes 7-8 = the Class of Device of a non-Bose device (values made up).
        return status(a, Mac.toBytes(peer.mac) + byteArrayOf(flags.toByte(), 0x02, 0x03) + peer.name.toByteArray())
    }

    /** `[4.6]` the Bose app's layout; the values are plausible guesses (no real frame yet). */
    private fun extendedInfo(a: FunctionAddress, payload: ByteArray): BmapPacket {
        if (payload.size < Mac.LENGTH) return error(a, BmapError.INVALID_DATA)
        val mac = Mac.format(payload, 0)
        val peer = pairedDevices.firstOrNull { it.mac.equals(mac, ignoreCase = true) }
            ?: return error(a, BmapError.INVALID_DATA)
        val classic = 0x07 // A2DP, HFP, AVRCP
        val connected = if (isPeerConnected(peer)) classic else 0
        val lePaired = if (peer.mac == appAddress) 0x01 else 0
        return status(a, Mac.toBytes(peer.mac) + byteArrayOf(classic.toByte(), connected.toByte(), 0, 0, lePaired.toByte(), 0))
    }

    private fun isPeerConnected(peer: Peer) = true // both multipoint links up, as in the 8.2.20 capture

    // ── SET_GET ──────────────────────────────────────────────────────────────────────────

    private fun setGet(a: FunctionAddress, p: ByteArray): List<BmapPacket> = when (a.block) {
        1 -> setGetSettings(a, p)
        5 -> listOf(setGetAudioManagement(a, p))
        9 -> listOf(setGetNotifications(a, p))
        22 -> listOf(if (a in PRINT_SETGET) status(a, p.copyOf()) else error(a, BmapError.OP_NOT_SUPPORTED)) // echo: a guess
        31 -> setGetAudioModes(a, p)
        else -> listOf(error(a, BmapError.OP_NOT_SUPPORTED))
    }

    private fun setGetSettings(a: FunctionAddress, p: ByteArray): List<BmapPacket> = when (a) {
        // [1.2] rename answers OpNotSupp in demo mode so the "firmware doesn't support this" path
        // stays exercised; whether the real 10.12 accepts the Bose app's SET_GET is untested.
        QcUltra2.CUSTOM_NAME -> listOf(error(a, BmapError.OP_NOT_SUPPORTED))
        QcUltra2.LE_AUDIO_CONTROL -> if (p.isEmpty()) listOf(error(a, BmapError.LENGTH)) else {
            leAudioEnabled = p[0] != 0.toByte()
            // The real unit restarts: answer, then go away; the factory powers it back on at the
            // next connection attempt, like the reboot finishing.
            val reply = get(a, BmapPacket.EMPTY)
            isPoweredOn = false
            listOf(reply)
        }
        else -> listOf(setGetSettingsSimple(a, p))
    }

    private fun setGetSettingsSimple(a: FunctionAddress, p: ByteArray): BmapPacket = when (a) {
        QcUltra2.VOICE_PROMPTS -> if (p.isEmpty()) error(a, BmapError.LENGTH) else { voicePrompts[0] = p[0]; get(a, BmapPacket.EMPTY) }
        QcUltra2.EQ -> {
            if (p.size < 2) error(a, BmapError.LENGTH) else {
                val value = p[0].toInt()
                val band = p[1].toInt() and 0xFF
                if (band !in 0..2 || value !in QcUltra2.EQ_MIN..QcUltra2.EQ_MAX) error(a, BmapError.INVALID_DATA)
                else { eq[band] = value; get(a, BmapPacket.EMPTY) }
            }
        }
        QcUltra2.BUTTONS -> {
            // Echoes the new action like the notes' "tested echo-back"; whether it sticks on the
            // real unit is disputed, here it does.
            // Each record is addressed by its own button id and event type.
            val record = if (p.size < 3) null else buttonRecords.firstOrNull { it[0] == p[0] && it[1] == p[1] }
            if (p.size < 3) error(a, BmapError.LENGTH)
            else if (record == null) error(a, BmapError.INVALID_DATA)
            else { record[2] = p[2]; status(a, record.copyOf()) }
        }
        QcUltra2.MULTIPOINT -> if (p.isEmpty()) error(a, BmapError.LENGTH) else {
            multipointByte = if (p[0] != 0.toByte()) multipointByte or 0x02 else multipointByte and 0x02.inv()
            get(a, BmapPacket.EMPTY)
        }
        QcUltra2.SIDETONE -> if (p.size < 2) error(a, BmapError.LENGTH) else {
            val level = p[1].toInt() and 0xFF
            if (level > 3) error(a, BmapError.INVALID_DATA)
            else { sidetonePersist = p[0] != 0.toByte(); sidetoneLevel = level; get(a, BmapPacket.EMPTY) }
        }
        QcUltra2.AUTO_PLAY_PAUSE -> if (p.isEmpty()) error(a, BmapError.LENGTH) else { autoPlayPause = p[0] != 0.toByte(); get(a, BmapPacket.EMPTY) }
        QcUltra2.AUTO_ANSWER -> if (p.isEmpty()) error(a, BmapError.LENGTH) else { autoAnswer = p[0] != 0.toByte(); get(a, BmapPacket.EMPTY) }
        QcUltra2.DISABLE_CAPTOUCH -> if (p.isEmpty()) error(a, BmapError.LENGTH) else { touchEnabled = p[0] != 0.toByte(); get(a, BmapPacket.EMPTY) }
        // [1.4] [m] or two bytes little-endian (Bose SettingsStandbyTimerSetGetPacket); the reply is the GET form.
        QcUltra2.STANDBY_TIMER -> when (p.size) {
            1 -> { standbyMinutes = p[0].toInt() and 0xFF; get(a, BmapPacket.EMPTY) }
            2 -> { standbyMinutes = (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8); get(a, BmapPacket.EMPTY) }
            else -> error(a, BmapError.LENGTH)
        }
        // [1.5] SET_GET [step, enabled]: the QC Earbuds 2020 path. Wolverine and the QC45 answer
        // OpNotSupp (write through [31.10] or the slots); the rest are read-only.
        QcUltra2.CNC -> if (!profile.cncWritable) error(a, BmapError.OP_NOT_SUPPORTED) else when {
            p.size < 2 -> error(a, BmapError.LENGTH)
            (p[0].toInt() and 0xFF) > QcUltra2.CNC_WIRE_MAX -> error(a, BmapError.INVALID_DATA)
            else -> {
                // Guess: switching ANC off only takes where [1.5] says the user may (flags bit 1 clear).
                val anc = if (profile.cncFlags and 0x02 == 0) p[1] != 0.toByte() else live.ancEnabled
                applyLive(live.copy(cncWire = p[0].toInt() and 0xFF, ancEnabled = anc))
                status(a, cncPayload())
            }
        }
        else -> error(a, BmapError.OP_NOT_SUPPORTED)
    }

    private fun setGetAudioManagement(a: FunctionAddress, p: ByteArray): BmapPacket = when (a) {
        QcUltra2.VOLUME -> if (p.isEmpty()) error(a, BmapError.LENGTH) else {
            val level = p[0].toInt() and 0xFF
            if (level > volumeMax) error(a, BmapError.INVALID_DATA) else { volume = level; status(a, volumePayload()) }
        }
        QcUltra2.LATENCY -> if (p.size < 2) error(a, BmapError.LENGTH) else {
            // Guess: the firmware clamps to its range and echoes what it now uses.
            latency = (((p[0].toInt() and 0xFF) shl 8 or (p[1].toInt() and 0xFF)).toShort().toInt()).coerceIn(latencyMin, latencyMax)
            status(a, latencyPayload())
        }
        else -> error(a, BmapError.OP_NOT_SUPPORTED)
    }

    /** `[9.2]` SET_GET `[op][bitset]`: 0 overwrite, 1 enable, 2 disable. Replies with the new bitset (shape guessed). */
    private fun setGetNotifications(a: FunctionAddress, p: ByteArray): BmapPacket {
        if (a != QcUltra2.NOTIFY_BY_FBLOCK) return error(a, BmapError.OP_NOT_SUPPORTED)
        if (p.size < 3) return error(a, BmapError.LENGTH)
        val blocks = Bitset.decode(p, 1, p.size - 1)
        notifyBlocks = when (p[0].toInt()) {
            Notifications.OP_OVERWRITE -> blocks
            Notifications.OP_ENABLE -> notifyBlocks + blocks
            Notifications.OP_DISABLE -> notifyBlocks - blocks
            else -> return error(a, BmapError.INVALID_DATA)
        }
        return status(a, Bitset.encode(notifyBlocks, 4))
    }

    private fun setGetAudioModes(a: FunctionAddress, p: ByteArray): List<BmapPacket> = when (a) {
        QcUltra2.AUDIO_SETTINGS -> {
            if (p.size < 5) listOf(error(a, BmapError.LENGTH))
            else if (p[1] != 0.toByte()) listOf(error(a, BmapError.RUNTIME)) // autoCnc=1: firmware says Runtime 8
            else {
                val parsed = AudioSettings.parse(p)!!
                if (parsed.cncWire > QcUltra2.CNC_WIRE_MAX || parsed.spatialCode > 2) listOf(error(a, BmapError.INVALID_DATA))
                else {
                    applyLive(parsed.copy(autoCnc = false))
                    listOf(status(a, AudioSettings.build(live)))
                }
            }
        }
        QcUltra2.MODE_CONFIG -> {
            // Only the profile's own write length (40 / 39 / 37 bytes); the QC45 refuses 40 with Length.
            if (p.size != layout.writeSize) listOf(error(a, BmapError.LENGTH))
            else {
                val index = p[0].toInt() and 0xFF
                when {
                    index in presetRange -> listOf(error(a, BmapError.RUNTIME)) // firmware-locked
                    index !in editableRange -> listOf(error(a, BmapError.INVALID_DATA))
                    p[36] != 0.toByte() -> listOf(error(a, BmapError.RUNTIME)) // autoCnc, same as [31.10]
                    else -> {
                        val echo = ModeConfigs.parse(p)!!
                        val wind = layout.hasWind && echo.windBlock
                        // QC45 (bosectl): with wind block on, the firmware stores CNC 0 whatever was sent.
                        val cnc = if (wind && layout == ModeLayout.COMPACT) 0 else echo.cncWire.coerceIn(0, QcUltra2.CNC_WIRE_MAX)
                        writeSlot(
                            index, promptCode = p[2].toInt() and 0xFF, editable = true, configured = true, flag5 = 0,
                            word40 = USER_SLOT_WORD40, cnc = cnc, spatial = if (layout.hasSpatial) echo.spatialCode else 0,
                            wind = wind, anc = !layout.hasAncToggle || echo.ancEnabled, name = echo.name,
                        )
                        if (currentMode == index) applyLive(slotSettings(index), fromSlot = true)
                        listOf(status(a, slotFrame(index)))
                    }
                }
            }
        }
        QcUltra2.FAVORITES -> {
            val parsed = FavoritesCodec.parse(p)
            if (parsed == null || p.size < 2) listOf(error(a, BmapError.LENGTH))
            else {
                favorites = parsed.indices.filter { it < slotCount }.toSet()
                listOf(status(a, favoritesPayload()))
            }
        }
        // The notes: SET_GET on [31.4] timed out (no frame at all); on [31.3] it is not implemented.
        QcUltra2.DEFAULT_MODE -> emptyList()
        else -> listOf(error(a, BmapError.OP_NOT_SUPPORTED))
    }

    /**
     * Apply new live settings. A write that no longer matches the selected slot moves `[31.3]`
     * to 0xFF, which the real unit pushes as an unsolicited STATUS shortly after.
     */
    private fun applyLive(settings: AudioSettings, fromSlot: Boolean = false) {
        live = settings
        if (fromSlot || slotCount == 0) return
        if (currentMode != QcUltra2.NO_SAVED_MODE && slotSettings(currentMode) != live) {
            currentMode = QcUltra2.NO_SAVED_MODE
            push(status(QcUltra2.CURRENT_MODE, byteArrayOf(QcUltra2.NO_SAVED_MODE.toByte())))
        }
    }

    // ── START ────────────────────────────────────────────────────────────────────────────

    private fun start(a: FunctionAddress, p: ByteArray): List<BmapPacket> = when (a) {
        QcUltra2.CURRENT_MODE -> {
            if (p.isEmpty()) listOf(error(a, BmapError.INVALID_DATA)) else {
                val index = p[0].toInt() and 0xFF
                if (index >= slotCount) listOf(error(a, BmapError.INVALID_DATA))
                else {
                    currentMode = index
                    live = slotSettings(index)
                    // The real unit confirms with RESULT and pushes the new live settings a moment later.
                    if (!withoutAudioSettings) push(status(QcUltra2.AUDIO_SETTINGS, AudioSettings.build(live)))
                    listOf(result(a, byteArrayOf(index.toByte())))
                }
            }
        }
        QcUltra2.MODE_CONFIG -> {
            if (p.isNotEmpty()) listOf(error(a, BmapError.INVALID_DATA))
            else listOf(processing(a)) + slotDump()
        }
        QcUltra2.MODES_GET_ALL -> {
            // GetAll: PROCESSING on [31.1], then the block's state streamed as STATUS frames.
            listOf(processing(a)) + slotDump() +
                status(QcUltra2.CURRENT_MODE, byteArrayOf(currentMode.toByte())) +
                status(QcUltra2.FAVORITES, favoritesPayload()) +
                status(QcUltra2.AUDIO_SETTINGS, AudioSettings.build(live))
        }
        QcUltra2.MODES_RESET -> if (p.isEmpty()) listOf(error(a, BmapError.INVALID_DATA)) else {
            val index = p[0].toInt() and 0xFF
            // Guess: presets refuse like their SET_GET does (Runtime 8); custom slots go back to empty.
            when (index) {
                in presetRange -> listOf(error(a, BmapError.RUNTIME))
                in editableRange -> {
                    emptySlot(index)
                    favorites = favorites - index
                    push(status(QcUltra2.MODE_CONFIG, slotFrame(index)))
                    listOf(result(a, BmapPacket.EMPTY))
                }
                else -> listOf(error(a, BmapError.INVALID_DATA))
            }
        }
        QcUltra2.POWER -> {
            if (p.isEmpty()) listOf(error(a, BmapError.INVALID_DATA)) else {
                val on = p[0] != 0.toByte()
                if (!on) isPoweredOn = false // the transport drops the link right after this reply
                listOf(result(a, byteArrayOf(if (on) 1 else 0)))
            }
        }
        QcUltra2.CONTROL_GET_ALL -> listOf(processing(a), status(QcUltra2.POWER, byteArrayOf(1)))
        QcUltra2.PAIRING_MODE -> {
            if (p.isEmpty()) listOf(error(a, BmapError.INVALID_DATA)) else {
                pairingMode = p[0] != 0.toByte()
                listOf(result(a, byteArrayOf(if (pairingMode) 1 else 0)))
            }
        }
        QcUltra2.ROUTING -> routeTo(a, p)
        // [4.19] LE Audio check: supported, not preferred (this phone streams AAC/SBC). Shape guessed.
        QcUltra2.LE_AUDIO_CHECK -> listOf(processing(a), result(a, byteArrayOf(0x01)))
        QcUltra2.AUDIO_CONTROL -> transport(a, p)
        QcUltra2.DM_CONNECT -> listOf(error(a, BmapError.INVALID_DATA)) // needs a MAC we never got right
        QcUltra2.AUTH_IDENTITY_KEYPAIR -> listOf(processing(a))
        QcUltra2.PRINT_OUTPUT -> listOf(result(a, byteArrayOf(0)))
        else -> listOf(error(a, BmapError.OP_NOT_SUPPORTED))
    }

    /** `[5.3]` START `[AudioControlValue]` → RESULT echo, and the new `[5.4]` play state is pushed. */
    private fun transport(a: FunctionAddress, p: ByteArray): List<BmapPacket> {
        if (p.isEmpty()) return listOf(error(a, BmapError.INVALID_DATA))
        val control = TransportControl.fromCode(p[0].toInt() and 0xFF) ?: return listOf(error(a, BmapError.INVALID_DATA))
        playback = when (control) {
            TransportControl.PLAY -> PlaybackStatus.PLAYING
            TransportControl.PAUSE -> PlaybackStatus.PAUSED
            TransportControl.STOP -> PlaybackStatus.STOPPED
            else -> playback
        }
        push(status(QcUltra2.AUDIO_STATUS, byteArrayOf(playback.code.toByte(), 0xFF.toByte(), 0xFF.toByte())))
        return listOf(result(a, byteArrayOf(control.code.toByte())))
    }

    /**
     * `[4.12]` START `[0x82, mac]`. First attempt answers FuncNotSupp ("routing table empty");
     * that attempt also marks the table as filled, so the retry the app does half a second
     * later succeeds with RESULT `[flags, mac]` (bytes 1-6 = now-active MAC).
     */
    private fun routeTo(a: FunctionAddress, p: ByteArray): List<BmapPacket> {
        if (p.size < 1 + Mac.LENGTH) return listOf(error(a, BmapError.LENGTH))
        val mac = Mac.format(p, 1)
        val peer = pairedDevices.firstOrNull { it.mac.equals(mac, ignoreCase = true) }
            ?: return listOf(error(a, BmapError.INVALID_DATA))
        if (!routingTableReady) {
            routingTableReady = true
            return listOf(error(a, BmapError.FUNC_NOT_SUPPORTED))
        }
        activeSourceMac = peer.mac
        push(status(QcUltra2.AUDIO_SOURCE, audioSourcePayload()))
        return listOf(result(a, byteArrayOf(p[0]) + Mac.toBytes(peer.mac)))
    }

    private fun slotDump(): List<BmapPacket> = slots.indices.map { status(QcUltra2.MODE_CONFIG, slotFrame(it)) }

    // ── Payload builders (device side) ───────────────────────────────────────────────────

    /**
     * `[2.2]` headphones form: `[percent, 0xFF, 0xFF, componentId=0]`. Earbuds: right (1), left (2),
     * combined (4) and case (3) records in bosectl's fixture order; minutes `ffff` unless the
     * profile estimates them (three minutes per percent, a made-up rate).
     */
    private fun batteryPayload(): ByteArray {
        if (profile.battery == VirtualBattery.SINGLE) return record(batteryPercent, null, 0)
        val left = (batteryPercent - 3).coerceAtLeast(0)
        fun minutes(p: Int) = if (profile.batteryMinutes) p * 3 else null
        return record(batteryPercent, minutes(batteryPercent), 1) + record(left, minutes(left), 2) +
            record(minOf(left, batteryPercent), minutes(minOf(left, batteryPercent)), 4) + record(caseBatteryPercent, null, 3)
    }

    private fun record(percent: Int, minutes: Int?, component: Int): ByteArray {
        val m = minutes ?: 0xFFFF
        return byteArrayOf(percent.toByte(), (m shr 8).toByte(), m.toByte(), component.toByte())
    }

    /** `[1.5]` `[steps=11, step, flags]`: the step follows the live level; bit 0 follows ANC where the user can switch it. */
    private fun cncPayload(): ByteArray {
        val flags = if (profile.cncFlags and 0x02 == 0) (profile.cncFlags and 0x01.inv()) or (if (live.ancEnabled) 1 else 0) else profile.cncFlags
        return byteArrayOf(0x0b, live.cncWire.toByte(), flags.toByte())
    }

    /** `[1.7]` three `[min, max, current, band]` groups. */
    private fun eqPayload() = ByteArray(12).also { out ->
        for (band in 0..2) {
            out[band * 4] = QcUltra2.EQ_MIN.toByte()
            out[band * 4 + 1] = QcUltra2.EQ_MAX.toByte()
            out[band * 4 + 2] = eq[band].toByte()
            out[band * 4 + 3] = band.toByte()
        }
    }

    private fun favoritesPayload() = FavoritesCodec.build(favorites.filter { it < slotCount }.toSet(), slotCount.coerceAtLeast(1))

    private fun volumePayload() = byteArrayOf(volumeMax.toByte(), volume.toByte())

    private fun latencyPayload() = byteArrayOf(
        (latencyMin shr 8).toByte(), latencyMin.toByte(), (latencyMax shr 8).toByte(), latencyMax.toByte(),
        (latency shr 8).toByte(), latency.toByte(),
    )

    /** `[4.4]` `[connected bitmask, mac × N]`; every peer is connected here. */
    private fun pairedListPayload(): ByteArray {
        var mask = 0
        pairedDevices.forEachIndexed { i, peer -> if (i < 8 && isPeerConnected(peer)) mask = mask or (1 shl i) }
        var out = byteArrayOf(mask.toByte())
        for (peer in pairedDevices) out += Mac.toBytes(peer.mac)
        return out
    }

    /** `[5.1]` `[supported 00 0f, type, mac]` per the capture; type 0 with no MAC when nothing streams. */
    private fun audioSourcePayload(): ByteArray {
        val mac = activeSourceMac ?: return byteArrayOf(0x00, 0x0f, 0x00)
        return byteArrayOf(0x00, 0x0f, 0x01) + Mac.toBytes(mac)
    }

    private fun text(a: FunctionAddress, s: String) = status(a, s.toByteArray(Charsets.UTF_8))

    companion object {
        /** The simulated battery never drains below this, so demos don't end in a low-battery alert. */
        const val BATTERY_FLOOR_PERCENT = 35
        private const val AUDIO_MODES_BLOCK = 31
        /** Bytes 40-41 of every user slot in the raw dump (byte 41 = changeable flags 0x1d). */
        private const val USER_SLOT_WORD40 = 0x001d

        /** `[0.2]` `87cc23ff` of the reference unit; other profiles list their own ([VirtualProfile.blocks]). */
        val KNOWN_BLOCKS: Set<Int> get() = VirtualProfile.QC_ULTRA_2.blocks

        /** A sample of the error-24 functions from the fw 10.12 scan (FblockInfo of the debug-ish blocks). */
        val ERROR_24_FUNCTIONS: Set<FunctionAddress> = setOf(
            FunctionAddress(8, 0), FunctionAddress(22, 0), FunctionAddress(22, 6), FunctionAddress(22, 7),
            FunctionAddress(23, 0), FunctionAddress(24, 0), FunctionAddress(25, 0), FunctionAddress(26, 0),
        )

        private val PRINT_SETGET = setOf(QcUltra2.PRINT_BIND, QcUltra2.PRINT_UNBIND, QcUltra2.PRINT_VERBOSITY)

        /** Functions that exist but answer OpNotSupp to a GET in the fw 10.12 scan. */
        private val START_OR_SETGET_ONLY: Set<FunctionAddress> = setOf(
            QcUltra2.MODES_GET_ALL, QcUltra2.MODES_RESET, QcUltra2.LE_AUDIO_CHECK, QcUltra2.NOW_PLAYING,
            QcUltra2.PRINT_BIND, QcUltra2.PRINT_UNBIND, QcUltra2.PRINT_VERBOSITY, FunctionAddress(7, 5),
            FunctionAddress(7, 11), FunctionAddress(1, 1), FunctionAddress(2, 1), QcUltra2.AUTH_IDENTITY_KEYPAIR,
        )

        /**
         * Every function the fw 10.12 scan saw answer anything, plus the START/SET_GET-only ones
         * the app uses (`[31.1]`, `[31.6]`, `[31.9]`, `[4.5]`, `[4.6]`, `[4.12]`, `[4.19]`, `[5.3]`,
         * `[22.2]`). `[2.3]` (AuxCableDetection) and `[31.4]` are absent, as on 10.12.
         */
        val KNOWN_FUNCTIONS: Set<FunctionAddress> = buildSet {
            fun fns(block: Int, vararg functions: Int) = functions.forEach { add(FunctionAddress(block, it)) }
            fns(0, 0, 1, 2, 3, 5, 6, 7, 12, 15, 17, 23)
            fns(1, 0, 1, 2, 3, 5, 7, 9, 10, 11, 12, 24, 27, 34, 37)
            fns(2, 0, 1, 2, 5, 16, 21)
            fns(3, 0, 1, 4, 6, 7, 15, 16)
            fns(4, 0, 1, 4, 5, 6, 8, 9, 12, 14, 18, 19)
            fns(5, 0, 1, 3, 4, 5, 6, 7, 13, 17)
            fns(6, 0)
            fns(7, 0, 1, 4, 5, 11)
            fns(8, 7, 8)
            fns(9, 0, 2)
            fns(13, 0, 1)
            fns(18, 0, 1, 9, 11, 12, 13, 19, 24)
            fns(22, 2, 3, 4, 5)
            fns(24, 13)
            fns(31, 0, 1, 2, 3, 6, 8, 9, 10, 11)
        }

        /**
         * The unit whose RFCOMM session started last, for demo mode's "press a control" buttons
         * (the units themselves live in `AppContainer`). Weak: a unit nobody holds is gone.
         */
        @Volatile
        private var lastSession: java.lang.ref.WeakReference<VirtualQcUltra2>? = null

        /** The demo unit currently (or last) connected, or null. */
        fun current(): VirtualQcUltra2? = lastSession?.get()

        fun status(a: FunctionAddress, payload: ByteArray) = BmapPacket(a.block, a.function, BmapOperator.STATUS, payload)
        fun result(a: FunctionAddress, payload: ByteArray) = BmapPacket(a.block, a.function, BmapOperator.RESULT, payload)
        fun processing(a: FunctionAddress) = BmapPacket(a.block, a.function, BmapOperator.PROCESSING)
        fun error(a: FunctionAddress, e: BmapError) = BmapPacket(a.block, a.function, BmapOperator.ERROR, byteArrayOf(e.code.toByte()))

        private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
