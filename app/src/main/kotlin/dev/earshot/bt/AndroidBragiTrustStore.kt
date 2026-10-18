package dev.earshot.bt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.earshot.protocol.bragi.BragiHandshake
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps this app's long-term identity key and the headphones' trusted identity keys on the phone.
 *
 * The identity private key is stored wrapped with an AES-GCM key that lives in the Android Keystore
 * (so a copy of the preferences file alone cannot be used), and an unreadable or missing key just
 * means a fresh identity and a new pairing. The trusted server keys are public keys and are stored
 * as they are. Nothing here ever leaves the device.
 */
class AndroidBragiTrustStore(context: Context) : BragiTrustStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun identity(): BragiHandshake.EcKeyPair {
        val wrapped = prefs.getString(KEY_PRIVATE, null)
        val point = prefs.getString(KEY_PUBLIC, null)
        if (wrapped != null && point != null) {
            runCatching { return BragiHandshake.EcKeyPair.restore(unwrap(decode(wrapped)), decode(point)) }
                .onFailure { BtLog.w("bragi identity could not be restored (${it.message}); creating a new one") }
        }
        val fresh = BragiHandshake.generateIdentity()
        val edit = prefs.edit()
        // A new identity cannot authenticate against servers that trusted the old one: forget them so
        // the next connect pairs again instead of failing until the user finds the reset button.
        prefs.all.keys.filter { it.startsWith("server_") }.forEach { edit.remove(it) }
        edit.putString(KEY_PRIVATE, encode(wrap(fresh.privateKeyPkcs8())))
            .putString(KEY_PUBLIC, encode(fresh.publicPoint))
            .apply()
        return fresh
    }

    @Synchronized
    override fun trustedServer(address: String): ByteArray? = prefs.getString(serverKey(address), null)?.let(::decode)

    @Synchronized
    override fun trust(address: String, serverPoint: ByteArray) {
        prefs.edit().putString(serverKey(address), encode(serverPoint)).apply()
    }

    @Synchronized
    override fun forget(address: String) {
        prefs.edit().remove(serverKey(address)).apply()
    }

    private fun serverKey(address: String) = "server_" + address.uppercase()
    private fun encode(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun decode(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        return c.iv + c.doFinal(plain)
    }

    private fun unwrap(wrapped: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, wrapped, 0, IV_BYTES))
        }
        return c.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
    }

    private companion object {
        const val PREFS = "bragi_trust"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "earshot_bragi_identity_wrap"
        const val KEY_PRIVATE = "identity_private"
        const val KEY_PUBLIC = "identity_public"
        const val IV_BYTES = 12
    }
}
