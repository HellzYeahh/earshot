package dev.earshot.protocol.bragi

/**
 * One read the Lab may issue on a Bragi headset: a call with no side effects. [streaming] reads
 * answer with the current state first; the Lab sends them without the subscribe flag (an empty or
 * default body), so they return that state once and push nothing afterwards.
 */
class BragiRead(
    val address: BragiServices.Address,
    /** The method's name in the Skullcandy app's definitions, `service.method`. */
    val name: String,
    val streaming: Boolean = false,
    val body: ByteArray = ByteArray(0),
)

/**
 * The Lab's read list for the Bragi stack (Crusher 1080, Method 360 / 540): exactly the methods in
 * [BragiServices.READS], never a set / configure call, never a guessed id. BOS-RPC numbers its
 * methods per service, so an id outside the shipped `.proto` files could be anything (the FOTA
 * service is the obvious danger); the Lab only ever walks this list.
 */
object BragiReads {
    val ALL: List<BragiRead> = listOf(
        BragiRead(BragiServices.DEV_INFO, "device.dev_info", body = DeviceInfo.devInfo()),
        BragiRead(BragiServices.SYS_INFO, "device.sys_info"),
        BragiRead(BragiServices.BATT_INFO, "device.batt_info"),
        BragiRead(BragiServices.MODE_INFO, "device.mode_info"),
        BragiRead(BragiServices.TIME_GET, "device.time_get"),
        BragiRead(BragiServices.MODE_READ, "hearing.mode_read", streaming = true),
        BragiRead(BragiServices.HEARING_MODE_INFO, "hearing.mode_info"),
        BragiRead(BragiServices.PEQ_READ_STATE, "hearing.peq_read_state", streaming = true),
        BragiRead(BragiServices.PEQ_READ_CONF, "hearing.peq_read_conf"),
        BragiRead(BragiServices.PEQ_INFO, "hearing.peq_info"),
        BragiRead(BragiServices.SIDETONE_READ, "hearing.sidetone_read", streaming = true),
        BragiRead(BragiServices.SIDETONE_INFO, "hearing.sidetone_info"),
        BragiRead(BragiServices.SPATIAL_INFO_GET, "spatial_audio.info_get", streaming = true),
        BragiRead(BragiServices.CRUSHER_CONFIG_GET, "crusher.config_get"),
        BragiRead(BragiServices.CRUSHER_STATE_GET, "crusher.state_get", streaming = true),
    )

    /** The services as the app names them; the Lab's scan groups. */
    val SERVICE_NAMES: Map<Int, String> = linkedMapOf(
        BragiServices.DEVICE to "device",
        BragiServices.HEARING to "hearing",
        BragiServices.SPATIAL_AUDIO to "spatial_audio",
        BragiServices.CRUSHER to "crusher",
    )

    /**
     * The reads for a product with [features]: the Crusher bass and spatial-audio services only
     * where the product has them ([BragiFeatures] exists so Earshot never asks a Method earbud for
     * what it cannot have; the Lab keeps that rule).
     */
    fun forFeatures(features: BragiFeatures): List<BragiRead> = ALL.filter {
        when (it.address.service) {
            BragiServices.CRUSHER -> features.crusherBass
            BragiServices.SPATIAL_AUDIO -> features.spatialAudio
            else -> true
        }
    }

    fun find(address: BragiServices.Address): BragiRead? = ALL.firstOrNull { it.address == address }
}
