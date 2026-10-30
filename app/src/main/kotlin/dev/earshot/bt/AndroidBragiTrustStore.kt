package dev.earshot.bt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.bragi.BragiHandshake
import java.security.KeyStore
import java.security.spec.InvalidKeySpecException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps this app's long-term identity key and the headphones' trusted identity keys on the phone.
 *
 * The identity private key is stored wrapped with an AES-GCM key that lives in the Android Keystore
 * (so a copy of the preferences file alone cannot be used). An identity that can never be read again
 * (its wrapping key is gone, or the stored bytes don't decrypt or parse) means a fresh identity and a
 * new pairing; a Keystore that fails for a moment keeps the identity and fails only this secure
 * session ([IdentityRestorePolicy]). The trusted server keys are public keys and are stored as they
 * are. Nothing here ever leaves the device.
 */
class AndroidBragiTrustStore(context: Context) : BragiTrustStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val restorePolicy = IdentityRestorePolicy()

    @Synchronized
    override fun identity(): BragiHandshake.EcKeyPair {
        val wrapped = prefs.getString(KEY_PRIVATE, null)
        val point = prefs.getString(KEY_PUBLIC, null)
        if (wrapped != null && point != null) {
            try {
                return BragiHandshake.EcKeyPair.restore(unwrap(decode(wrapped)), decode(point)).also { restorePolicy.onRestored() }
            } catch (e: Exception) {
                if (!restorePolicy.shouldReplace(e)) {
                    // Keystore busy, its service restarting, an OS update still migrating keys: a new
                    // identity would drop every paired Crusher/Method for good. Keep it; the next
                    // connect tries again with the stored identity and server keys intact.
                    BtLog.w("bragi identity could not be read right now (${e.javaClass.simpleName}: ${e.message}); keeping it")
                    throw HeadphoneCommandException("Android's key store didn't answer, so the secure session wasn't set up. Earshot tries again on the next connect", e)
                }
                BtLog.w("bragi identity can't be restored (${e.javaClass.simpleName}: ${e.message}); creating a new one")
            }
        }
        val fresh = BragiHandshake.generateIdentity()
        val edit = prefs.edit()
        // A new identity cannot authenticate against servers that trusted the old one: forget them so
        // the next connect pairs again instead of failing until the user finds the reset button.
        prefs.all.keys.filter { it.startsWith("server_") }.forEach { edit.remove(it) }
        edit.putString(KEY_PRIVATE, encode(wrap(fresh.privateKeyPkcs8())))
            .putString(KEY_PUBLIC, encode(fresh.publicPoint))
            .apply()
        restorePolicy.onRestored()
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

    /** The wrapping key, or null when the Keystore has none under [ALIAS] (KEY_NOT_FOUND, not an error). */
    private fun existingWrappingKey(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return ks.getKey(ALIAS, null) as? SecretKey
    }

    private fun wrappingKey(): SecretKey {
        existingWrappingKey()?.let { return it }
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
        // Never a new key here: one made now could not decrypt the stored blob anyway, and silently
        // replacing the alias would hide that the old key is gone (the reason to start over).
        val key = existingWrappingKey() ?: throw MissingWrappingKeyException()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, IV_BYTES))
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

/** The Keystore has no wrapping key for a stored identity (app data restored onto another phone, keys cleared). */
internal class MissingWrappingKeyException : Exception("the identity's wrapping key is gone")

/**
 * When a stored Bragi identity that failed to restore may be replaced by a new one, which drops
 * every trusted Crusher/Method and makes each pair again with the passcode.
 *
 * Only when it can never be read again ([isPermanent]): the wrapping key is gone, GCM says the key
 * doesn't match the blob, the Keystore invalidated the key, or the stored bytes don't decode or
 * parse. Anything else (a `KeyStoreException`, a `ProviderException`, an `UnrecoverableKeyException`
 * from a busy or restarting keystore2, a generic `InvalidKeyException("Keystore operation failed")`)
 * may pass, so the identity is kept and only this attempt fails. A key that keeps failing that way
 * is not coming back: after [MAX_FAILURES] failures in a row spanning at least [GIVE_UP_AFTER_MS] it
 * is replaced after all, so a broken key can't block the secure session for good. Pure, for the tests.
 */
internal class IdentityRestorePolicy(private val now: () -> Long = System::currentTimeMillis) {
    private var failures = 0
    private var firstFailureAt = 0L

    /** True: start over with a new identity. False: keep the stored one and fail this attempt. */
    fun shouldReplace(e: Throwable): Boolean {
        if (isPermanent(e)) return true
        val at = now()
        if (failures == 0) firstFailureAt = at
        failures++
        return failures >= MAX_FAILURES && at - firstFailureAt >= GIVE_UP_AFTER_MS
    }

    /** The identity was read (or replaced): the next failure starts a new count. */
    fun onRestored() {
        failures = 0
    }

    companion object {
        const val MAX_FAILURES = 3
        const val GIVE_UP_AFTER_MS = 10 * 60_000L

        fun isPermanent(e: Throwable): Boolean = when (e) {
            is MissingWrappingKeyException -> true
            // GCM tag mismatch: the key under the alias is not the one that wrapped the blob.
            is AEADBadTagException -> true
            // A subclass of InvalidKeyException, so before any broader key check.
            is KeyPermanentlyInvalidatedException -> true
            // The stored bytes: bad Base64, a blob shorter than its IV, a point off the curve,
            // PKCS#8 that doesn't parse.
            is IllegalArgumentException, is InvalidKeySpecException -> true
            else -> false
        }
    }
}
