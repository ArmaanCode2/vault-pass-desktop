package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.domain.session.SessionManager
import com.vaultpass.desktop.domain.metadata.MetadataRepository
import com.vaultpass.desktop.domain.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the application's authentication flow, delegating state to the SessionManager.
 */
class AuthViewModel(
    private val sessionManager: SessionManager,
    private val metadataRepository: MetadataRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    val sessionState: StateFlow<SessionState> = sessionManager.sessionState
    
    private val _lastOpenedAt = MutableStateFlow<Long>(0L)
    val lastOpenedAt: StateFlow<Long> = _lastOpenedAt.asStateFlow()

    init {
        scope.launch {
            sessionManager.initialize()
            _lastOpenedAt.value = metadataRepository.getMetadata()?.lastOpenedAt ?: 0L
        }
    }

    fun createMasterPassword(password: String, onResult: (Boolean, String?) -> Unit) {
        scope.launch {
            val success = sessionManager.vaultCreated(password)
            onResult(success, null)
        }
    }

    fun verifyMasterPassword(password: String, onResult: (Boolean, String?) -> Unit) {
        scope.launch {
            // Milestone 2.2: Do not verify passwords. Just unlock.
            val success = sessionManager.unlock(password)
            onResult(success, null)
        }
    }

    fun lock() {
        sessionManager.lock()
    }
}
