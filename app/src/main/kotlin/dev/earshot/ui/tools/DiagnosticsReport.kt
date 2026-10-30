package dev.earshot.ui.tools

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import dev.earshot.appContainer
import dev.earshot.bt.BtTimeline
import dev.earshot.bt.DeviceFinder
import dev.earshot.bt.LogRing
import dev.earshot.core.HeadphoneState
import dev.earshot.core.SupportTier
import dev.earshot.distribution.Distribution
import dev.earshot.distribution.LegacyApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A plain-text snapshot of what Earshot knows about the connection, for pasting into a bug report
 * when the headphones will not connect or a control misbehaves. It holds the headphones' Bluetooth
 * name and address, the services the phone has seen them advertise, the model Earshot detected,
 * their Bluetooth history on this phone ([dev.earshot.bt.TimelineRecorder]) and the recent connection log. No account, location or contact data is involved.
 */
object DiagnosticsReport {

    suspend fun build(context: Context): String {
        val container = context.applicationContext.appContainer
        val state = container.headphones.state.value
        val saved = runCatching { container.devicePrefs.savedDevice() }.getOrNull()
        val family = runCatching { container.devicePrefs.familyOverrideFor(saved?.address) }.getOrNull()
        val drivenBy = saved?.let { runCatching { container.devicePrefs.familyFor(it.address, it.name) }.getOrNull() }
        val profile = saved?.address?.let { runCatching { container.devicePrefs.bragiProfileFor(it) }.getOrNull() }
        val channel = saved?.address?.let { runCatching { container.devicePrefs.candidateFor(it) }.getOrNull() }
        return buildString {
            appendLine("Earshot connection report")
            // Both builds share the version name: the flavour and package tell a Play report from a GitHub one.
            val versionName = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            appendLine("App: $versionName (${Distribution.NAME}, ${context.packageName})")
            // Two Earshots fight over the one control channel: the losing one only sees a busy headset.
            LegacyApp.find(context)?.let { other ->
                val what = if (other == LegacyApp.Kind.LEGACY) "the pre-1.0 app" else "the Google Play build"
                appendLine("Also installed: ${Distribution.LEGACY_PACKAGE} ($what)")
            }
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("Saved headphones: ${saved?.name ?: "none"} ${saved?.address ?: ""}".trim())
            appendLine("Headphone type setting: ${family ?: "automatic"}")
            appendLine("Driven by: ${drivenBy?.name?.lowercase() ?: "-"}")
            appendLine("Learned link: ${profile ?: "-"} / control channel ${channel ?: "-"}")
            appendLine("Connection: ${state.connection.status} ${state.connection.message ?: ""}".trim())
            saved?.address?.let { appendLeAudio(context, it) }
            appendModel(state)
            appendLine()
            appendLine("Bluetooth services the phone knows for the headphones:")
            val uuids = services(context, saved?.address)
            if (uuids.isEmpty()) appendLine("  (none cached, or permission missing)") else uuids.forEach { appendLine("  $it") }
            appendLine()
            appendTimeline(context, saved?.address)
            appendLine()
            appendLine("Recent log:")
            LogRing.tail(150).forEach { appendLine(it) }
        }
    }

    /**
     * Earbuds on LE Audio show up as a second "LE-…" device (0.3.1 report: Earshot waited for a
     * link that was there). List the device type, its twin and whether LE Audio is up.
     */
    @SuppressLint("MissingPermission")
    private suspend fun StringBuilder.appendLeAudio(context: Context, address: String) {
        val finder = DeviceFinder(context.applicationContext, context.applicationContext.appContainer.devicePrefs)
        val device = finder.deviceFor(address)
        appendLine("Device type: ${device?.let { typeName(finder.typeOf(it)) } ?: "not bonded"}")
        val twins = finder.twinsOf(address)
        if (twins.isEmpty()) appendLine("LE Audio twin: none")
        twins.forEach { twin ->
            val name = runCatching { twin.name }.getOrNull()
            appendLine("LE Audio twin: $name ${twin.address} (${typeName(finder.typeOf(twin))})")
        }
        appendLine("Linked for audio: ${finder.isAudioConnected(address) ?: "unknown"}, over LE Audio: ${finder.isLeAudioConnected(address)}")
    }

    /**
     * The saved headphones' Bluetooth history (and their LE Audio twin's), newest last: disconnect
     * reasons, lost pairings and codecs the log above may have scrolled past. Other headsets stay out.
     */
    private fun StringBuilder.appendTimeline(context: Context, address: String?) {
        appendLine("Bluetooth history (this phone, newest last):")
        if (address == null) {
            appendLine("  (no saved headphones)")
            return
        }
        val container = context.applicationContext.appContainer
        val twins = runCatching { DeviceFinder(context.applicationContext, container.devicePrefs).twinsOf(address).map { it.address } }.getOrDefault(emptyList())
        val ours = (listOf(address) + twins).map { it.uppercase() }.toSet()
        val events = runCatching { container.timeline.store.events.value }.getOrDefault(emptyList())
            .filter { it.address.uppercase() in ours }
            .takeLast(TIMELINE_LINES)
        if (events.isEmpty()) appendLine("  (nothing recorded yet)")
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        events.forEach { e ->
            val twin = if (e.address.equals(address, ignoreCase = true)) "" else " (LE twin)"
            appendLine("  ${format.format(Date(e.timeMs))} ${BtTimeline.describe(e)}$twin")
        }
    }

    private const val TIMELINE_LINES = 40

    private fun typeName(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "LE only"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
        else -> "unknown"
    }

    private fun StringBuilder.appendModel(state: HeadphoneState) {
        val info = state.info
        appendLine("Detected model: ${info?.modelName ?: "-"} (product ${info?.productName ?: "-"}, id ${info?.productId?.let { "0x%04x".format(it) } ?: "-"})")
        appendLine("Firmware: ${info?.firmwareVersion ?: "-"}")
        appendLine("Layout verified on hardware: ${state.capabilities.verified}")
        // What the owner saw on the tier tag; an Experimental report is how a model moves to Tested.
        appendLine("Support tier: ${SupportTier.of(state)?.name?.lowercase() ?: "-"}")
        appendLine("Secure session: ${state.secureSession.status} ${state.secureSession.message ?: ""}".trim())
    }

    /** The SDP service UUIDs the phone has cached for [address]; the Bose and Skullcandy stacks use different ones. */
    @SuppressLint("MissingPermission")
    private fun services(context: Context, address: String?): List<String> {
        if (address == null) return emptyList()
        return runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            adapter?.getRemoteDevice(address)?.uuids?.map { it.toString() }.orEmpty().sorted()
        }.getOrDefault(emptyList())
    }
}
