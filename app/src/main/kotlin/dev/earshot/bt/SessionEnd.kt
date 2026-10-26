package dev.earshot.bt

/**
 * What [ConnectionManager] tells the user, and how it counts the attempt, when an established
 * session ends. Kept apart from the manager (which needs a Context) so the policy is unit-testable.
 */
internal data class SessionEnd(
    /** Shown as `ConnectionState.message`; null after a user stop. */
    val message: String?,
    /**
     * True when the session must count as a failed connect attempt for [ReconnectBackoff]: it
     * opened but never really worked, so retrying it on the fast schedule forever would hammer
     * the channel. That way the slow 5-minute pulse eventually applies.
     */
    val countsAsFailedAttempt: Boolean,
) {
    companion object {
        const val MESSAGE_CONNECTION_LOST = "Connection lost"
        const val MESSAGE_NOT_LINKED = "Headphones are not connected to this phone"

        /**
         * @param cause what [BmapConnection.awaitClosed] returned.
         * @param linked [DeviceFinder.isAudioConnected] for the device (null = unknown).
         */
        fun classify(cause: Throwable?, userStopped: Boolean, linked: Boolean?): SessionEnd = when {
            userStopped -> SessionEnd(null, countsAsFailedAttempt = false)
            // Socket open, nobody answering: another app (the Bose app) holds BMAP channel 2,
            // unless the headphones simply went away for audio too.
            cause is UnresponsiveLinkException -> SessionEnd(
                if (linked == false) MESSAGE_NOT_LINKED else ConnectionManager.MESSAGE_CHANNEL_BUSY,
                countsAsFailedAttempt = true,
            )
            else -> SessionEnd(MESSAGE_CONNECTION_LOST, countsAsFailedAttempt = false)
        }

        /**
         * The message for a connect that never became a session (socket refused, or opened but
         * failed the [LivenessProbe]). The busy signature is only trusted while the phone still
         * has the headphones linked: out of range produces the same socket errors.
         */
        fun connectFailureMessage(e: Throwable, linked: Boolean?): String = when {
            linked == false -> MESSAGE_NOT_LINKED
            e is RfcommConnectException && e.looksBusy -> ConnectionManager.MESSAGE_CHANNEL_BUSY
            e is ExplainedConnectException -> e.message ?: MESSAGE_CONNECT_FAILED
            else -> MESSAGE_CONNECT_FAILED
        }

        const val MESSAGE_CONNECT_FAILED = "Could not connect to the headphones"
    }
}
