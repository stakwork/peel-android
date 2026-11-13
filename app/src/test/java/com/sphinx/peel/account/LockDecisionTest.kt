package com.sphinx.peel.account

import com.sphinx.peel.account.LockDecision.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class LockDecisionTest {
    private fun d(valid: Boolean, on: Boolean, available: Boolean = true) = LockDecision.decide(valid, on, available)

    @Test fun `no valid remembered pin always asks the pin, never biometrics`() {
        assertEquals(Step.AskPin, d(false, false))
        assertEquals(Step.AskPin, d(false, true))
    }

    @Test fun `valid pin and biometrics off opens straight in`() = assertEquals(Step.OpenWithRememberedPin, d(true, false))

    @Test fun `valid pin and biometrics on shows the cover`() = assertEquals(Step.BiometricCover, d(true, true))

    @Test fun `biometrics on but unavailable fails closed to the pin`() = assertEquals(Step.AskPin, d(true, true, false))
}
