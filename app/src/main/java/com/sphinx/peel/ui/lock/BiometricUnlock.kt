package com.sphinx.peel.ui.lock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/// BiometricPrompt as a plain SECOND CHECK (no CryptoObject, no secret behind
/// it): a success lets the app use the remembered PIN. The system fallback is
/// the device passcode (DEVICE_CREDENTIAL), never Peel's PIN. Android 9-10
/// do not support STRONG | DEVICE_CREDENTIAL, so there it is WEAK | DEVICE_CREDENTIAL
/// (unverified on those versions).
object BiometricUnlock {
    private val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }

    /// Whether the device can run the check right now (biometrics enrolled or a passcode set).
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    /// "Face ID" on iOS: the word for the settings notes.
    fun name(): String = "biometrics"

    /// Shows the prompt; true only on a successful check (cancel, error, OS lockout: false).
    suspend fun check(activity: FragmentActivity, title: String, subtitle: String? = null): Boolean =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                .setAllowedAuthenticators(authenticators)
                .setConfirmationRequired(false)
                .build()
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(info)
        }
}
