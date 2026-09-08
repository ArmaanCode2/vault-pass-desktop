package com.vaultpass.desktop.domain.generator

import com.vaultpass.desktop.data.generator.SecurePasswordGeneratorImpl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PasswordGeneratorTest {

    private val generator: PasswordGenerator = SecurePasswordGeneratorImpl()

    @Test
    fun testPasswordRespectsConfiguredLength() {
        listOf(8, 16, 24, 32, 64).forEach { length ->
            val secret = generator.generatePassword(PasswordConfig(length = length))
            assertEquals(length, secret.value.length, "Expected password length to be $length")
        }
    }

    @Test
    fun testPasswordGuaranteesSelectedCharacterSets() {
        val uppercaseChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toSet()
        val lowercaseChars = "abcdefghijklmnopqrstuvwxyz".toSet()
        val digitChars = "0123456789".toSet()
        val symbolChars = "!@#$%^&*()_+-=[]{}|;:,.<>?".toSet()

        // Test over 50 iterations to ensure statistical reliability of guaranteed diversity
        for (i in 0 until 50) {
            val secret = generator.generatePassword(
                PasswordConfig(
                    length = 16,
                    includeUppercase = true,
                    includeLowercase = true,
                    includeNumbers = true,
                    includeSymbols = true,
                    avoidAmbiguous = false
                )
            )

            val value = secret.value
            assertTrue(value.any { it in uppercaseChars }, "Password must contain at least 1 uppercase letter: $value")
            assertTrue(value.any { it in lowercaseChars }, "Password must contain at least 1 lowercase letter: $value")
            assertTrue(value.any { it in digitChars }, "Password must contain at least 1 digit: $value")
            assertTrue(value.any { it in symbolChars }, "Password must contain at least 1 symbol: $value")
        }
    }

    @Test
    fun testAvoidAmbiguousExcludesProblematicChars() {
        val ambiguousChars = setOf('0', 'O', 'o', '1', 'l', 'I', '|')

        // Test over 50 iterations with avoidAmbiguous enabled
        for (i in 0 until 50) {
            val secret = generator.generatePassword(
                PasswordConfig(
                    length = 32,
                    includeUppercase = true,
                    includeLowercase = true,
                    includeNumbers = true,
                    includeSymbols = true,
                    avoidAmbiguous = true
                )
            )

            val value = secret.value
            for (ch in value) {
                assertFalse(
                    ch in ambiguousChars,
                    "Generated password must NOT contain ambiguous character '$ch': $value"
                )
            }
        }
    }

    @Test
    fun testPassphraseRespectsWordCountAndSeparator() {
        listOf("-" to 4, "." to 5, "_" to 3, " " to 6).forEach { (sep, count) ->
            val secret = generator.generatePassphrase(
                PassphraseConfig(
                    wordCount = count,
                    separator = sep,
                    capitalize = false,
                    includeNumber = false
                )
            )

            val words = secret.value.split(sep)
            assertEquals(count, words.size, "Expected $count words separated by '$sep' in: ${secret.value}")
            words.forEach { word ->
                assertTrue(word.isNotBlank(), "Each word in passphrase must be non-blank")
            }
        }
    }

    @Test
    fun testPassphraseCapitalizeAndNumber() {
        for (i in 0 until 20) {
            val secret = generator.generatePassphrase(
                PassphraseConfig(
                    wordCount = 5,
                    separator = "-",
                    capitalize = true,
                    includeNumber = true
                )
            )

            val words = secret.value.split("-")
            assertEquals(5, words.size, "Expected 5 words in: ${secret.value}")

            // Verify each word starts with uppercase
            words.forEach { word ->
                assertTrue(word[0].isUpperCase(), "Word '$word' must be capitalized")
            }

            // Verify a digit is included somewhere in the passphrase
            assertTrue(secret.value.any { it.isDigit() }, "Passphrase must contain a digit: ${secret.value}")
        }
    }

    @Test
    fun testEntropyCalculationScalesWithComplexity() {
        // 20-character password with all sets enabled
        val secret20 = generator.generatePassword(
            PasswordConfig(
                length = 20,
                includeUppercase = true,
                includeLowercase = true,
                includeNumbers = true,
                includeSymbols = true,
                avoidAmbiguous = false
            )
        )

        // pool size ~ 91; 20 * log2(91) ~ 130 bits
        assertTrue(secret20.entropyBits >= 80f, "Entropy should be >= 80 bits, was: ${secret20.entropyBits}")
        assertTrue(
            secret20.rating == EntropyRating.STRONG || secret20.rating == EntropyRating.VERY_STRONG,
            "Expected Strong or Very Strong rating, got ${secret20.rating}"
        )

        // 8-character numeric-only password
        val secret8Num = generator.generatePassword(
            PasswordConfig(
                length = 8,
                includeUppercase = false,
                includeLowercase = false,
                includeNumbers = true,
                includeSymbols = false
            )
        )
        // 8 * log2(10) ~ 26.5 bits
        assertTrue(secret8Num.entropyBits < 40f, "8-digit password should have low entropy: ${secret8Num.entropyBits}")
        assertEquals(EntropyRating.VERY_WEAK, secret8Num.rating)
        assertEquals("Instantly", secret8Num.crackTimeEstimate)
    }

    @Test
    fun testEntropyRatingAndCrackTimeEstimates() {
        // Below 40 bits
        val e30 = generator.calculatePasswordEntropy(9, 10) // 9 * 3.32 = ~29.8 bits
        assertTrue(e30 < 40f)

        // 40 to 64 bits
        val e50 = generator.calculatePasswordEntropy(10, 36) // 10 * 5.17 = ~51.7 bits
        assertTrue(e50 in 40f..64.99f)

        // 65 to 79 bits
        val e70 = generator.calculatePasswordEntropy(12, 62) // 12 * 5.95 = ~71.4 bits
        assertTrue(e70 in 65f..79.99f)

        // 80 to 99 bits
        val e85 = generator.calculatePasswordEntropy(14, 70) // 14 * 6.13 = ~85.8 bits
        assertTrue(e85 in 80f..99.99f)

        // >= 100 bits
        val e120 = generator.calculatePasswordEntropy(20, 80) // 20 * 6.32 = ~126.4 bits
        assertTrue(e120 >= 100f)
    }

    @Test
    fun testAllSetsDisabledFallback() {
        val secret = generator.generatePassword(
            PasswordConfig(
                length = 12,
                includeUppercase = false,
                includeLowercase = false,
                includeNumbers = false,
                includeSymbols = false
            )
        )

        assertEquals(12, secret.value.length)
        assertTrue(secret.value.all { it.isLowerCase() }, "Expected fallback to lowercase letters")
    }
}
