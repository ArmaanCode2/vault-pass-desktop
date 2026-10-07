package com.vaultpass.desktop.domain.security

import com.vaultpass.desktop.domain.models.VaultEntry
import java.util.concurrent.TimeUnit

data class SecurityReport(
    val score: Int,
    val weakEntries: List<VaultEntry>,
    val reusedEntries: List<VaultEntry>,
    val duplicateEntries: List<VaultEntry>,
    val oldEntries: List<VaultEntry>,
    val missingUrlEntries: List<VaultEntry>,
    val missingUsernameEntries: List<VaultEntry>,
    val recommendations: List<String>
)

object SecurityAnalyzer {
    fun analyze(entries: List<VaultEntry>): SecurityReport {
        if (entries.isEmpty()) {
            return SecurityReport(
                score = 0,
                weakEntries = emptyList(),
                reusedEntries = emptyList(),
                duplicateEntries = emptyList(),
                oldEntries = emptyList(),
                missingUrlEntries = emptyList(),
                missingUsernameEntries = emptyList(),
                recommendations = listOf("Your vault is empty. Start adding passwords securely.")
            )
        }

        // 1. Weak Passwords (Entropy Score == 0)
        val weakEntries = entries.filter { PasswordStrength.calculatePasswordScore(it.secret) == 0 }

        // 2. Reused Passwords (Different entries sharing the exact same secret)
        // Group by secret. If a secret is used in >1 entry, all those entries are "reused".
        val reusedEntries = entries.groupBy { it.secret }
            .filter { it.value.size > 1 }
            .flatMap { it.value }

        // 3. Duplicate Entries (Exact same title, username, and secret)
        // We consider an entry a duplicate if another entry exists with identical core fields.
        val duplicateEntries = entries.groupBy { Triple(it.title, it.username, it.secret) }
            .filter { it.value.size > 1 }
            .flatMap { it.value }

        // 4. Old Passwords (Not updated in > 90 days)
        val ninetyDaysMs = TimeUnit.DAYS.toMillis(90)
        val now = System.currentTimeMillis()
        val oldEntries = entries.filter { now - it.updatedAt > ninetyDaysMs }

        // 5. Missing URLs
        val missingUrlEntries = entries.filter { it.url.isBlank() }

        // 6. Missing Usernames
        val missingUsernameEntries = entries.filter { it.username.isBlank() }

        // Score Calculation
        // Start at 100.
        // -15 for each weak password
        // -10 for each reused password
        // -5 for each duplicate password
        // -2 for each old password
        // -1 for each missing URL
        // -2 for each missing username
        var score = 100
        score -= (weakEntries.size * 15)
        score -= (reusedEntries.size * 10)
        score -= (duplicateEntries.size * 5)
        score -= (oldEntries.size * 2)
        score -= (missingUrlEntries.size * 1)
        score -= (missingUsernameEntries.size * 2)
        score = score.coerceIn(0, 100)

        // Generate Recommendations
        val recommendations = mutableListOf<String>()
        if (weakEntries.isNotEmpty()) {
            recommendations.add("You have ${weakEntries.size} weak password(s). Consider updating them to be stronger.")
        }
        if (reusedEntries.isNotEmpty()) {
            recommendations.add("You are reusing passwords across ${reusedEntries.size} accounts. Use unique passwords for every service.")
        }
        if (oldEntries.isNotEmpty()) {
            recommendations.add("You have ${oldEntries.size} password(s) older than 90 days. It is a good practice to rotate them periodically.")
        }
        if (duplicateEntries.isNotEmpty()) {
            recommendations.add("You have exact duplicate entries in your vault. Consider removing them to keep your vault clean.")
        }
        if (missingUrlEntries.isNotEmpty()) {
            recommendations.add("You have ${missingUrlEntries.size} entry/entries missing a website URL. Adding a URL helps prevent phishing.")
        }
        if (missingUsernameEntries.isNotEmpty()) {
            recommendations.add("You have ${missingUsernameEntries.size} entry/entries missing a username. Adding a username makes it easier to identify accounts.")
        }
        if (score == 100) {
            recommendations.add("Excellent! Your vault is highly secure.")
        }

        return SecurityReport(
            score = score,
            weakEntries = weakEntries.distinctBy { it.id },
            reusedEntries = reusedEntries.distinctBy { it.id },
            duplicateEntries = duplicateEntries.distinctBy { it.id },
            oldEntries = oldEntries.distinctBy { it.id },
            missingUrlEntries = missingUrlEntries.distinctBy { it.id },
            missingUsernameEntries = missingUsernameEntries.distinctBy { it.id },
            recommendations = recommendations
        )
    }
}
