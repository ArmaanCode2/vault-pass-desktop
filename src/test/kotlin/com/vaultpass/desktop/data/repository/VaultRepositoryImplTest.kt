package com.vaultpass.desktop.data.repository

import com.vaultpass.desktop.domain.crypto.CryptoManager
import com.vaultpass.desktop.domain.db.VaultLocalDataSource
import com.vaultpass.desktop.data.models.VaultEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import kotlinx.coroutines.flow.first

class VaultRepositoryImplTest {

    @Test
    fun `observeAllEntries uses cache for same updatedAt`() = runBlocking {
        // Arrange
        val mockDataSource = mock<VaultLocalDataSource>()
        val mockCryptoManager = mock<CryptoManager>()
        
        val repository = VaultRepositoryImpl(mockDataSource, mockCryptoManager)
        
        val dummyPayload = """{"title":"Test","username":"user","secret":"decrypted_secret","url":"","notes":"","category":null,"tags":[],"history":[]}""".toByteArray()
        val entity1 = VaultEntity(
            id = "1",
            type = "password",
            encryptedPayload = "encrypted".toByteArray(),
            createdAt = 1000L,
            updatedAt = 2000L,
            isFavorite = false,
            isDeleted = false,
            deletedAt = null,
            syncVersion = 1
        )
        
        whenever(mockDataSource.observeAll()).thenReturn(flowOf(listOf(entity1)))
        
        // Act 1: First collection should decrypt
        whenever(mockCryptoManager.decryptData(any())).thenReturn(dummyPayload)
        val result1 = repository.observeAllEntries(false).first()
        
        // Act 2: Second collection should use cache
        val result2 = repository.observeAllEntries(false).first()
        
        // Assert: CryptoManager should only be called once because of cache
        verify(mockCryptoManager, times(1)).decryptData(any())
        
        // Act 3: Update timestamp
        val entity2 = entity1.copy(updatedAt = 3000L)
        whenever(mockDataSource.observeAll()).thenReturn(flowOf(listOf(entity2)))
        
        val result3 = repository.observeAllEntries(false).first()
        
        // Assert: CryptoManager called again because updatedAt changed
        verify(mockCryptoManager, times(2)).decryptData(any())
        
        // Act 4: Clear cache
        repository.clearCache()
        val result4 = repository.observeAllEntries(false).first()
        
        // Assert: CryptoManager called again because cache was cleared
        verify(mockCryptoManager, times(3)).decryptData(any())
    }
}
