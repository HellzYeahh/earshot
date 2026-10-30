package dev.earshot.codec

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.lang.reflect.Method
import java.util.concurrent.TimeoutException

/**
 * Runs a shell command as the Shizuku user (ADB shell, uid 2000, or root if Shizuku was started
 * with root) and returns its stdout.
 *
 * Shizuku 13 dropped `newProcess` from the public surface of [Shizuku] (it is `private static` in
 * 13.1.5), but the underlying `IShizukuService.newProcess` AIDL method is still served by every
 * Shizuku and Sui release. Two routes, in order:
 *  1. reflection on the private `Shizuku.newProcess(String[], String[], String)`, which returns a
 *     [Process] subclass that already adapts the remote pipes; this is the route most Shizuku apps use;
 *  2. the AIDL directly through `Shizuku.getBinder()`, reading the returned [ParcelFileDescriptor]s
 *     ourselves, in case a future API release removes the private method.
 */
class ShizukuShell {

    suspend fun exec(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "exec `${cmd.take(40)}`")
            if (!Shizuku.pingBinder()) error("Shizuku isn't running")
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) error("Shizuku permission not granted")
            val proc = start(arrayOf("sh", "-c", cmd))
            try {
                coroutineScope {
                    val out = async(Dispatchers.IO) { proc.stdout.use { it.readBytes() } }
                    val err = async(Dispatchers.IO) { proc.stderr.use { it.readBytes() } }
                    // The pipe reads are plain blocking I/O and cannot be interrupted; on timeout we
                    // destroy the process, which closes the pipes and lets both readers finish.
                    val finished = withTimeoutOrNull(timeoutMs) {
                        val stdout = String(out.await())
                        val stderr = String(err.await())
                        val code = proc.waitFor()
                        Triple(stdout, stderr, code)
                    }
                    if (finished == null) {
                        proc.destroy()
                        throw TimeoutException("`${cmd.take(40)}` timed out after ${timeoutMs}ms")
                    }
                    val (stdout, stderr, code) = finished
                    if (code != 0) error("exit $code: ${stderr.ifBlank { stdout }.trim().take(300)}")
                    // Logged so a bug report shows the Shizuku fallback really ran (Android 15+
                    // has no other codec read path).
                    Log.i(TAG, "`${cmd.take(40)}` ok, ${stdout.length} chars")
                    stdout
                }
            } finally {
                runCatching { proc.destroy() }
            }
        }.onFailure {
            if (it is CancellationException) throw it // never turn the caller's cancellation into a Result
            Log.w(TAG, "exec failed: ${it.message}")
        }
    }

    /** Minimal process handle so the two start routes look the same to [exec]. */
    private interface Proc {
        val stdout: InputStream
        val stderr: InputStream
        fun waitFor(): Int
        fun destroy()
    }

    private fun start(cmd: Array<String>): Proc {
        startViaReflection(cmd)?.let { return it }
        return startViaAidl(cmd)
    }

    private fun startViaReflection(cmd: Array<String>): Proc? {
        val method: Method = newProcessMethod ?: return null
        return try {
            val p = method.invoke(null, cmd, null, null) as Process
            object : Proc {
                override val stdout: InputStream get() = p.inputStream
                override val stderr: InputStream get() = p.errorStream
                override fun waitFor() = p.waitFor()
                override fun destroy() = p.destroy()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku.newProcess via reflection failed, falling back to AIDL: $t")
            null
        }
    }

    private fun startViaAidl(cmd: Array<String>): Proc {
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder() ?: error("Shizuku binder missing"))
        val remote: IRemoteProcess = service.newProcess(cmd, null, null)
        // The remote closes the write end once the shell exits; AutoCloseInputStream closes ours.
        val out = ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
        val err = ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)
        // Shizuku's own wrapper does this too: without it the server-side stdin pipe never reaches EOF.
        runCatching { remote.outputStream.close() }
        return object : Proc {
            override val stdout: InputStream get() = out
            override val stderr: InputStream get() = err
            override fun waitFor() = remote.waitFor()
            override fun destroy() { runCatching { remote.destroy() }; runCatching { out.close() }; runCatching { err.close() } }
        }
    }

    companion object {
        private const val TAG = "Earshot/ShizukuShell"
        const val DEFAULT_TIMEOUT_MS = 15_000L

        private val newProcessMethod: Method? by lazy {
            runCatching {
                Shizuku::class.java.getDeclaredMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
                ).apply { isAccessible = true }
            }.onFailure { Log.i(TAG, "Shizuku.newProcess not reflectable (${it.javaClass.simpleName}); AIDL route only") }
                .getOrNull()
        }
    }
}
