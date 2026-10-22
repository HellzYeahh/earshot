package dev.earshot.bt

import dev.earshot.protocol.bragi.BragiModel
import dev.earshot.protocol.soundcore.SoundcoreModel

/** Which stack drives a pair of headphones. */
enum class DeviceFamily {
    /** Bose BMAP: QC Ultra, QC Headphones, QC45 and the other Bose models. */
    BOSE,

    /** Bragi BOS-RPC: the Skullcandy Crusher 1080 ANC ("Sound by Bose"). */
    BRAGI,

    /** Anker Soundcore over its RFCOMM vendor service; only the models in `SoundcoreModel` (Sleep A30 first). */
    SOUNDCORE,

    /**
     * Any other headset: Earshot doesn't speak its control protocol (yet), so it is shown by its
     * own name with what Android itself reports (link, battery), and nothing is ever sent to it.
     * Until 0.6 these were treated as Bose and got the BMAP channel search, which opens sockets
     * on channels 2/8/9 and writes a `[0.1]` probe: harmless for BMAP, but other stacks have
     * been seen to power off on a stray frame (research/headset-landscape-2026-10.md).
     */
    GENERIC,
    ;

    companion object {
        /**
         * Bose's default Bluetooth names all carry "Bose" ("Bose QC Ultra Headphones",
         * "LE-Bose QC Ultra Earbuds", "Bose QC35 II") or a QC model ("QC45" after a rename in some
         * regions). "QC" must be followed by a model: QCY ("QCY-T13") is a different brand.
         * "Ultra" alone is not Bose either (plenty of "… Ultra" earbuds).
         */
        private val BOSE_NAME = Regex(
            """\bbose\b|quietcomfort|\bqc(?:\s?\d|\s+(?:ultra|earbuds|headphones|se|sc)\b)""",
            RegexOption.IGNORE_CASE,
        )

        fun looksLikeBose(name: String?): Boolean = name != null && BOSE_NAME.containsMatchIn(name)

        /**
         * By Bluetooth name alone. An unknown name is [GENERIC]: a renamed Bose is recognised by
         * what Earshot learned from it instead (see [resolve]).
         */
        fun forName(name: String?): DeviceFamily = when {
            BragiModel.isBragiName(name) -> BRAGI
            SoundcoreModel.fromName(name) != null -> SOUNDCORE
            looksLikeBose(name) -> BOSE
            else -> GENERIC
        }

        fun fromKey(key: String?): DeviceFamily? = entries.firstOrNull { it.name.equals(key, ignoreCase = true) }

        /**
         * The one rule everything uses, strongest first:
         *  1. the user's "Headphone type" [override] for this device;
         *  2. the family that already answered on this address ([learned], kept in [DevicePrefs]):
         *     a Bose renamed to "Alex's headphones" stays Bose;
         *  3. the Bluetooth [name].
         * The router and each connection manager must agree on this, or a device ends up with
         * nobody (or two stacks) driving it. [DevicePrefs.familyFor] feeds it.
         */
        fun resolve(override: String?, name: String?, learned: DeviceFamily? = null): DeviceFamily =
            fromKey(override) ?: learned ?: forName(name)
    }
}
