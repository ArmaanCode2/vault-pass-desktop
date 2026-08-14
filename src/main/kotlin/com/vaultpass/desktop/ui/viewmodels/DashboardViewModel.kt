package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.VaultRepository
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.security.SecurityAnalyzer
import com.vaultpass.desktop.domain.security.SecurityReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class DashboardUiState(
    val searchQuery: String = "",
    val securityReport: SecurityReport = SecurityAnalyzer.analyze(emptyList()),
    val favorites: List<VaultEntry> = emptyList(),
    val recentActivity: List<VaultEntry> = emptyList(),
    val totalPasswords: Int = 0
)

class DashboardViewModel(private val vaultRepository: VaultRepository) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _searchQuery = MutableStateFlow("")
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var observeJob: kotlinx.coroutines.Job? = null

    init {
        startObserving()
    }

    private fun startObserving() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(
                vaultRepository.observeAllEntries(isDeleted = false),
                _searchQuery
            ) { result, query ->
        println("[DEBUG] DashboardViewModel received result: $result, query: $query")
        if (result is RepositoryResult.Success) {
            val entries = result.data
            println("[DEBUG] DashboardViewModel Success with ${entries.size} entries.")
            
            val filteredEntries = if (query.isNotBlank()) {
                entries.filter { 
                    it.title.contains(query, ignoreCase = true) ||
                    it.username.contains(query, ignoreCase = true) ||
                    it.url.contains(query, ignoreCase = true) ||
                    it.notes.contains(query, ignoreCase = true)
                }
            } else {
                entries
            }
            
            _uiState.value = DashboardUiState(
                searchQuery = query,
                securityReport = SecurityAnalyzer.analyze(entries),
                favorites = filteredEntries.filter { it.isFavorite }.take(6),
                recentActivity = filteredEntries.sortedByDescending { it.updatedAt }.take(3),
                totalPasswords = entries.size
            )
        } else {
            _uiState.value = DashboardUiState(searchQuery = query)
        }
    }.collectLatest { }
        }
    }

    fun clearState() {
        observeJob?.cancel()
        _searchQuery.value = ""
        _uiState.value = DashboardUiState()
        // Restart observing so it's ready for the next unlock
        startObserving()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
