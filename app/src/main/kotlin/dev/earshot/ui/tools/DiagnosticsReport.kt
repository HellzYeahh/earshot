package dev.earshot.ui.tools

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import dev.earshot.appContainer
import dev.earshot.bt.LogRing
import dev.earshot.core.HeadphoneState

/**
 * A plain-text snapshot of what Earshot knows about the connection, for pasting into a bug report
 * when the headphones will not connect or a control misbehaves. It holds the headphones' Bluetooth
 * name and address, the services the phone has seen them advertise, the model Earshot detected and
 * the recent connection log. No account, location or contact data is involved.
 */
object DiagnosticsReport {

    suspend fun build(context: Context): String {
        val container = context.applicationContext.appContainer
        val state = container.headphones.state.value
        val saved = runCatching { container.devicePrefs.savedDevice() }.getOrNull()
        val family = runCatching { container.devicePrefs.familyOverride() }.getOrNull()
        val profile = saved?.address?.let { runCatching { container.devicePrefs.bragiProfileFor(it) }.getOrNull() }
        val channel = saved?.address?.let { runCatching { container.devicePrefs.channelFor(it) }.getOrNull() }
        return buildString {
            appendLine("Earshot connection report")
            appendLine("App: ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("Saved headphones: ${saved?.name ?: "none"} ${saved?.address ?: ""}".trim())
            appendLine("Headphone type setting: ${family ?: "automatic"}")
            appendLine("Learned link: ${profile ?: "-"} / Bose channel ${channel ?: "-"}")
            appendLine("Connection: ${state.connection.status} ${state.connection.message ?: ""}".trim())
            appendModel(state)
            appendLine()
            appendLine("Bluetooth services the phone knows for the headphones:")
            val uuids = services(context, saved?.address)
            if (uuids.isEmpty()) appendLine("  (none cached, or permission missing)") else uuids.forEach { appendLine("  $it") }
            appendLine()
            appendLine("Recent log:")
            LogRing.tail(150).forEach { appendLine(it) }
        }
    }

    private fun StringBuilder.appendModel(state: HeadphoneState) {
        val info = state.info
        appendLine("Detected model: ${info?.modelName ?: "-"} (product ${info?.productName ?: "-"}, id ${info?.productId?.let { "0x%04x".format(it) } ?: "-"})")
        appendLine("Firmware: ${info?.firmwareVersion ?: "-"}")
        appendLine("Layout verified on hardware: ${state.capabilities.verified}")
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
