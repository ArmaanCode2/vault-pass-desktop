package com.vaultpass.desktop.domain.security

object PasswordStrength {

    /**
     * Calculates a password security score based on entropy, diversity, and patterns.
     * Returns:
     * 0 -> Weak
     * 1 -> Medium
     * 2 -> Strong
     */
    fun calculatePasswordScore(password: String): Int {
        if (password.isEmpty()) return 0
        
        var score = 0.0
        val length = password.length

        // 1. Character Diversity (Pool Size)
        var poolSize = 0
        if (password.any { it.isLowerCase() }) poolSize += 26
        if (password.any { it.isUpperCase() }) poolSize += 26
        if (password.any { it.isDigit() }) poolSize += 10
        if (password.any { !it.isLetterOrDigit() }) poolSize += 32

        // 2. Entropy
        // log2(poolSize) * length
        val entropy = if (poolSize > 0) length * (Math.log(poolSize.toDouble()) / Math.log(2.0)) else 0.0
        score += entropy

        // 3. Length Bonuses
        if (length >= 16) score += 10.0
        if (length >= 24) score += 15.0

        // 4. Deductions for Repeated Patterns
        var consecutiveCount = 0
        for (i in 0 until length - 1) {
            if (password[i] == password[i+1]) {
                consecutiveCount++
            }
        }
        score -= (consecutiveCount * 5.0)

        // Subtract points for common sequences/dictionary words
        val lowerPass = password.lowercase()
        val dictionaryWords = listOf("password", "qwerty", "12345", "admin", "welcome", "letmein", "123123")
        for (word in dictionaryWords) {
            if (lowerPass.contains(word)) {
                score -= 30.0
            }
        }

        // Subtract points for sequential characters (abc, 123)
        var sequentialCount = 0
        for (i in 0 until length - 2) {
            val c1 = password[i].code
            val c2 = password[i+1].code
            val c3 = password[i+2].code
            if ((c1 + 1 == c2 && c2 + 1 == c3) || (c1 - 1 == c2 && c2 - 1 == c3)) {
                sequentialCount++
            }
        }
        score -= (sequentialCount * 15.0)

        // Normalize final output
        return when {
            score < 40.0 -> 0     // Weak
            score < 75.0 -> 1     // Medium
            else -> 2             // Strong
        }
    }
}
