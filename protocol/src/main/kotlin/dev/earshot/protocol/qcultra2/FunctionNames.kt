package dev.earshot.protocol.qcultra2

import dev.earshot.protocol.FunctionAddress

/**
 * Human labels for the Lab explorer.
 *
 * The base is the Bose app 13.3.4's own enums ([BoseAppFunctionTable], generated from
 * `docs/bmap-tables/functions-bose-app-13.3.4.json`: 283 functions in blocks 0-22 and 31). On top
 * of that, the ids the fw 10.12 deep scan found answering that the app has no name for, labelled
 * with a trailing `?`, and blocks 23-26, which the headphones report in `[0.2]` but the app does
 * not know at all (most of their functions answer the undocumented error 24).
 */
object FunctionNames {
    /** Label for blocks 23-26 (absent from the app; error 24 on fw 10.12). */
    const val UNKNOWN_ERROR24_BLOCK = "Unknown (error 24)"

    val blocks: Map<Int, String> = BoseAppFunctionTable.blocks +
        (23..26).associateWith { UNKNOWN_ERROR24_BLOCK }

    /** Unnamed in the app, seen answering in the fw 10.12 scan. Names ending in `?` are guesses. */
    private val scanExtras: Map<FunctionAddress, String> = listOf(
        FunctionAddress(5, 13) to "LatencyRecord?",        // 00d6 0000 0208 0000 0208: [cur][min][max][min][max]?
        FunctionAddress(6, 4) to "CallManagement.4?",      // exists, GET needs a payload
        FunctionAddress(8, 7) to "Debug.7?",               // 04
        FunctionAddress(8, 8) to "Debug.8?",               // 000000000100000000
        FunctionAddress(13, 13) to "DataCollection.13?",   // 01
        FunctionAddress(13, 14) to "DataCollection.14?",   // 00
        FunctionAddress(19, 43) to "Experimental.43?",     // exists, GET needs a payload
        FunctionAddress(24, 13) to "Block24.13? (= [8.8])",
    ).toMap()

    fun blockName(block: Int): String? = blocks[block]

    fun functionName(address: FunctionAddress): String? =
        BoseAppFunctionTable.functions[address.block]?.get(address.function) ?: scanExtras[address]
            // Blocks the app lists without functions (6 CallManagement, 8 Debug, 19 Experimental)
            // still answer the universal function 0 with their version string.
            ?: if (address.function == 0 && address.block in blocks) "FblockInfo" else null

    fun functionName(block: Int, function: Int): String? = functionName(FunctionAddress(block, function))

    /** "Settings.RangeControl [1.7]", or "Block 40.[3] [40.3]" when unknown. Always usable as a list label. */
    fun label(address: FunctionAddress): String {
        val block = blocks[address.block] ?: "Block ${address.block}"
        val function = functionName(address) ?: "[${address.function}]"
        return "$block.$function $address"
    }

    /** Whether the notes or the fw 10.12 scan record this address as answering a plain GET. */
    fun isKnownReadable(address: FunctionAddress): Boolean = address in readableSet

    /**
     * Every address that answered a plain GET with STATUS in bosectl's 8.2.20 scans or Earshot's
     * fw 10.12 deep scan. Ordered by block then function: the scan order for the Lab.
     *
     * Not included: `[4.5]`/`[4.6]`/`[31.6]` (GET needs a payload), START-only functions, `[2.3]`.
     */
    fun knownReadableFunctions(): List<FunctionAddress> = readable

    private val readable: List<FunctionAddress> = listOf(
        0 to listOf(0, 1, 2, 3, 5, 6, 7, 12, 15, 17, 23),
        1 to listOf(0, 2, 3, 5, 7, 9, 10, 11, 12, 24, 27, 34, 37),
        2 to listOf(0, 2, 5, 16, 21),
        3 to listOf(0, 1, 4, 6, 7, 15, 16),
        4 to listOf(0, 1, 4, 8, 9, 14, 18),
        5 to listOf(0, 1, 3, 4, 5, 7, 13, 17),
        6 to listOf(0),
        7 to listOf(0, 1, 4),
        8 to listOf(7, 8),
        9 to listOf(0, 2),
        13 to listOf(0, 1, 13, 14),
        18 to listOf(0, 1, 9, 11, 12, 13, 24),
        24 to listOf(13),
        31 to listOf(0, 2, 3, 4, 8, 10, 11),
    ).flatMap { (block, fns) -> fns.map { FunctionAddress(block, it) } }

    private val readableSet: Set<FunctionAddress> = readable.toSet()
}
