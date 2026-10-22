package dev.earshot.protocol.bragi

/**
 * What a Bragi model has on top of the services every one of them shares (`device` for identity and
 * battery, `hearing` for the Quiet / Aware / Off modes, their gain and the sidetone). The rest of a
 * model's surface is read from the headphones at runtime and skipped when the firmware refuses it;
 * these flags only keep Earshot from asking for, or showing, what the product cannot have.
 * From the Skullcandy app's feature manifest (research notes `skullcandy.md` §4-5): the Crusher
 * bass and Bose TrueSpatial belong to the Crusher 1080's configuration only.
 */
data class BragiFeatures(
    /** The haptic Crusher bass (`crusher` service). */
    val crusherBass: Boolean,
    /** Bose TrueSpatial Still / Motion (`spatial_audio` service). */
    val spatialAudio: Boolean,
    /** True-wireless earbuds rather than over-ear headphones. */
    val earbuds: Boolean,
) {
    companion object {
        val CRUSHER = BragiFeatures(crusherBass = true, spatialAudio = true, earbuds = false)

        /** Method 360 / 540 ANC: ANC, Stay-Aware and the shared services; no Crusher bass, no spatial audio. */
        val METHOD = BragiFeatures(crusherBass = false, spatialAudio = false, earbuds = true)
    }
}

/**
 * Headphones controlled with Bragi BOS-RPC rather than Bose BMAP: the Skullcandy models "Sound by
 * Bose" (Crusher 1080 ANC, Method 360 ANC, Method 540 ANC). The Method earbuds share the Crusher's
 * link UUIDs, framing and service model (the app's Bragi configuration binds all three to the same
 * data and third-party services), so they run on the same code; only [features] differ. The
 * Skull-iQ models (Crusher ANC 2, Rail ANC: BOS-AT) and the Airoha RACE ones (Sesh ANC, Icon ANC,
 * ...) are a different protocol and stay out of this table, so they remain generic headsets.
 *
 * Routing: a device is sent to the Bragi stack when its Bluetooth name matches [fromBluetoothName]
 * or, once connected, its `dev_info` model number matches [fromModelNumber].
 */
enum class BragiModel(
    val displayName: String,
    /** `dev_info.model_number` values (the SKU the Skullcandy app reads: S6CNW for the Crusher 1080 ANC). */
    val modelNumbers: Set<String>,
    val codename: String,
    /** Lower-cased fragments of the Bluetooth name; spaces, dashes and underscores don't matter. */
    private val nameHints: List<String>,
    val features: BragiFeatures = BragiFeatures.CRUSHER,
) {
    SKULLCANDY_CRUSHER_1080(
        displayName = "Skullcandy Crusher 1080 ANC",
        modelNumbers = setOf("S6CNW"),
        codename = "CHP300",
        nameHints = listOf("crusher 1080", "crusher1080"),
    ),
    SKULLCANDY_METHOD_360(
        displayName = "Skullcandy Method 360 ANC",
        modelNumbers = setOf("S2MTW"),
        codename = "T99_PLUS",
        // "ANC" is part of the hint: a plain "Method" is not enough to claim a device (the same lesson as QC vs QCY).
        nameHints = listOf("method 360 anc"),
        features = BragiFeatures.METHOD,
    ),
    SKULLCANDY_METHOD_540(
        displayName = "Skullcandy Method 540 ANC",
        modelNumbers = setOf("S2MFW"),
        codename = "T120",
        nameHints = listOf("method 540 anc"),
        features = BragiFeatures.METHOD,
    ),
    ;

    companion object {
        /** Letters and digits only, lower case: "Method-360_ANC" and "method 360 anc" compare equal. */
        private fun compact(s: String) = s.lowercase().replace("+", "plus").filter { it.isLetterOrDigit() }

        fun fromBluetoothName(name: String?): BragiModel? {
            val n = name?.lowercase() ?: return null
            val c = compact(n)
            return entries.firstOrNull { m -> m.nameHints.any { it in n || compact(it) in c } }
        }

        /** The SKU, or the SKU followed by a separator and an ODM code (`S2MTW-xxx`). */
        fun fromModelNumber(modelNumber: String?): BragiModel? {
            val n = modelNumber?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { m ->
                m.modelNumbers.any { sku -> n == sku || (n.startsWith(sku) && !n[sku.length].isLetterOrDigit()) }
            }
        }

        /** `dev_info.model`, which on the Crusher is the codename (CHP300); `T99+` and `T99_PLUS` are the same. */
        fun fromCodename(model: String?): BragiModel? {
            val c = model?.let(::compact)?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { compact(it.codename) == c }
        }

        /** True for a name that belongs to the Bragi stack, e.g. to pick the right connection class. */
        fun isBragiName(name: String?): Boolean = fromBluetoothName(name) != null
    }
}
