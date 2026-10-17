package dev.earshot.codec.shizuku

import android.annotation.SuppressLint
import android.bluetooth.BluetoothCodecType
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import dev.earshot.codec.CodecNames
import dev.earshot.codec.CodecRequest
import dev.earshot.codec.CodecStatusText
import kotlin.system.exitProcess

/**
 * The privileged half of the codec panel. Shizuku starts this class in its own `app_process`
 * (`dev.earshot:codec`) as the shell user, uid 2000, which holds BLUETOOTH_PRIVILEGED and
 * BLUETOOTH_CONNECT. Such a process has no hidden-API policy, so the hidden `IBluetoothA2dp`
 * calls are plain reflection here; the app itself never touches hidden APIs (HiddenApiBypass
 * segfaults ART on Android 16+).
 *
 * Shizuku 13 instantiates it through the `(Context)` constructor when present, else the no-arg one;
 * both are kept by `proguard-rules.pro`. Everything is logged under `Earshot/CodecSvc`.
 *
 * Binder threads only: nothing here may throw anything but the exception types AIDL can parcel,
 * otherwise the transaction would kill this process, hence [guard].
 */
@Keep
class CodecUserService(private val context: Context?) : ICodecService.Stub() {

    @Keep
    constructor() : this(null)

    private val locator = A2dpBinderLocator(context)
    @Volatile private var calls: Pair<IBinder, A2dpAidlCalls>? = null

    init {
        Log.i(SVC_TAG, "codec service started: uid=${Process.myUid()} pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT} context=${context != null}")
    }

    override fun destroy() {
        Log.i(SVC_TAG, "destroy requested, exiting")
        exitProcess(0)
    }

    override fun getCodecStatus(mac: String): String = guard("getCodecStatus") {
        val status = aidl().getCodecStatus(device(mac))
            ?: throw IllegalStateException("The Bluetooth stack has no codec status for this device (not connected for audio?)")
        status.toString()
    }

    override fun setCodecConfig(mac: String, codecName: String, codecType: Int, sampleRate: Int, bits: Int, specific1: Long): String =
        guard("setCodecConfig") {
            val request = CodecRequest(CodecNames.normalize(codecName), codecType, sampleRate, bits, specific1)
            val device = device(mac)
            val a2dp = aidl()
            val config = CodecConfigFactory.build(request) { a2dp.getSupportedCodecTypes() }
            Log.i(SVC_TAG, "requesting $request")
            val overload = a2dp.setCodecConfigPreference(device, config)
            Log.i(SVC_TAG, "setCodecConfigPreference accepted via $overload; polling status")
            pollUntilApplied(a2dp, device, request)
        }

    override fun supportedCodecs(): List<String> = guard("supportedCodecs") {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return@guard emptyList()
        aidl().getSupportedCodecTypes().mapNotNull { codecTypeName(it) }
    }

    /**
     * `setCodecConfigPreference` is oneway on current builds and the stack renegotiates with the
     * headphones afterwards, so the result only shows up in `getCodecStatus` a moment later.
     */
    private fun pollUntilApplied(a2dp: A2dpAidlCalls, device: BluetoothDevice, request: CodecRequest): String {
        val deadline = SystemClock.uptimeMillis() + POLL_TOTAL_MS
        var last: String? = null
        while (true) {
            SystemClock.sleep(POLL_INTERVAL_MS)
            runCatching { a2dp.getCodecStatus(device)?.toString() }
                .onSuccess { if (it != null) last = it }
                .onFailure { Log.w(SVC_TAG, "status poll failed: ${rootCause(it)}") }
            if (CodecStatusText.currentMatches(last, request.codecName, request.sampleRateMask)) {
                Log.i(SVC_TAG, "${request.codecName} active after ${POLL_TOTAL_MS - (deadline - SystemClock.uptimeMillis())} ms")
                break
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                Log.w(SVC_TAG, "${request.codecName} not active after ${POLL_TOTAL_MS} ms; returning the last status")
                break
            }
        }
        return last ?: throw IllegalStateException("The switch was sent, but the codec status could not be read back")
    }

    @SuppressLint("NewApi") // only called on API 35+
    private fun codecTypeName(type: Any): String? = (type as? BluetoothCodecType)?.codecName

    /** One [A2dpAidlCalls] per binder, so the overload that worked is probed only once. */
    private fun aidl(): A2dpAidlCalls {
        val binder = locator.a2dpBinder()
        calls?.takeIf { it.first === binder }?.let { return it.second }
        return A2dpAidlCalls(binder, context).also { calls = binder to it }
    }

    @SuppressLint("MissingPermission") // getRemoteDevice needs no runtime permission
    private fun device(mac: String): BluetoothDevice {
        val adapter = locator.adapter()
        return try {
            adapter.getRemoteDevice(mac.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Not a Bluetooth address: $mac")
        }
    }

    private inline fun <T> guard(op: String, block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        Log.w(SVC_TAG, "$op refused: ${e.message}")
        locator.invalidate()
        throw SecurityException("$op: ${e.message}")
    } catch (e: IllegalArgumentException) {
        Log.w(SVC_TAG, "$op bad argument: ${e.message}")
        throw e
    } catch (e: IllegalStateException) {
        Log.w(SVC_TAG, "$op failed: ${e.message}")
        locator.invalidate()
        throw IllegalStateException(e.message)
    } catch (t: Throwable) {
        // DeadObjectException (Bluetooth restarted), reflection errors, anything else: AIDL can only
        // carry a few exception types, so everything else becomes an IllegalStateException.
        Log.w(SVC_TAG, "$op failed", t)
        if (t is DeadObjectException) locator.invalidate()
        throw IllegalStateException("$op: ${rootCause(unwrap(t))}")
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
        const val POLL_TOTAL_MS = 2_500L
    }
}
