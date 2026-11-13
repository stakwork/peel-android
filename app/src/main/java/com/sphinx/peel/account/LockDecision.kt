package com.sphinx.peel.account

/// What the lock screen does when the app opens (cold launch or return from
/// the background). Pure, so the rule is unit-tested. Mirrors the iOS
/// LockScreen: biometrics are a SECOND check on top of a remembered PIN,
/// never a replacement for the PIN.
object LockDecision {
    enum class Step {
        /// Show the keypad (timeout 0, expired, nothing kept, or biometrics unusable).
        AskPin,
        /// Open with the remembered PIN, no prompt at all.
        OpenWithRememberedPin,
        /// "Peel is locked": no keypad, a biometric check opens it with the remembered PIN.
        BiometricCover,
    }

    fun decide(rememberedPinValid: Boolean, biometricsEnabled: Boolean, biometricsAvailable: Boolean): Step = when {
        !rememberedPinValid -> Step.AskPin
        !biometricsEnabled -> Step.OpenWithRememberedPin
        // Enabled but the device can no longer check (all lock screen security
        // removed): fail closed, the PIN opens it.
        !biometricsAvailable -> Step.AskPin
        else -> Step.BiometricCover
    }
}
