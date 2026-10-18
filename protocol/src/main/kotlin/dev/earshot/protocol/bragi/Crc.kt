package dev.earshot.protocol.bragi

/**
 * The two checksums the Bragi `bos_ppp_framing` layer uses (verified against the Skullcandy app
 * 5.0.6 and reproduced byte-for-byte from the captured golden frames):
 *
 *  - the 1-byte header check is **CRC-8/ROHC** (poly 0x07, init 0xFF, reflected in/out, no xorout),
 *  - the 2-byte frame-check sequence is **CRC-16/MCRF4XX** (poly 0x1021, init 0xFFFF, reflected
 *    in/out, no xorout), stored big-endian.
 *
 * Both are reflected, so they are implemented with the reflected polynomials (0xE0 and 0x8408) and
 * a right-shifting loop. See [PppFraming] for which bytes each one covers.
 */
internal object Crc {

    /** CRC-8/ROHC over [data] (or a slice of it). */
    fun crc8Rohc(data: ByteArray, from: Int = 0, until: Int = data.size): Int {
        var crc = 0xFF
        for (i in from until until) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xE0 else crc ushr 1 }
        }
        return crc and 0xFF
    }

    /** CRC-16/MCRF4XX over [data] (or a slice of it). Returned as a 16-bit value; store big-endian. */
    fun crc16Mcrf4xx(data: ByteArray, from: Int = 0, until: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until until) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }
}
