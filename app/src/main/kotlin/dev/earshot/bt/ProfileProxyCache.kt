package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a [ProfileProxyCache] gets its proxies: `BluetoothAdapter.getProfileProxy`,
 * `closeProfileProxy` and `getConnectedDevices` on the phone ([HeldProfileProxies]), a fake in
 * the tests.
 */
internal interface ProfileProxySource<P : Any> {
    /**
     * Starts binding [profile]; the stack answers through [events] later (on a phone, on the main
     * thread). False when the stack refuses the profile. A throw counts as a passing failure.
     */
    fun open(profile: Int, events: ProfileProxyEvents<P>): Boolean

    fun close(profile: Int, proxy: P)

    /** Upper-case addresses connected through [proxy], or null when they cannot be read (permission). Runs on the caller's thread. */
    fun connectedAddresses(proxy: P): Set<String>?
}

/** `BluetoothProfile.ServiceListener`, Android-free. */
internal interface ProfileProxyEvents<P : Any> {
    fun connected(proxy: P)
    fun disconnected()
}

/**
 * One held proxy per Bluetooth profile, for [DeviceFinder]. Until 1.0 every lookup bound a proxy,
 * waited for `onServiceConnected` (delivered on the main thread, so at a cold start it queued
 * behind the Activity and the first frame), asked for the connected devices there and closed it
 * again: 7 to 10 such rounds before a launch's auto-connect got its first answer. Now a profile is
 * bound once, kept, and asked directly on the caller's thread.
 *
 * The answers stay those of a fresh bind, which is what the connect loop, the session-end check
 * and the presence service decide on:
 *  - A proxy whose service disconnected, or that was held when Bluetooth went off, is closed and
 *    forgotten; the next lookup binds afresh. A dead proxy would answer "nothing connected"
 *    (Android returns an empty list without a service), which parks the connect loop.
 *  - An answer read from a proxy that was dropped while it was being asked is null (unknown),
 *    as a lookup that timed out always was.
 *  - The first bind of a profile keeps the old per-lookup timeout. A lookup that times out
 *    gives up on that bind, so the next lookup binds afresh as it always did; a late answer of
 *    the old bind is still taken (and a spare bind closed).
 *  - A profile the stack refuses (`getProfileProxy` false) is remembered until Bluetooth
 *    restarts; a throw is not remembered.
 *
 * Thread-safe. The lock only guards fields: no binder call and no callback runs under it, and the
 * stack's own callbacks on the main thread hold it for a few field writes.
 */
internal class ProfileProxyCache<P : Any>(
    private val source: ProfileProxySource<P>,
    private val timeoutMs: Long,
) {
    private val lock = Any()
    private val slots = HashMap<Int, Slot>()

    /** One `getProfileProxy` call and its listener. */
    private inner class Bind(val profile: Int) : ProfileProxyEvents<P> {
        val ready = CompletableDeferred<P?>()

        /** Its proxy was closed by us: whatever it reports afterwards is ignored. Under [lock]. */
        var closed = false

        override fun connected(proxy: P) = onConnected(this, proxy)
        override fun disconnected() = onDisconnected(this)
    }

    private inner class Slot {
        var proxy: P? = null
        var owner: Bind? = null
        var pending: Bind? = null
        var refused = false

        /** Forgets the held proxy and returns it for closing (outside the lock). Under [lock]. */
        fun release(): P? {
            val held = proxy ?: return null
            owner?.closed = true
            proxy = null
            owner = null
            return held
        }
    }

    private fun slot(profile: Int): Slot = slots.getOrPut(profile) { Slot() }

    /** Starts binding [profile] unless it is held, binding or refused; never waits. */
    fun prime(profile: Int) {
        begin(profile)
    }

    /**
     * Upper-case addresses connected on [profile], or null when no proxy answered in time or its
     * answer could not be trusted (the contract `DeviceFinder` always had).
     */
    suspend fun connectedAddresses(profile: Int): Set<String>? {
        val proxy = proxy(profile) ?: return null
        val addresses = try {
            source.connectedAddresses(proxy)
        } catch (e: Exception) {
            // Before 1.0 this ran inside onServiceConnected on the main thread, where anything
            // but a SecurityException would have crashed the app; now the proxy is treated as dead.
            drop(profile, proxy)
            return null
        }
        return if (isHeld(profile, proxy)) addresses else null
    }

    /** The held proxy for [profile], binding it first if needed; null when none answered in time. */
    suspend fun proxy(profile: Int): P? {
        val ready = begin(profile)
        val proxy = if (ready.isCompleted) ready.await() else withTimeoutOrNull(timeoutMs) { ready.await() }
        if (proxy == null) synchronized(lock) {
            // Lost or late: the next lookup binds afresh. A late answer is still taken (onConnected).
            val slot = slot(profile)
            if (slot.pending?.ready === ready) slot.pending = null
        }
        return proxy
    }

    /** Bluetooth is off: held proxies are dead and waiting lookups get null now instead of after the timeout. */
    fun bluetoothOff() {
        val stale = ArrayList<Pair<Int, P>>()
        val waiting = ArrayList<Bind>()
        synchronized(lock) {
            for ((profile, slot) in slots) {
                slot.release()?.let { stale += profile to it }
                slot.pending?.let(waiting::add)
                slot.pending = null
                slot.refused = false
            }
        }
        for (bind in waiting) bind.ready.complete(null)
        for ((profile, proxy) in stale) closeQuietly(profile, proxy)
    }

    /** Bluetooth is back on: a profile the stack refused before gets another try. */
    fun bluetoothOn() {
        synchronized(lock) { for (slot in slots.values) slot.refused = false }
    }

    /** The deferred to wait on: already complete when the proxy is held or the profile refused. */
    private fun begin(profile: Int): CompletableDeferred<P?> {
        val bind: Bind = synchronized(lock) {
            val slot = slot(profile)
            slot.proxy?.let { return completed(it) }
            if (slot.refused) return completed(null)
            slot.pending?.let { return it.ready }
            Bind(profile).also { slot.pending = it }
        }
        // Outside the lock: the stack may answer from inside this call.
        val started: Boolean? = try {
            source.open(profile, bind)
        } catch (e: Exception) {
            null
        }
        if (started != true) {
            synchronized(lock) {
                val slot = slot(profile)
                if (slot.pending === bind) slot.pending = null
                if (started == false) slot.refused = true
            }
            bind.ready.complete(null)
        }
        return bind.ready
    }

    /**
     * Not `CompletableDeferred<P?>(null)`: with a null argument that picks the `parent: Job?`
     * overload and gives a deferred that never completes (a refused profile then waited out the
     * timeout; ProfileProxyCacheTest caught it).
     */
    private fun completed(value: P?): CompletableDeferred<P?> = CompletableDeferred<P?>().also { it.complete(value) }

    private fun onConnected(bind: Bind, proxy: P) {
        var spare: P? = null
        var waiting: Bind? = null
        val held: P = synchronized(lock) {
            if (bind.closed) return
            val slot = slot(bind.profile)
            waiting = slot.pending
            slot.pending = null
            val current = slot.proxy
            if (current == null) {
                slot.proxy = proxy
                slot.owner = bind
                proxy
            } else {
                if (current !== proxy) {
                    // A bind that answered after another one: one proxy per profile is enough.
                    bind.closed = true
                    spare = proxy
                }
                current
            }
        }
        bind.ready.complete(held)
        waiting?.ready?.complete(held)
        spare?.let { closeQuietly(bind.profile, it) }
    }

    private fun onDisconnected(bind: Bind) {
        val stale: P? = synchronized(lock) {
            val slot = slot(bind.profile)
            if (slot.pending === bind) slot.pending = null
            // Closed, not left to rebind itself: the next lookup binds afresh, as every lookup did.
            if (slot.owner === bind) slot.release() else null
        }
        bind.ready.complete(null)
        stale?.let { closeQuietly(bind.profile, it) }
    }

    private fun drop(profile: Int, proxy: P) {
        val stale = synchronized(lock) { slot(profile).takeIf { it.proxy === proxy }?.release() }
        stale?.let { closeQuietly(profile, it) }
    }

    private fun isHeld(profile: Int, proxy: P): Boolean = synchronized(lock) { slots[profile]?.proxy === proxy }

    /** Some Android versions report onServiceDisconnected from inside close(): state is cleared before. */
    private fun closeQuietly(profile: Int, proxy: P) {
        try {
            source.close(profile, proxy)
        } catch (e: Exception) {
            // Nothing to do: the proxy is forgotten either way.
        }
    }
}

/**
 * The process-wide [ProfileProxyCache] behind every [DeviceFinder] (the stacks', the presence
 * service's, the diagnostics'): one proxy per profile for the whole process, bound on the
 * application context. Bluetooth off drops them, Bluetooth on retries refused profiles.
 *
 * The state receiver is required, not a backup: before Android 13, Bluetooth off unbinds the
 * A2DP proxy silently (BluetoothA2dp on 9/10, BluetoothProfileConnector on 11/12: no
 * `onServiceDisconnected`; `getConnectedDevices` then answers an empty list), and the stack
 * rebinds that same proxy when Bluetooth comes back. Without STATE_OFF a dead proxy would stay
 * held and its empty answer would read as "not linked", parking the connect loop. On newer
 * versions it backs up `onServiceDisconnected`.
 */
@SuppressLint("MissingPermission") // DeviceFinder checks BLUETOOTH_CONNECT before every lookup.
internal object HeldProfileProxies {
    /** The wait for the first `onServiceConnected` of a profile, as each lookup waited before 1.0. */
    const val TIMEOUT_MS = 1_500L

    @Volatile
    private var cache: ProfileProxyCache<BluetoothProfile>? = null

    /** The cache, created on first use; null on a device without Bluetooth. */
    fun get(context: Context): ProfileProxyCache<BluetoothProfile>? {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: create(context.applicationContext ?: context)?.also { cache = it }
        }
    }

    private fun create(app: Context): ProfileProxyCache<BluetoothProfile>? {
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val cache = ProfileProxyCache(AndroidProfileProxySource(app, adapter), TIMEOUT_MS)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> cache.bluetoothOff()
                    BluetoothAdapter.STATE_ON -> cache.bluetoothOn()
                }
            }
        }
        try {
            // A protected system broadcast, as in ConnectionManager. Never unregistered: it lives
            // as long as the process, like the cache.
            ContextCompat.registerReceiver(app, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            BtLog.w("profile proxies: no Bluetooth state receiver (${e.message}); a proxy may go stale across Bluetooth off on Android 12 and older")
        }
        return cache
    }

    private class AndroidProfileProxySource(
        private val context: Context,
        private val adapter: BluetoothAdapter,
    ) : ProfileProxySource<BluetoothProfile> {
        override fun open(profile: Int, events: ProfileProxyEvents<BluetoothProfile>): Boolean {
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) = events.connected(proxy)
                override fun onServiceDisconnected(p: Int) = events.disconnected()
            }
            val started = adapter.getProfileProxy(context, listener, profile)
            if (!started) BtLog.d("profile $profile not offered by this phone (until Bluetooth restarts)")
            return started
        }

        override fun close(profile: Int, proxy: BluetoothProfile) = adapter.closeProfileProxy(profile, proxy)

        override fun connectedAddresses(proxy: BluetoothProfile): Set<String>? = try {
            proxy.connectedDevices.mapTo(HashSet()) { it.address.uppercase() }
        } catch (e: SecurityException) {
            null
        }
    }
}
