package dev.earshot.bt

import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.security.spec.InvalidKeySpecException
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the Bragi trust store may give up a stored identity (and with it every trusted
 * Crusher/Method): only when it can never be read again, not on a Keystore hiccup.
 */
class IdentityRestorePolicyTest {

    private var clock = 0L
    private fun policy() = IdentityRestorePolicy(now = { clock })

    @Test fun `an identity that can never be read again is replaced at once`() {
        for (e in listOf(
            MissingWrappingKeyException(),
            AEADBadTagException("tag mismatch"),
            IllegalArgumentException("bad base-64"),
            InvalidKeySpecException("not PKCS#8"),
        )) {
            assertTrue(policy().shouldReplace(e), "${e.javaClass.simpleName} is permanent")
        }
    }

    @Test fun `a Keystore that fails for a moment keeps the identity`() {
        for (e in listOf(
            KeyStoreException("BACKEND_BUSY"),
            ProviderException("Keystore operation failed"),
            UnrecoverableKeyException("Failed to obtain information about key"),
            InvalidKeyException("Keystore operation failed"),
            IllegalStateException("keystore2 restarting"),
        )) {
            assertFalse(IdentityRestorePolicy.isPermanent(e), "${e.javaClass.simpleName} may pass")
            assertFalse(policy().shouldReplace(e), "${e.javaClass.simpleName} keeps the identity")
        }
    }

    @Test fun `a key that keeps failing is replaced after three tries spanning ten minutes`() {
        val p = policy()
        val busy = KeyStoreException("busy")
        clock = 0
        assertFalse(p.shouldReplace(busy))
        clock = 60_000
        assertFalse(p.shouldReplace(busy))
        clock = 120_000
        assertFalse(p.shouldReplace(busy), "three quick failures are still a busy Keystore")
        clock = IdentityRestorePolicy.GIVE_UP_AFTER_MS
        assertTrue(p.shouldReplace(busy), "failing for ten minutes and four connects: not coming back")
    }

    @Test fun `a successful read starts the count again`() {
        val p = policy()
        val busy = ProviderException("busy")
        clock = 0
        assertFalse(p.shouldReplace(busy))
        assertFalse(p.shouldReplace(busy))
        p.onRestored()
        clock = IdentityRestorePolicy.GIVE_UP_AFTER_MS
        assertFalse(p.shouldReplace(busy), "the earlier failures no longer count")
        clock += 1
        assertFalse(p.shouldReplace(busy))
        clock += IdentityRestorePolicy.GIVE_UP_AFTER_MS
        assertTrue(p.shouldReplace(busy), "a new run of three, spanning ten minutes")
    }
}
