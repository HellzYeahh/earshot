package dev.earshot.bt

import java.util.UUID

/**
 * Where a vendor's byte pipe lives on a headset's GATT server: one service, the characteristic
 * Earshot writes to, and the ones whose notifications carry the headset's bytes back. A
 * [GattTransport] opened with a spec behaves like an RFCOMM socket, so a vendor's frame codec does
 * not care which of the two it runs on.
 *
 * Specs are data, not guesses: each one comes from the vendor's own app (research "Top brand
 * headset protocols", JBL notes §2). A spec never names a firmware-update service:
 * [isFirmwareService] runs in `init`, so such a spec cannot even be built.
 */
data class GattLinkSpec(
    /** For logs and the connection report. */
    val name: String,
    val service: UUID,
    /** The characteristic Earshot writes its frames to. */
    val write: UUID,
    /** Characteristics whose notifications (or indications) are the incoming stream, enabled in this order. */
    val notify: List<UUID>,
    /** Write-with-response (`WRITE_TYPE_DEFAULT`) when true, as JBL's app does; without response otherwise. */
    val writeWithResponse: Boolean = true,
    /** The ATT MTU to ask for. The headset may grant less; chunks follow what it grants. */
    val mtu: Int = DEFAULT_MTU,
) {
    init {
        require(notify.isNotEmpty()) { "a GATT link needs at least one notifying characteristic" }
        require(mtu in MIN_MTU..MAX_MTU) { "ATT MTU $mtu is outside $MIN_MTU..$MAX_MTU" }
        require(!isFirmwareService(service)) { "$service is a firmware-update service" }
    }

    companion object {
        /** The ATT minimum every LE link starts with. */
        const val MIN_MTU = 23

        /** Android's own ceiling for `requestMtu`. */
        const val MAX_MTU = 517

        const val DEFAULT_MTU = 512

        /**
         * GATT services that update firmware, by prefix because the notes quote some of them only
         * up to their first group. Recognising one is fine (it fingerprints the chip); opening one
         * is never: BES OTA (`66666666-…`) and TOTA (`86868686-…`), the "normal" and BES SDK OTA
         * services and JBL's "excelpoint.co\x82" OTA variants (JBL notes §4).
         */
        private val FIRMWARE_PREFIXES = listOf(
            "66666666-",
            "86868686-",
            "01000100-0000-1000-8000-",
            "03000300-0000-1000-8000-",
            "05000500-0000-1000-8000-",
            "06000600-0000-1000-8000-",
            "07000700-0000-1000-8000-",
            "65786365-6c70-6f69-6e74-2e636f82",
        )

        // Declared before the specs below: their init blocks call it while the companion initialises.
        fun isFirmwareService(uuid: UUID): Boolean = uuid.toString().lowercase().let { s -> FIRMWARE_PREFIXES.any { s.startsWith(it) } }

        /** Client Characteristic Configuration descriptor, the switch for notifications. */
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun harman(suffix: String) = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d$suffix")

        /**
         * JBL's Harman service ("excelpoint.com" in ASCII), shared by the H-AA (2021–24) and H-P4
         * (2025+) stacks: notifications on `…0001`, write-with-response on `…0002`, MTU 512. The raw
         * `…0003` characteristic belongs to H-P4 security modes 1 and 3 and is left to that driver.
         */
        val HARMAN = GattLinkSpec(
            name = "Harman",
            service = harman("0000"),
            write = harman("0002"),
            notify = listOf(harman("0001")),
        )

        /**
         * Airoha's RACE service over LE (`PRIM-…-AirohaBLE` in ASCII), used by JBL's Airoha models
         * while the phone is on LE Audio; otherwise RACE runs on the Airoha SPP record. TX is the
         * characteristic the app writes, RX the one that notifies. The notes quote both only to
         * their third group; the tail is the service's own "irohaBLE" suffix, which the ASCII
         * pattern of all three UUIDs shares. Unverified on hardware: [GattTransport] checks the
         * properties before it trusts them.
         */
        val AIROHA = GattLinkSpec(
            name = "Airoha RACE",
            service = UUID.fromString("5052494d-2dab-0341-6972-6f6861424c45"),
            write = UUID.fromString("43484152-2dab-3241-6972-6f6861424c45"),
            notify = listOf(UUID.fromString("43484152-2dab-3141-6972-6f6861424c45")),
        )
    }
}
