package com.sphinx.peel.diagnostics

import com.sphinx.peel.account.LockDecision

/// Why the lock screen was shown, as the diagnostics name it (iOS `LockReason`): pure, so the mapping
/// is tested. "Ask for the PIN: every time" (timeout 0) is `everyOpen`; a run-out timeout `idleTimeout`;
/// the biometric cover `biometricCheck`; a launch that opened with the kept PIN `rememberedPIN`.
object LockTrace {
    fun reason(step: LockDecision.Step, timeoutHours: Int): DiagnosticEvent.LockReason = when (step) {
        LockDecision.Step.BiometricCover -> DiagnosticEvent.LockReason.biometricCheck
        LockDecision.Step.OpenWithRememberedPin -> DiagnosticEvent.LockReason.rememberedPIN
        LockDecision.Step.AskPin -> if (timeoutHours == 0) DiagnosticEvent.LockReason.everyOpen else DiagnosticEvent.LockReason.idleTimeout
    }
}
