package dev.earshot.service.session

import android.content.Context
import dev.earshot.bt.DeviceFinder
import dev.earshot.service.HeadphoneService

/**
 * The foreground service that keeps the process alive while a headset is followed. A seam so a
 * session repository does not call [HeadphoneService] itself: JVM tests pass a recording fake, and
 * a vendor module after the split gets the app's host handed in instead of importing the service.
 */
interface ForegroundHost {
    /**
     * True when the service may start now. Its `connectedDevice` type needs the runtime
     * BLUETOOTH_CONNECT grant; without it the manager reports NO_PERMISSION and the UI asks.
     */
    fun canStart(): Boolean
    fun start()
    fun stop()
}

/** The app's [HeadphoneService], gated on the connect permission [finder] checks. */
class AndroidForegroundHost(context: Context, private val finder: DeviceFinder) : ForegroundHost {
    private val appContext = context.applicationContext

    override fun canStart(): Boolean = finder.hasConnectPermission()
    override fun start() = HeadphoneService.start(appContext)
    override fun stop() = HeadphoneService.stop(appContext)
}
