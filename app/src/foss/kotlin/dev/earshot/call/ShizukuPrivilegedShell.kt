package dev.earshot.call

import android.content.Context
import android.util.Log
import dev.earshot.codec.ShizukuPrivilege
import dev.earshot.codec.ShizukuShell
import dev.earshot.core.PrivilegeState
import rikka.shizuku.Shizuku

/** [PrivilegedShell] over the app's existing Shizuku shell (GitHub build only). */
class ShizukuPrivilegedShell(
    private val context: Context,
    private val shell: ShizukuShell = ShizukuShell(),
) : PrivilegedShell {
    override fun isAvailable(): Boolean = ShizukuPrivilege.current(context) == PrivilegeState.GRANTED

    override suspend fun exec(cmd: String): Result<String> = shell.exec(cmd)

    override fun setOnAvailableListener(listener: () -> Unit) {
        runCatching { Shizuku.addBinderReceivedListenerSticky { listener() } }
            .onFailure { Log.w("Earshot/CallAudio", "Shizuku listener unavailable: $it") }
    }
}
