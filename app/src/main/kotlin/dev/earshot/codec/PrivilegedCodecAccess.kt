package dev.earshot.codec

import dev.earshot.core.PrivilegeState

/**
 * The shell-user route behind the codec panel's best read and switch paths. Only the GitHub build
 * has one: Shizuku, in `app/src/foss/kotlin/dev/earshot/codec/shizuku/`. The Google Play build has
 * none (B8 in docs/research/play-readiness.md: fewer things for Play's reviewers and pre-launch
 * report to flag), and keeps the companion-link switch ([AssociationCodecPath]), the codec
 * broadcast and the public readouts. [CodecRepositoryImpl] only talks to this interface, so the
 * shared code names no Shizuku class. Each flavour's `PrivilegedCodecRoute.create` picks one.
 */
internal interface PrivilegedCodecAccess {
    /**
     * False when this build has no such route ([None]): [state] is always UNAVAILABLE, and no
     * message may point the user at it.
     */
    val included: Boolean

    /** Where the route stands right now. Cheap; called on every refresh. */
    fun state(): PrivilegeState

    /** Shows the route's own permission dialog and suspends until the user answers. True when granted. */
    suspend fun request(): Boolean

    /**
     * `BluetoothCodecStatus.toString()` for the sink at [address], plus the phone's codec type names
     * (Android 15+, else empty), through the privileged service. Throws when it can't answer.
     */
    suspend fun readStatus(address: String): Pair<String, List<String>>

    /**
     * Asks for [request] on the sink at [address]. Returns the status string once the stack applied
     * it, or the last one it saw. Throws with a readable message when Android refuses.
     */
    suspend fun select(address: String, request: CodecRequest): String

    /** Runs a shell command as the shell user (`dumpsys bluetooth_manager`). */
    suspend fun exec(command: String): Result<String>

    /**
     * [onAppeared] runs whenever the route may have become usable, [onGone] when it went away
     * (Shizuku's binder arrived or died). Called once, right after construction.
     */
    fun watch(onAppeared: () -> Unit, onGone: () -> Unit)

    /** Stops the privileged service and the watch. */
    fun close()

    /** No route: the Google Play build's answer (and a plain stand-in for tests). */
    object None : PrivilegedCodecAccess {
        private const val MESSAGE = "Not available in this version of Earshot"

        override val included: Boolean = false
        override fun state(): PrivilegeState = PrivilegeState.UNAVAILABLE
        override suspend fun request(): Boolean = false
        override suspend fun readStatus(address: String): Pair<String, List<String>> = throw IllegalStateException(MESSAGE)
        override suspend fun select(address: String, request: CodecRequest): String = throw IllegalStateException(MESSAGE)
        override suspend fun exec(command: String): Result<String> = Result.failure(IllegalStateException(MESSAGE))
        override fun watch(onAppeared: () -> Unit, onGone: () -> Unit) = Unit
        override fun close() = Unit
    }
}
