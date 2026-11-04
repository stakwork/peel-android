package com.sphinx.peel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.sphinx.peel.account.Account
import com.sphinx.peel.account.AndroidClipboardAccess
import com.sphinx.peel.account.SensitiveClipboard
import com.sphinx.peel.ui.theme.Theme

/// Twelve (or three) places in a two-column grid, each showing its own
/// number. Ported from PeelCore's PhoneControls.swift (`NumberedWords`).
@Composable
fun NumberedWords(
    places: List<Int>,
    focusedIndex: Int? = null,
    modifier: Modifier = Modifier,
    cell: @Composable (index: Int) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        places.indices.chunked(2).forEach { rowIndices ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowIndices.forEach { index ->
                    Row(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Theme.colors.fieldFill)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "${places[index] + 1}",
                            color = Theme.colors.secondaryText,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(20.dp),
                        )
                        Box(Modifier.weight(1f)) { cell(index) }
                    }
                }
                if (rowIndices.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/// One word's input: no Material chrome of its own (no background, no
/// forced minimum height) — `NumberedWords`' row already draws the field's
/// background and rounded corner, and a plain `TextField` here was tall
/// enough on its own to push text past the row's 48dp height and clip
/// descenders (g, p, y) against it.
@Composable
private fun WordField(
    value: String,
    onValueChange: (String) -> Unit,
    imeAction: ImeAction,
    focusRequester: FocusRequester,
    keyboardActions: KeyboardActions,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.focusRequester(focusRequester),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Default, color = Theme.colors.text),
        cursorBrush = SolidColor(Theme.colors.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        keyboardActions = keyboardActions,
    )
}

/// The twelve words, typed or pasted in. Ported from PeelCore's
/// PhoneControls.swift (`WordEntry`): a paste button fills every box at
/// once from a twelve-word clipboard, a single word fills just the one box.
@Composable
fun WordEntry(words: List<String>, onWordsChange: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val focusRequesters = remember { List(words.size) { FocusRequester() } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {
            val text = clipboard.getText()?.text ?: return@Button
            val parts = Account.wordsIn(text)
            if (parts.isEmpty()) return@Button
            // The words are in the boxes now; they should not stay on the clipboard.
            if (SensitiveClipboard.shouldClearAfterPaste(parts.size)) AndroidClipboardAccess(context).clearIfText(context, text)
            val next = words.toMutableList()
            parts.take(next.size).forEachIndexed { i, word -> next[i] = word }
            onWordsChange(next)
        }) { Text("Paste") }

        NumberedWords(places = words.indices.toList()) { index ->
            WordField(
                value = words[index],
                onValueChange = { text ->
                    val parts = Account.wordsIn(text)
                    val next = words.toMutableList()
                    if (parts.size > 1) {
                        parts.take(next.size - index).forEachIndexed { offset, word -> next[index + offset] = word }
                    } else {
                        next[index] = parts.firstOrNull() ?: ""
                    }
                    onWordsChange(next)
                    if (parts.size > 1 && index + parts.size < next.size) {
                        focusRequesters[index + parts.size].requestFocus()
                    }
                },
                imeAction = if (index == words.size - 1) ImeAction.Done else ImeAction.Next,
                focusRequester = focusRequesters[index],
                keyboardActions = KeyboardActions(onNext = {
                    if (index + 1 < focusRequesters.size) focusRequesters[index + 1].requestFocus()
                }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/// Three of the twelve words asked back: their places, chosen at random
/// (`Account.wordsToCheck`), and what was typed for each. Ported from
/// PeelUI's WordCheck.swift.
class WordCheck {
    val positions: List<Int> = Account.wordsToCheck()
    val answers = mutableStateListOf(*Array(positions.size) { "" })

    val isFilled: Boolean get() = answers.all { it.isNotBlank() }
    val byPosition: Map<Int, String> get() = positions.zip(answers).toMap()
}

/// The three boxes a word check asks for. Ported from PeelCore's
/// PhoneControls.swift (`WordCheckBoxes`).
@Composable
fun WordCheckBoxes(check: WordCheck, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    val focusRequesters = remember(check) { List(check.positions.size) { FocusRequester() } }

    NumberedWords(places = check.positions, modifier = modifier) { index ->
        WordField(
            value = check.answers[index],
            onValueChange = { check.answers[index] = Account.wordsIn(it).firstOrNull() ?: it },
            imeAction = if (index == check.positions.size - 1) ImeAction.Done else ImeAction.Next,
            focusRequester = focusRequesters[index],
            keyboardActions = KeyboardActions(
                onNext = { if (index + 1 < focusRequesters.size) focusRequesters[index + 1].requestFocus() },
                onDone = { onSubmit() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
