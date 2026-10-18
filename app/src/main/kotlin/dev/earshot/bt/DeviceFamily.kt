package dev.earshot.bt

import dev.earshot.protocol.bragi.BragiModel

/** Which stack drives a pair of headphones. */
enum class DeviceFamily {
    /** Bose BMAP: QC Ultra, QC Headphones, QC45 and the other Bose models. */
    BOSE,

    /** Bragi BOS-RPC: the Skullcandy Crusher 1080 ANC ("Sound by Bose"). */
    BRAGI,
    ;

    companion object {
        /** By Bluetooth name; a name Earshot does not recognise is treated as Bose, as before. */
        fun forName(name: String?): DeviceFamily = if (BragiModel.isBragiName(name)) BRAGI else BOSE

        fun fromKey(key: String?): DeviceFamily? = entries.firstOrNull { it.name.equals(key, ignoreCase = true) }

        /**
         * The one rule everything uses: the user's "Headphone type" [override] wins, otherwise the
         * Bluetooth [name] decides. The router and each connection manager must agree on this, or a
         * renamed device ends up with nobody driving it.
         */
        fun resolve(override: String?, name: String?): DeviceFamily = fromKey(override) ?: forName(name)
    }
}
