package com.sphinx.peel.account

import java.security.SecureRandom
import uniffi.sphinxrs.mnemonicFromEntropy
import uniffi.sphinxrs.mnemonicToSeed
import uniffi.sphinxrs.nodeKeys

/// Pure helpers over the sphinxrs core, no stored state: ported from
/// PeelCore's Engine+Account.swift ("Pure helpers" section). Account
/// creation and restore need nothing else; the engine that talks to the
/// server comes later.
object Account {
    private val random = SecureRandom()

    fun generateMnemonic(): String {
        val entropy = ByteArray(16)
        random.nextBytes(entropy)
        return mnemonicFromEntropy(entropy.toHex())
    }

    /// Checks twelve words and returns them in canonical form.
    fun validate(mnemonic: String): String {
        val words = normalise(mnemonic)
        mnemonicToSeed(words)
        return words
    }

    /// The words in whatever someone typed or pasted: any case, separated by
    /// whitespace or commas.
    fun wordsIn(text: String): List<String> =
        text.lowercase().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }

    fun normalise(mnemonic: String): String = wordsIn(mnemonic).joinToString(" ")

    /// The words stay in the caller's array (zero it after); only the
    /// transient `String` the native call needs is created here.
    fun seedOf(mnemonic: CharArray): String = mnemonicToSeed(normalise(String(mnemonic)))

    fun seedOf(mnemonic: String): String = mnemonicToSeed(normalise(mnemonic))

    /// The pubkey this account's seed derives, so a screen can show it
    /// without any server connection.
    fun pubkeyOf(seed: String, net: String = "bitcoin"): String = nodeKeys(net, seed).pubkey

    /// How many of the twelve words a check asks back.
    private const val WORDS_CHECKED = 3

    /// Positions (0 to 11, in order) of the words a check asks back, chosen
    /// at random.
    fun wordsToCheck(): List<Int> = (0..11).shuffled().take(WORDS_CHECKED).sorted()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
