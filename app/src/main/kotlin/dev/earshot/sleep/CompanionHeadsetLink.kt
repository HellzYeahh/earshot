package dev.earshot.sleep

import dev.earshot.bt.CompanionRemote

/** [HeadsetLink] over the universal remote's Companion Device Manager link (connect / disconnect on Android 17). */
class CompanionHeadsetLink(private val remote: CompanionRemote) : HeadsetLink {
    override val canConnect: Boolean get() = remote.abilities.canConnect
    override fun isLinked(address: String): Boolean = runCatching { remote.isLinked(address) }.getOrDefault(false)
    override fun connect(address: String) = remote.connect(address)
    override fun disconnect(address: String) = remote.disconnect(address)
}
