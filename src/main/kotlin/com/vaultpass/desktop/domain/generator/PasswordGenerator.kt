package com.vaultpass.desktop.domain.generator

enum class GeneratorMode { PASSWORD, PASSPHRASE }

data class PasswordConfig(
    val length: Int = 16,
    val includeUppercase: Boolean = true,
    val includeLowercase: Boolean = true,
    val includeNumbers: Boolean = true,
    val includeSymbols: Boolean = true,
    val avoidAmbiguous: Boolean = false // Excludes 0, O, o, 1, l, I, |
)

data class PassphraseConfig(
    val wordCount: Int = 5,
    val separator: String = "-", // "-", ".", "_", " "
    val capitalize: Boolean = true,
    val includeNumber: Boolean = true
)

enum class EntropyRating(val label: String, val colorHex: Long) {
    VERY_WEAK("Very Weak", 0xFFFF4D4D),
    WEAK("Weak", 0xFFFF6B6B),
    MEDIUM("Medium", 0xFFFFB900),
    STRONG("Strong", 0xFF0EA5A1),
    VERY_STRONG("Very Strong", 0xFF10B981)
}

data class GeneratedSecret(
    val value: String,
    val entropyBits: Float,
    val rating: EntropyRating,
    val crackTimeEstimate: String
)

interface PasswordGenerator {
    fun generatePassword(config: PasswordConfig): GeneratedSecret
    fun generatePassphrase(config: PassphraseConfig): GeneratedSecret
    fun calculatePasswordEntropy(length: Int, poolSize: Int): Float
    fun calculatePassphraseEntropy(config: PassphraseConfig, wordlistSize: Int): Float
}
