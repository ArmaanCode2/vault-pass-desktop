package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.domain.session.SessionManager
import com.vaultpass.desktop.domain.metadata.MetadataRepository
import com.vaultpass.desktop.domain.session.SessionState
import com.vaultpass.desktop.domain.session.UnlockInterruptedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

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
        scope.launch(Dispatchers.IO) {
            try {
                sessionManager.initialize()
                val lastOpened = metadataRepository.getMetadata()?.lastOpenedAt ?: 0L
                withContext(Dispatchers.Main) {
                    _lastOpenedAt.value = lastOpened
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    fun createMasterPassword(password: String, onResult: (Boolean, String?) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val success = sessionManager.vaultCreated(password)
                withContext(Dispatchers.Main) {
                    if (success) {
                        onResult(true, null)
                    } else {
                        onResult(false, "Failed to initialize vault storage. Please verify write permissions.")
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    val message = when {
                        e is UnlockInterruptedException -> e.message
                        e.message?.contains("Access is denied", ignoreCase = true) == true ->
                            "Access Denied: Cannot write vault files to disk. Check folder permissions."
                        e.message?.contains("SQLITE_CANTOPEN", ignoreCase = true) == true ->
                            "Database Error: Unable to open or create vault database file."
                        else -> "Setup failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    }
                    onResult(false, message)
                }
            }
        }
    }

    fun verifyMasterPassword(password: String, onResult: (Boolean, String?) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val success = sessionManager.unlock(password)
                withContext(Dispatchers.Main) {
                    onResult(success, if (success) null else "Incorrect Master Password.")
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult(false, unlockFailureMessage(e))
                }
            }
        }
    }

    companion object {
        /**
         * What the lock screen says when unlocking threw. A lock that won over the unlock is not a
         * wrong password, and says so.
         */
        internal fun unlockFailureMessage(e: Throwable): String = when (e) {
            is UnlockInterruptedException -> e.message ?: "VaultPass was locked while it was unlocking."
            else -> "Unlock error: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    fun lock() {
        sessionManager.lock()
    }
}
