package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

/**
 * `BluetoothDevice.getBatteryLevel()`: the battery Android last heard from the headset (HFP
 * indicator, Apple's +IPHONEACCEV, BLE Battery Service). A SystemApi since Android 13, but on the
 * hidden-API policy's SDK list (Android 16 `hiddenapi-flags.csv`: "sdk,system-api"), so reflection
 * is not blocked; the Bluetooth service may still refuse a normal app, and then this is null. Used
 * only for a starting value: the change broadcast keeps it current.
 */
object DeviceBattery {
    @SuppressLint("MissingPermission", "DiscouragedPrivateApi") // caller holds BLUETOOTH_CONNECT; failure is expected and caught
    fun read(device: BluetoothDevice): Int? = runCatching {
        BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as Int
    }.onFailure { BtLog.d("getBatteryLevel unavailable: ${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName}") }
        .getOrNull()
        ?.takeIf { it in 0..100 }
}
