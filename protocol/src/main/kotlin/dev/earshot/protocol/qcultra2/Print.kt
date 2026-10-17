package dev.earshot.protocol.qcultra2

import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress

/**
 * Print block `[22.x]`: the firmware's debug log console. The Bose app has the methods
 * (`setBind`, `setVerbosity`, `startOutput`, `getTransports`, `getClients`) but never calls them.
 * On fw 10.12: Bind `[22.3]`, Unbind `[22.4]` and Verbosity `[22.5]` exist (GET → error 5, so
 * SET_GET); FblockInfo, Transports and Clients answer error 24. The argument strings the
 * firmware accepts are unknown: everything here is **experimental, untested on hardware**.
 *
 * Console syntax used by Earshot's Lab: `bind <arg>`, `unbind <arg>`, `verbosity <arg>`,
 * `output [n]`; anything else is rejected before it reaches the headphones.
 */
object PrintConsole {
    /** Parse one console line into the packet to send, or null when the verb is unknown. */
    fun parseCommand(line: String): BmapPacket? {
        val trimmed = line.trim()
        val verb = trimmed.substringBefore(' ').lowercase()
        val arg = trimmed.substringAfter(' ', "").trim()
        fun setGet(a: FunctionAddress) = BmapPacket.setGet(a, arg.toByteArray(Charsets.UTF_8).also {
            require(it.size <= 255) { "argument longer than 255 bytes" }
        })
        return when (verb) {
            "bind" -> setGet(QcUltra2.PRINT_BIND)
            "unbind" -> setGet(QcUltra2.PRINT_UNBIND)
            "verbosity" -> setGet(QcUltra2.PRINT_VERBOSITY)
            "output" -> {
                val n = arg.ifBlank { null }?.toIntOrNull()
                BmapPacket.start(QcUltra2.PRINT_OUTPUT, if (n == null) BmapPacket.EMPTY else byteArrayOf(n.coerceIn(0, 255).toByte()))
            }
            else -> null
        }
    }

    /** A reply payload as text when it is printable UTF-8, else hex. */
    fun render(payload: ByteArray): String {
        val text = String(payload, Charsets.UTF_8).trimEnd('\u0000')
        val printable = text.isNotEmpty() &&
            text.all { it != '�' && (it == '\n' || it == '\r' || it == '\t' || !it.isISOControl()) }
        return if (printable) text else payload.joinToString("") { "%02x".format(it) }
    }
}
