package dev.earshot.protocol.qcultra2

import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.hexToBytes

/**
 * Loads the bosectl capture snapshots copied into `src/test/resources/captures/`. Each file holds
 * several full scans ("before", "t+3s", ..., "after") of every GET-able function as
 * `"block.func": "hex"`. The files are plain enough that a regex beats a JSON dependency.
 *
 * Bytes the author redacted appear as `X`/`x`; [Snapshot.bytes] substitutes zeros for those, and
 * [Snapshot.isMasked] tells a test when the value is partly fake.
 */
object Captures {
    const val NOISE_CONTROL = "20260404-173135_noise-control-off-and-on.json"
    const val CNC_SLIDER = "20260404-173221_noise-cancelation-amount-a-slider.json"
    const val WIND_BLOCK = "20260404-173258_wind-block.json"
    const val IMMERSIVE = "20260404-173357_immersive-audio-currently-starting-with-off-will-t.json"
    const val EQ_BASS = "20260404-173511_equalizer-bass-1-of-3-will-go-direction-first-then.json"
    const val EQ_MID = "20260404-173558_equalizer-mid-2-of-3-will-go-direction-first-then-.json"
    const val EQ_TREBLE = "20260404-173645_equalizer-treble-3-of-3-will-go-direction-first-th.json"
    const val MULTIPOINT = "20260404-173737_will-toggle-multi-point-connection-off-and-then-on.json"
    const val EARBUDS_BATTERY = "earbuds-battery-status.hex"

    val all = listOf(NOISE_CONTROL, CNC_SLIDER, WIND_BLOCK, IMMERSIVE, EQ_BASS, EQ_MID, EQ_TREBLE, MULTIPOINT)

    class Snapshot(val label: String, val state: Map<FunctionAddress, String>) {
        fun hex(address: FunctionAddress): String = state[address] ?: error("$label has no $address")
        fun bytes(address: FunctionAddress): ByteArray = hex(address).replace('X', '0').replace('x', '0').hexToBytes()
        fun isMasked(address: FunctionAddress): Boolean = hex(address).any { it == 'X' || it == 'x' }
        fun has(address: FunctionAddress): Boolean = address in state
    }

    fun text(name: String): String =
        checkNotNull(Captures::class.java.getResourceAsStream("/captures/$name")) { "missing test resource $name" }
            .bufferedReader().use { it.readText() }

    private val snapshotHeader = Regex(""""label"\s*:\s*"([^"]+)"\s*,\s*"state"\s*:\s*\{""")
    private val entry = Regex(""""(\d+)\.(\d+)"\s*:\s*"([0-9a-fA-FxX]*)"""")

    fun snapshots(name: String): List<Snapshot> {
        val json = text(name)
        val headers = snapshotHeader.findAll(json).toList()
        return headers.mapIndexed { i, m ->
            val start = m.range.last + 1
            val end = json.indexOf('}', start)
            val body = json.substring(start, end)
            val state = entry.findAll(body).associate { e ->
                FunctionAddress(e.groupValues[1].toInt(), e.groupValues[2].toInt()) to e.groupValues[3]
            }
            check(i < headers.size)
            Snapshot(m.groupValues[1], state)
        }
    }

    fun before(name: String): Snapshot = snapshots(name).first { it.label == "before" }
    fun after(name: String): Snapshot = snapshots(name).first { it.label == "after" }
}
