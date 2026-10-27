package com.sphinx.peel.account

/// The six-digit PIN that unlocks an account on this device. ASCII digits
/// only, so a PIN typed one way can always be typed again. Ported from
/// PeelCore's SecretStore.swift.
object Pin {
    const val LENGTH = 6

    fun isValid(pin: String): Boolean = pin.length == LENGTH && pin.all { it in '0'..'9' }

    /// Strips anything but digits and caps at `LENGTH`, as a PIN field types.
    fun digits(text: String): String = text.filter { it in '0'..'9' }.take(LENGTH)
}
