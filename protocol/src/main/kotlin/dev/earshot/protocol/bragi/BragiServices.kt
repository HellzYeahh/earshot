package dev.earshot.protocol.bragi

/**
 * The numeric (service, method) addresses of the BOS-RPC calls Earshot uses, taken from the
 * `bos_service_id` / `bos_method_id` options in the shipped `.proto` files and confirmed against
 * the Skullcandy app's `transport.rpc` registry.
 *
 * Only the services below are addressable; this doubles as the Crusher's allow-list. The whole
 * firmware-update (FOTA) surface and `feature.set_state` on fixed/DRM features are deliberately
 * **not** here, so [WriteGuard][dev.earshot.protocol.bragi]-style callers cannot reach them.
 */
object BragiServices {
    /** A BOS-RPC call address. */
    data class Address(val service: Int, val method: Int)

    // device = 2
    const val DEVICE = 2
    val DEV_INFO = Address(DEVICE, 1)
    val SYS_INFO = Address(DEVICE, 2)
    val BATT_INFO = Address(DEVICE, 3)
    val MODE_INFO = Address(DEVICE, 4)
    val TIME_GET = Address(DEVICE, 5)
    val TIME_SET = Address(DEVICE, 6)

    // hearing = 4 (ANC, sidetone, parametric EQ)
    const val HEARING = 4
    val MODE_READ = Address(HEARING, 1)
    val MODE_CONFIGURE = Address(HEARING, 2)
    val HEARING_MODE_INFO = Address(HEARING, 3)
    val PEQ_READ_STATE = Address(HEARING, 4)
    val PEQ_READ_CONF = Address(HEARING, 5)
    val PEQ_INFO = Address(HEARING, 6)
    val PEQ_CONFIGURE = Address(HEARING, 7)
    val PEQ_ENABLE = Address(HEARING, 8)
    val PEQ_DISABLE = Address(HEARING, 9)
    val SIDETONE_READ = Address(HEARING, 10)
    val SIDETONE_CONFIGURE = Address(HEARING, 11)
    val SIDETONE_INFO = Address(HEARING, 12)

    // spatial_audio = 17 (Bose TrueSpatial)
    const val SPATIAL_AUDIO = 17
    val SPATIAL_INFO_GET = Address(SPATIAL_AUDIO, 1)
    val SPATIAL_MODE_SET = Address(SPATIAL_AUDIO, 2)

    // crusher = 23 (haptic bass)
    const val CRUSHER = 23
    val CRUSHER_CONFIG_GET = Address(CRUSHER, 1)
    val CRUSHER_STATE_GET = Address(CRUSHER, 2)
    val CRUSHER_STATE_SET = Address(CRUSHER, 3)

    // authenticated_access = 25 (the pairing + secure-session handshake; sent cleartext)
    const val AUTHENTICATED_ACCESS = 25
    val AUTH_HELLO = Address(AUTHENTICATED_ACCESS, 1)
    val AUTH_AUTHENTICATE = Address(AUTHENTICATED_ACCESS, 2)
    val AUTH_TRUST_START = Address(AUTHENTICATED_ACCESS, 3)
    val AUTH_TRUST_COMMIT = Address(AUTHENTICATED_ACCESS, 4)
    val AUTH_TRUST_NONCE = Address(AUTHENTICATED_ACCESS, 5)
    val AUTH_TRUST_CONFIRM = Address(AUTHENTICATED_ACCESS, 6)
    val AUTH_TRUST_ABORT = Address(AUTHENTICATED_ACCESS, 7)

    /** Services whose RPCs Earshot is allowed to send. Everything else (FOTA, vendor) is refused. */
    val ALLOWED_SERVICES = setOf(DEVICE, HEARING, SPATIAL_AUDIO, CRUSHER, AUTHENTICATED_ACCESS)

    fun isAllowed(address: Address): Boolean = address.service in ALLOWED_SERVICES
}
