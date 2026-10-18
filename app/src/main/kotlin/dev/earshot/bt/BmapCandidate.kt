package dev.earshot.bt

/**
 * One way to reach the BMAP control socket: a fixed RFCOMM channel (opened by reflection, no SDP)
 * or a service UUID that SDP resolves to whatever channel the headphones advertise.
 *
 * Why several: the channel is not a per-model constant. bosectl issues #22/#26/#44 show two QC
 * Headphones ("prince") units resolving BMAP to channel **8 on one and 9 on another**; channels 14,
 * 25 and 26 accept the socket without speaking BMAP, and on wolverine 14 is a status beacon and 22 a
 * log stream. bosectl's maintainer: hard-coding one channel breaks somebody either way. So
 * [BoseLinkFactory] tries an ordered list of these and keeps the first that answers the strict
 * [LivenessProbe].
 */
sealed interface BmapCandidate {
    /** How [DevicePrefs] stores a winner: the channel number, or a negative code for an SDP lookup. */
    val prefValue: Int

    data class Channel(val number: Int) : BmapCandidate {
        override val prefValue: Int get() = number
        override fun toString(): String = "ch$number"
    }

    /** The Serial Port Profile UUID through SDP: how prince resolves to 8 or 9 (bosectl). */
    data object SdpSpp : BmapCandidate {
        override val prefValue: Int get() = PREF_SDP_SPP
        override fun toString(): String = "SDP SPP"
    }

    /** Bose's own BMAP UUID through SDP. MIUI resolves it to the channel-14 beacon: the probe rejects that. */
    data object SdpBmap : BmapCandidate {
        override val prefValue: Int get() = PREF_SDP_BMAP
        override fun toString(): String = "SDP BMAP"
    }

    /**
     * BMAP over an LE L2CAP channel, for headphones the phone reaches over LE Audio only (the
     * "LE-Bose …" device). Opened on the LE identity, not the classic address; see [BmapPsmReader].
     */
    data object LeCoc : BmapCandidate {
        override val prefValue: Int get() = PREF_LE_COC
        override fun toString(): String = "LE CoC"
    }

    /** True for the candidates that open an RFCOMM socket on the classic (BR/EDR) device. */
    val isClassic: Boolean get() = this !is LeCoc

    companion object {
        const val PREF_SDP_SPP = -1
        const val PREF_SDP_BMAP = -2
        const val PREF_LE_COC = -3

        /** RFCOMM server channels are 1..30. */
        val VALID_CHANNELS = 1..30

        fun fromPref(value: Int?): BmapCandidate? = when (value) {
            null -> null
            PREF_SDP_SPP -> SdpSpp
            PREF_SDP_BMAP -> SdpBmap
            PREF_LE_COC -> LeCoc
            in VALID_CHANNELS -> Channel(value)
            else -> null
        }
    }
}

/** The order [BoseLinkFactory] tries the candidates in. Pure, so it is unit-tested on its own. */
object BmapCandidates {
    /**
     * Channels seen carrying BMAP: 2 (QC Ultra family, QC Headphones 2nd Gen, Ultra Open), 8 (QC45,
     * one prince unit) and 9 (another prince unit, bosectl #26). bosectl probes exactly these.
     */
    val PROBED_CHANNELS: List<Int> = listOf(2, 8, 9)

    /**
     * Learned winner first within its transport (reconnects go straight to it), then the model's channel guessed from the
     * Bluetooth name, then 2 / 8 / 9, then the two SDP lookups (the durable fix bosectl's maintainer
     * recommends, last because each costs an SDP query and MIUI resolves the Bose UUID to the beacon).
     * Duplicates and impossible channels are dropped; a stale learned channel simply fails its probe
     * and the next candidate is tried.
     *
     * LE Audio: [hasClassic] / [hasLe] say whether a classic device and an LE identity are there to
     * open (the picked device or its twin, see [LeAudioTwins]). The LE channel goes first when
     * [preferLe] (the phone is on LE Audio, or the user picked the "LE-" device), else last; the
     * RFCOMM candidates are dropped when there is no classic device at all.
     */
    fun order(
        learned: BmapCandidate?,
        guessedChannel: Int?,
        hasClassic: Boolean = true,
        hasLe: Boolean = false,
        preferLe: Boolean = false,
    ): List<BmapCandidate> {
        // The learned winner leads its own group; which group goes first is the transport the phone
        // is on now, so a classic channel learned last week does not delay an LE Audio connect.
        val classic = buildList {
            learned?.takeIf { it.isClassic }?.let { add(it) }
            guessedChannel?.let { add(BmapCandidate.Channel(it)) }
            PROBED_CHANNELS.forEach { add(BmapCandidate.Channel(it)) }
            add(BmapCandidate.SdpSpp)
            add(BmapCandidate.SdpBmap)
        }.takeIf { hasClassic }.orEmpty()
        val le = listOf(BmapCandidate.LeCoc).takeIf { hasLe }.orEmpty()
        val all = if (preferLe) le + classic else classic + le
        return all.filter { it !is BmapCandidate.Channel || it.number in BmapCandidate.VALID_CHANNELS }.distinct()
    }
}
