package dev.earshot.protocol.qcultra2

/**
 * Notification block `[9.x]`: which blocks the headphones push STATUS frames for on their own.
 *
 * `[9.2]` ByFblock: GET → big-endian block bitset ([Bitset]); SET_GET `[op][bitset]` with op
 * 0 = overwrite, 1 = enable, 2 = disable (Bose app `NotificationByFblock…`; an empty set is sent
 * as two zero bytes). The fw 10.12 scan reads `00000000`: **nothing is pushed until a client
 * subscribes**. The Bose app subscribes right after connecting with Enable for the intersection
 * of `[0.2]` and [APP_BLOCKS]; for wolverine that is `09 02 02 05 01 80 04 22 B7`
 * (blocks 0, 1, 2, 4, 5, 7, 9, 13, 18, 31).
 *
 * What that turns on (per block, functions Earshot consumes in bold):
 *  - 0 ProductInfo; 1 Settings (**[1.9]** buttons, **[1.34]** touch, **[1.37]** LE Audio, EQ, sidetone, …);
 *  - 2 Status (**[2.2]** battery, **[2.5]** charger, **[2.12]** button events, **[2.16]** operation mode,
 *    **[2.21]** mic mute);
 *  - 4 DeviceManagement (**[4.4]** device list changes); 5 AudioManagement (**[5.1]** source,
 *    **[5.4]** play state, **[5.5]** volume, **[5.7]** latency); 7 Control (power);
 *  - 9 Notification, 13 DataCollection, 18 Authentication (nothing Earshot reads);
 *  - 31 AudioModes (**[31.3]** current mode, **[31.6]** slots, **[31.8]** favourites, **[31.10]** live settings).
 */
object Notifications {
    const val OP_OVERWRITE = 0
    const val OP_ENABLE = 1
    const val OP_DISABLE = 2

    /** Blocks the Bose app asks pushes for, before intersecting with `[0.2]`. */
    val APP_BLOCKS: Set<Int> = setOf(0, 1, 2, 4, 5, 7, 9, 13, 16, 17, 18, 31)

    /** What the Bose app enables on wolverine fw 10.12 (`[0.2]` = `87cc23ff`). */
    val WOLVERINE_BLOCKS: Set<Int> = setOf(0, 1, 2, 4, 5, 7, 9, 13, 18, 31)

    /** [APP_BLOCKS] limited to what `[0.2]` reports; [WOLVERINE_BLOCKS] when `[0.2]` was not read. */
    fun blocksToEnable(supportedBlocks: Set<Int>?): Set<Int> =
        supportedBlocks?.let { APP_BLOCKS intersect it } ?: WOLVERINE_BLOCKS

    /**
     * `[9.2]` SET_GET payload: `[op]` + big-endian bitset sized to the highest block (at least two
     * bytes, the app's encoding of an empty set). Wolverine Enable → `01 80 04 22 b7`.
     */
    fun byFblockPayload(blocks: Set<Int>, op: Int = OP_ENABLE): ByteArray {
        require(op in OP_OVERWRITE..OP_DISABLE) { "bad notification op $op" }
        require(blocks.all { it in 0..255 }) { "block ids are bytes" }
        return byteArrayOf(op.toByte()) + Bitset.encode(blocks, Bitset.bytesFor(blocks, minBytes = 2))
    }

    /**
     * `[9.2]` GET / SET_GET STATUS → the blocks currently pushing. The GET form is the bare bitset
     * (`00000000`); a 5-byte reply is read as `[op][bitset]` (the echo's exact shape is a guess).
     */
    fun parseByFblock(payload: ByteArray): Set<Int>? = when {
        payload.isEmpty() -> null
        payload.size == 5 -> Bitset.decode(payload, 1, 4)
        else -> Bitset.decode(payload)
    }
}
