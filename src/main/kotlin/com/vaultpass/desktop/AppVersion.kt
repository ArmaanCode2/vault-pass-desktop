package com.vaultpass.desktop

object AppVersion {
    val CURRENT: String by lazy {
        try {
            AppVersion::class.java.getResourceAsStream("/version.txt")
                ?.bufferedReader()
                ?.use { it.readText().trim() }
                ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }
}
