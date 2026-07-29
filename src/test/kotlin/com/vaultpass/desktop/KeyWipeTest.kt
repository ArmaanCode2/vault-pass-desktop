package com.vaultpass.desktop

import com.vaultpass.desktop.domain.models.AppSettings
import com.vaultpass.desktop.domain.session.SessionManagerImpl
import com.vaultpass.desktop.data.AuthenticationRepositoryImpl
import com.vaultpass.desktop.data.crypto.*
import com.vaultpass.desktop.data.repository.VaultRepositoryImpl
import com.vaultpass.desktop.ui.viewmodels.AuthViewModel
import com.vaultpass.desktop.ui.viewmodels.VaultViewModel
import com.vaultpass.desktop.ui.viewmodels.DashboardViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import com.vaultpass.desktop.domain.migration.MigrationRunner
import com.vaultpass.desktop.domain.migration.MigrationRegistry

class KeyWipeTest {
    @Test
    fun testStartupKeyWipe() = runBlocking {
        val metadataRepo = com.vaultpass.desktop.data.MetadataRepositoryImpl()
        val kdf = PBKDF2KdfProvider()
        val rnd = SecureRandomProviderImpl()
        val enc = AESGcmEncryptionProvider()
        val keyMgr = InMemoryKeyManager()
        val cryptoMgr = CryptoManagerImpl(kdf, rnd, enc, keyMgr)
        
        val authRepo = AuthenticationRepositoryImpl(metadataRepo, cryptoMgr)
        val connMgr = com.vaultpass.desktop.data.database.SQLiteConnectionManager(System.getProperty("user.home") + "/.vaultpass/vaultpass.db")
        
        val vaultDS = com.vaultpass.desktop.data.database.SQLiteVaultDataSource(connMgr)
        val vaultRepo = VaultRepositoryImpl(vaultDS, cryptoMgr)

        val settingsRepo = object : com.vaultpass.desktop.domain.AppSettingsRepository {
            override suspend fun getSettings(): AppSettings = AppSettings(requireMasterPasswordOnStartup = true)
            override suspend fun saveSettings(settings: AppSettings) {}
            override suspend fun updateSettings(settings: AppSettings) {}
        }
        
        val migrationRegistry = MigrationRegistry()
        val migrationRunner = MigrationRunner(migrationRegistry)
        
        val sessionManager = SessionManagerImpl(
            metadataRepo, authRepo, migrationRunner, keyMgr, connMgr, settingsRepo
        )
        
        val authViewModel = AuthViewModel(sessionManager, metadataRepo)
        val vaultViewModel = VaultViewModel(vaultRepo)
        val dashViewModel = DashboardViewModel(vaultRepo)
        
        println("=== TEST START ===")
        sessionManager.initialize()
        
        // Wait a bit
        delay(500)
        
        // Unlock
        authViewModel.verifyMasterPassword("master") { _, _ -> }
        
        delay(500)
        
        // Trigger UI observation effect
        vaultViewModel.refresh()
        
        // Wait for flows to finish
        delay(2000)
        println("=== TEST END ===")
    }
}
