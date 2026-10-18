package dev.earshot.protocol.bragi

/**
 * Headphones controlled with Bragi BOS-RPC rather than Bose BMAP. Today that is the Skullcandy
 * Crusher 1080 ANC ("Sound by Bose"); the structure leaves room for the other Skullcandy / Bragi
 * products that share the stack (they differ in which services they expose, which the connection
 * discovers by asking).
 *
 * Routing: a device is sent to the Bragi stack when its Bluetooth name matches [fromBluetoothName]
 * or, once connected, its `dev_info` model number matches [fromModelNumber].
 */
enum class BragiModel(
    val displayName: String,
    /** `dev_info.model_number` values (the SKU the Skullcandy app reads: S6CNW for the Crusher 1080 ANC). */
    val modelNumbers: Set<String>,
    val codename: String,
    /** Lower-cased fragments of the Bluetooth name. */
    private val nameHints: List<String>,
) {
    SKULLCANDY_CRUSHER_1080(
        displayName = "Skullcandy Crusher 1080 ANC",
        modelNumbers = setOf("S6CNW"),
        codename = "CHP300",
        nameHints = listOf("crusher 1080", "crusher1080"),
    ),
    ;

    companion object {
        fun fromBluetoothName(name: String?): BragiModel? {
            val n = name?.lowercase() ?: return null
            return entries.firstOrNull { m -> m.nameHints.any { it in n } }
        }

        fun fromModelNumber(modelNumber: String?): BragiModel? {
            val n = modelNumber?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { n in it.modelNumbers }
        }

        /** True for a name that belongs to the Bragi stack, e.g. to pick the right connection class. */
        fun isBragiName(name: String?): Boolean = fromBluetoothName(name) != null
    }
}
