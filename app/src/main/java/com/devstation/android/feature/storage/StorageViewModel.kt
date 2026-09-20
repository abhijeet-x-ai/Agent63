package com.devstation.android.feature.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.filesystem.StorageStatsCalculator
import com.devstation.android.core.model.StorageStats
import com.devstation.android.core.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StorageUiState(
    val stats: StorageStats? = null,
    val isLoading: Boolean = false,
    val userMessage: String? = null
)

class StorageViewModel(
    private val storageStatsCalculator: StorageStatsCalculator,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StorageUiState(isLoading = true))
    val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

    init {
        refreshStorage()
    }

    fun refreshStorage() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            projectRepository.refreshProjectSizes()
            val stats = storageStatsCalculator.calculateStorageStats()
            _uiState.value = _uiState.value.copy(
                stats = stats,
                isLoading = false,
                userMessage = "Storage metrics updated"
            )
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            val cleared = storageStatsCalculator.clearAppCache()
            if (cleared) {
                val stats = storageStatsCalculator.calculateStorageStats()
                _uiState.value = _uiState.value.copy(
                    stats = stats,
                    userMessage = "Application cache cleared safely"
                )
            } else {
                _uiState.value = _uiState.value.copy(userMessage = "Could not clear cache")
            }
        }
    }

    fun dismissUserMessage() {
        _uiState.value = _uiState.value.copy(userMessage = null)
    }

    companion object {
        fun provideFactory(
            storageStatsCalculator: StorageStatsCalculator,
            projectRepository: ProjectRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return StorageViewModel(storageStatsCalculator, projectRepository) as T
            }
        }
    }
}
