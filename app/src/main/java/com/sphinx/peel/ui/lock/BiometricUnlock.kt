package com.sphinx.peel.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.account.SecretStoreFailure
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/// BiometricPrompt around the `SecretStore` biometric API. The prompt is
/// always CLASS 3 (strong) with a CryptoObject, so the OS releases the key
/// only after a real biometric match — a bare "success" callback with no
/// CryptoObject could be hooked; a Keystore key that is unusable until the
/// match cannot. There is no device-credential fallback on the prompt: the
/// fallback is Peel's own PIN, which is what the negative button says.
object BiometricUnlock {
    /// Whether this device can do strong biometrics right now (hardware present and enrolled).
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /// Shows the prompt for `cipher`; the authenticated cipher, or null if
    /// cancelled, not matched, or locked out by the OS (the caller falls back to the PIN).
    suspend fun authenticate(activity: FragmentActivity, cipher: Cipher, title: String, subtitle: String?): Cipher? =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(result.cryptoObject?.cipher)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(null)
                    }
                    // onAuthenticationFailed: one non-matching try; the prompt stays up and retries itself.
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Use PIN")
                .setConfirmationRequired(false)
                .build()
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        }

    /// The twelve words after a biometric match; null when the person must
    /// type the PIN instead (cancelled, no match, key invalidated or stale —
    /// the store has already forgotten a dead key).
    suspend fun unlockWords(activity: FragmentActivity, store: SecretStore, title: String, subtitle: String?): String? {
        if (!store.biometricEnrolled) return null
        val cipher = try { store.biometricUnlockCipher() } catch (e: SecretStoreFailure.BiometricUnavailable) { return null }
        val authenticated = authenticate(activity, cipher, title, subtitle) ?: return null
        return try {
            store.loadMnemonicBiometric(authenticated)
        } catch (e: SecretStoreFailure.BiometricUnavailable) {
            null
        }
    }
}
