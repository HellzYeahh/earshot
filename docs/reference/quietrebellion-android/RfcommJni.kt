/*
 * RfcommJni.kt – Kotlin-side JNI declarations for the native RFCOMM socket.
 * Mirrors the Python: socket.socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM)
 *                     socket.connect((mac, channel))
 */
package net.quietrebellion

import android.os.ParcelFileDescriptor

object RfcommJni {
    init { System.loadLibrary("quiet-rebellion-rfcomm") }

    /** Connect to (mac, channel) via raw Linux AF_BLUETOOTH. Returns fd >= 0 or -errno. */
    external fun connect(mac: String, channel: Int): Int

    /** Close a raw fd returned by connect(). */
    external fun close(fd: Int)

    /**
     * Wrap a raw fd in a ParcelFileDescriptor so it can be used with
     * BluetoothSocket.fromFd() – except we can't use that either (hidden).
     * Instead callers use the fd directly via FileInputStream / FileOutputStream.
     */
    fun wrap(fd: Int): ParcelFileDescriptor =
        ParcelFileDescriptor.fromFd(fd)
}

