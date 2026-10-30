package dev.earshot.codec.shizuku

import android.content.Context
import android.util.Log
import dev.earshot.codec.CodecRequest
import dev.earshot.codec.PrivilegedCodecAccess
import dev.earshot.codec.ShizukuPrivilege
import dev.earshot.codec.ShizukuShell
import dev.earshot.core.PrivilegeState
import rikka.shizuku.Shizuku

/**
 * [PrivilegedCodecAccess] over Shizuku (GitHub build only): the codec user service
 * ([CodecServiceClient] → [CodecUserService], shell uid) for reads and switches, the Shizuku shell
 * for `dumpsys`, and Shizuku's binder listeners. Shizuku hands its binder over asynchronously
 * through the manifest's `ShizukuProvider`, so the repository re-evaluates whenever it arrives.
 */
internal class ShizukuCodecAccess(private val context: Context) : PrivilegedCodecAccess {

    private val service = CodecServiceClient(context)
    private val shell = ShizukuShell()

    @Volatile private var received: Shizuku.OnBinderReceivedListener? = null
    @Volatile private var dead: Shizuku.OnBinderDeadListener? = null

    override val included: Boolean = true

    override fun state(): PrivilegeState = ShizukuPrivilege.current(context)

    override suspend fun request(): Boolean = ShizukuPrivilege.request(REQUEST_CODE)

    override suspend fun readStatus(address: String): Pair<String, List<String>> = service.withService { svc ->
        svc.getCodecStatus(address) to (runCatching { svc.supportedCodecs() }.getOrNull() ?: emptyList())
    }

    override suspend fun select(address: String, request: CodecRequest): String = service.withService {
        it.setCodecConfig(address, request.codecName, request.codecType, request.sampleRateMask, request.bitsMask, request.codecSpecific1)
    }

    override suspend fun exec(command: String): Result<String> = shell.exec(command)

    override fun watch(onAppeared: () -> Unit, onGone: () -> Unit) {
        val onReceived = Shizuku.OnBinderReceivedListener { onAppeared() }
        val onDead = Shizuku.OnBinderDeadListener {
            // The user service dies with the Shizuku server; forget it so the next call binds again.
            service.reset()
            onGone()
        }
        received = onReceived
        dead = onDead
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(onReceived)
            Shizuku.addBinderDeadListener(onDead)
        }.onFailure { Log.w(TAG, "Shizuku listeners unavailable: $it") }
    }

    override fun close() {
        service.close()
        runCatching {
            received?.let { Shizuku.removeBinderReceivedListener(it) }
            dead?.let { Shizuku.removeBinderDeadListener(it) }
        }
        received = null
        dead = null
    }

    private companion object {
        const val TAG = "Earshot/Codec"
        const val REQUEST_CODE = 0xEA12
    }
}
