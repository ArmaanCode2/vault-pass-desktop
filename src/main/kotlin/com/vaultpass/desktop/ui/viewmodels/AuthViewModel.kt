package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.domain.session.SessionManagerImpl
import com.vaultpass.desktop.domain.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Manages the application's authentication flow, delegating state to the SessionManager.
 */
class AuthViewModel(
    private val sessionManager: com.vaultpass.desktop.domain.session.SessionManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    val sessionState: StateFlow<SessionState> = sessionManager.sessionState

    init {
        scope.launch {
            sessionManager.initialize()
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
