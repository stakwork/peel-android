package com.sphinx.peel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sphinx.peel.account.Pin
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.delay

/// Six dots, `count` of them filled. Ported from PeelCore's PinPad.swift
/// (`PinDots`).
@Composable
fun PinDots(count: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(Pin.LENGTH) { index ->
            val filled = index < count
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (filled) Theme.colors.text else Theme.colors.fieldFill),
            )
        }
    }
}

/// Six dots filled as digits go in, a 3-by-3 grid of digit keys, an empty
/// bottom-left and Delete bottom-right. Calls `onComplete` at the sixth
/// digit. Ported from PeelCore's PinPad.swift — the lock screen's own
/// keypad specifically (that file's doc comment: "The PIN as the lock
/// screen asks for it... styled like the system's passcode keys"). Onboarding
/// uses `PinBoxes` instead, below.
@Composable
fun PinPad(
    pin: String,
    onPinChange: (String) -> Unit,
    onComplete: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val keySize = 72.dp
    fun press(digit: String) {
        if (!enabled || pin.length >= Pin.LENGTH) return
        val next = pin + digit
        onPinChange(next)
        if (next.length == Pin.LENGTH) onComplete()
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(44.dp)) {
        PinDots(pin.length)
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    row.forEach { digit -> PinKey(digit, keySize, enabled) { press(digit) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                Box(Modifier.size(keySize))
                PinKey("0", keySize, enabled) { press("0") }
                PinSymbolKey("⌫", keySize, enabled = enabled && pin.isNotEmpty()) {
                    if (pin.isNotEmpty()) onPinChange(pin.dropLast(1))
                }
            }
        }
    }
}

@Composable
private fun PinKey(digit: String, size: androidx.compose.ui.unit.Dp, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Theme.colors.fieldFill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(digit, fontSize = 30.sp, color = Theme.colors.text)
    }
}

@Composable
private fun PinSymbolKey(symbol: String, size: androidx.compose.ui.unit.Dp, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 26.sp, color = Theme.colors.text.copy(alpha = if (enabled) 1f else 0.35f))
    }
}

/// Six boxes, filled with a dot as digits go in, over the real (invisible)
/// text input — so the system's own number keyboard does the typing,
/// instead of a second on-screen pad. Ported from PeelCore's
/// PhoneControls.swift (`PinBoxes`): onboarding's PIN entry, as opposed to
/// `PinPad`'s drawn keypad for the lock screen specifically.
@Composable
fun PinBoxes(
    pin: String,
    onPinChange: (String) -> Unit,
    onComplete: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Box(modifier.fillMaxWidth()) {
        BasicTextField(
            value = pin,
            onValueChange = { raw ->
                val digits = Pin.digits(raw)
                if (digits != pin) onPinChange(digits)
                if (digits.length == Pin.LENGTH && digits != pin) onComplete()
            },
            modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focusRequester),
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                ) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(Pin.LENGTH) { index ->
                val filled = index < pin.length
                val focused = enabled && index == pin.length
                Box(
                    Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Theme.colors.fieldFill)
                        .then(
                            if (focused) {
                                Modifier.border(1.dp, Theme.colors.accent, RoundedCornerShape(9.dp))
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (filled) Box(Modifier.size(12.dp).clip(CircleShape).background(Theme.colors.text))
                }
            }
        }
    }

    // Asking for focus while a step is still sliding in gets dropped, same
    // reason PeelCore's own PinBoxes waits before its first focus request.
    LaunchedEffect(Unit) {
        delay(350)
        focusRequester.requestFocus()
        keyboardController?.show()
    }
}

/// A screen that asks for the PIN on the keypad: the title, a line under
/// it, and the pad. Ported from PeelCore's PinPad.swift (`PinPrompt`).
@Composable
fun PinPrompt(
    title: String,
    detail: String?,
    pin: String,
    onPinChange: (String) -> Unit,
    onComplete: () -> Unit,
    working: Boolean = false,
    /// False while a wrong-PIN wait is running (`LockoutPolicy`): the keypad is dimmed and inert.
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    SecureWindow()
    Column(
        modifier.background(Theme.colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Theme.colors.text)
            Text(detail ?: " ", style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
        }
        PinPad(pin = pin, onPinChange = onPinChange, onComplete = onComplete, enabled = !working && enabled)
        // PBKDF2 at a deliberately slow iteration count (SecretStore.kt)
        // runs on every unlock; with no indicator here it just looked
        // frozen for however long that takes on this device.
        if (working) CircularProgressIndicator(color = Theme.colors.accent)
    }
}
