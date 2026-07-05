package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.domain.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AuthState {
    LOADING,
    FIRST_LAUNCH,
    LOCKED,
    UNLOCKED
}

/**
 * Manages the application's authentication state flow.
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _authState = MutableStateFlow(AuthState.LOADING)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        checkVaultStatus()
    }

    private fun checkVaultStatus() {
        scope.launch {
            _authState.value = AuthState.LOADING
            if (authRepository.hasVault()) {
                _authState.value = AuthState.LOCKED
            } else {
                _authState.value = AuthState.FIRST_LAUNCH
            }
        }
    }

    fun createMasterPassword(password: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            val success = authRepository.createMasterPassword(password)
            if (success) {
                _authState.value = AuthState.UNLOCKED
            }
            onResult(success)
        }
    }

    fun verifyMasterPassword(password: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            val success = authRepository.verifyMasterPassword(password)
            if (success) {
                _authState.value = AuthState.UNLOCKED
            }
            onResult(success)
        }
    }

    fun lock() {
        authRepository.lock()
        _authState.value = AuthState.LOCKED
    }
}
