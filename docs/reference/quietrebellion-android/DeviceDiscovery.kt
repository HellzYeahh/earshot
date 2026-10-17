/*
 * DeviceDiscovery.kt – Find a paired Bose device on Android.
 * Uses bondedDevices (no active scan) – same strategy as bosectl discovery.py.
 */
package net.quietrebellion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice

object DeviceDiscovery {
    /** Returns all classic-BT bonded devices, sorted by name. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(adapter: BluetoothAdapter): List<BluetoothDevice> =
        adapter.bondedDevices.sortedBy { it.name ?: "" }
}

