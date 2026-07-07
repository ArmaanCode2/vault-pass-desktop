package com.vaultpass.desktop.domain.security

import com.vaultpass.desktop.domain.models.VaultEntry
import java.util.concurrent.TimeUnit

data class SecurityReport(
    val score: Int,
    val weakEntries: List<VaultEntry>,
    val reusedEntries: List<VaultEntry>,
    val duplicateEntries: List<VaultEntry>,
    val oldEntries: List<VaultEntry>,
    val recommendations: List<String>
)

object SecurityAnalyzer {
    fun analyze(entries: List<VaultEntry>): SecurityReport {
        if (entries.isEmpty()) {
            return SecurityReport(
                score = 100,
                weakEntries = emptyList(),
                reusedEntries = emptyList(),
                duplicateEntries = emptyList(),
                oldEntries = emptyList(),
                recommendations = listOf("Your vault is empty. Start adding passwords securely.")
            )
        }

        // 1. Weak Passwords (Length < 8)
        val weakEntries = entries.filter { it.secret.length < 8 }

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

        // Score Calculation
        // Start at 100.
        // -10 for each weak password
        // -5 for each reused password
        // -2 for each old password
        var score = 100
        score -= (weakEntries.size * 10)
        score -= (reusedEntries.size * 5)
        score -= (oldEntries.size * 2)
        score = score.coerceIn(0, 100)

        // Generate Recommendations
        val recommendations = mutableListOf<String>()
        if (weakEntries.isNotEmpty()) {
            recommendations.add("You have ${weakEntries.size} weak password(s). Consider updating them to be at least 8 characters long.")
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
        if (score == 100) {
            recommendations.add("Excellent! Your vault is highly secure.")
        }

        return SecurityReport(
            score = score,
            weakEntries = weakEntries.distinctBy { it.id },
            reusedEntries = reusedEntries.distinctBy { it.id },
            duplicateEntries = duplicateEntries.distinctBy { it.id },
            oldEntries = oldEntries.distinctBy { it.id },
            recommendations = recommendations
        )
    }
}
