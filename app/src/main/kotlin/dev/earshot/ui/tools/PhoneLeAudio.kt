package dev.earshot.ui.tools

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build

/**
 * Does this phone do LE Audio at all? Both ends need it: with LE Audio switched on in the
 * headphones, a phone without it simply keeps using Classic Bluetooth (AAC / SBC).
 */
object PhoneLeAudio {
    /** True / false from `BluetoothAdapter.isLeAudioSupported()` (Android 13+); null when unknown. */
    fun support(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            when (context.getSystemService(BluetoothManager::class.java)?.adapter?.isLeAudioSupported) {
                BluetoothStatusCodes.FEATURE_SUPPORTED -> true
                BluetoothStatusCodes.FEATURE_NOT_SUPPORTED -> false
                else -> null // Bluetooth off, not configured, or an error code
            }
        } catch (e: SecurityException) {
            null
        }
    }
}
