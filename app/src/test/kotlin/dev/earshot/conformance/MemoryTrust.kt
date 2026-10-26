package dev.earshot.conformance

import dev.earshot.bt.BragiTrustStore
import dev.earshot.protocol.bragi.BragiHandshake

/**
 * The Bragi pairing trust kept in memory instead of the Android keystore, so a secure Crusher can
 * pair once and reconnect without asking again. A copy of the private one in
 * `VirtualCrusherSecureSessionTest` (F2 phase 1 leaves that test as it is).
 */
class MemoryTrust : BragiTrustStore {
    private val id by lazy { BragiHandshake.generateIdentity() }
    private val servers = HashMap<String, ByteArray>()
    override fun identity() = id
    override fun trustedServer(address: String) = servers[address.uppercase()]
    override fun trust(address: String, serverPoint: ByteArray) { servers[address.uppercase()] = serverPoint }
    override fun forget(address: String) { servers.remove(address.uppercase()) }
}
