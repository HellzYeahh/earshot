package dev.earshot.ui.common

import android.content.Context
import androidx.annotation.StringRes
import dev.earshot.R
import dev.earshot.core.HeadphoneCommandException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** One line for the shared Snackbar. Resolved to text at the root, where a Context is available. */
sealed interface UiMessage {
    data class Text(val text: String) : UiMessage
    data class Resource(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiMessage
    /**
     * The headphones answered BMAP error 5: the firmware doesn't implement changing this setting
     * (not a login gate). [detail] is the repository's own message, kept for logs; the user sees
     * the neutral resource text.
     */
    data class FirmwareUnsupported(val detail: String) : UiMessage

    fun resolve(context: Context): String = when (this) {
        is Text -> text
        is Resource -> context.getString(id, *args.toTypedArray())
        is FirmwareUnsupported -> context.getString(R.string.error_firmware_unsupported)
    }
}

/**
 * Process-wide funnel for one-shot user messages. Every ViewModel action goes through
 * `runCatching { repo.x() }` and reports failures here; the root composable shows them.
 *
 * The app uses [shared]: ViewModels outlive the composition that built them, so a messenger
 * created per composition would still be fed by the ViewModels after a rotation while the new
 * root collects from a fresh, silent instance. Previews and tests may construct their own.
 */
class UiMessenger {
    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<UiMessage> = _messages

    companion object {
        /** The one instance the running app uses. */
        val shared: UiMessenger by lazy { UiMessenger() }
    }

    fun show(message: UiMessage) { _messages.tryEmit(message) }
    fun show(text: String) = show(UiMessage.Text(text))
    fun show(@StringRes id: Int, vararg args: Any) = show(UiMessage.Resource(id, args.toList()))

    fun report(error: Throwable) {
        val message = when {
            error is HeadphoneCommandException && error.notSupportedByFirmware ->
                UiMessage.FirmwareUnsupported(error.message ?: "")
            !error.message.isNullOrBlank() -> UiMessage.Text(error.message!!)
            else -> UiMessage.Resource(R.string.error_generic)
        }
        show(message)
    }
}
