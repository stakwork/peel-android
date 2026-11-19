package com.sphinx.peel.account

import java.security.SecureRandom
import uniffi.sphinxrs.mnemonicFromEntropy
import uniffi.sphinxrs.mnemonicToBip39Seed
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

    /// The full BIP39 seed (64 bytes, hex) of the words: what LUD-05's per-domain key derives from
    /// (`signBytesLud05`). Not the Sphinx seed above, which is what the core takes everywhere else.
    fun bip39SeedOf(mnemonic: CharArray): String = mnemonicToBip39Seed(normalise(String(mnemonic)))

    fun seedOf(mnemonic: String): String = mnemonicToSeed(normalise(mnemonic))

    /// The pubkey this account's seed derives, so a screen can show it
    /// without any server connection.
    fun pubkeyOf(seed: String, net: String = "bitcoin"): String = nodeKeys(net, seed).pubkey

    /// How many of the twelve words a check asks back.
    private const val WORDS_CHECKED = 3

    /// Positions (0 to 11, in order) of the words a check asks back, chosen
    /// at random.
    fun wordsToCheck(): List<Int> = (0..11).shuffled().take(WORDS_CHECKED).sorted()

    /// The places (0 to 11, in order) whose answer is not the word there.
    fun wrongPlaces(answers: Map<Int, String>, words: List<String>): List<Int> =
        answers.filter { (place, answer) -> wordsIn(answer) != listOf(words[place]) }.keys.sorted()

    /// What a check with wrong places says, numbered from 1.
    fun wrongPlacesProblem(wrong: List<Int>): String {
        val many = wrong.size > 1
        return "Word${if (many) "s" else ""} ${wrong.joinToString(", ") { "${it + 1}" }} ${if (many) "don't" else "doesn't"} match."
    }

    /// Whether `Setting.WORDS_CONFIRMED` says the words were checked. Signup writes "0", a passed check
    /// "1"; a restore writes "1" (typing all twelve back is the check), and one restored before it did has none.
    fun wordsChecked(setting: String?): Boolean = setting != "0"
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
