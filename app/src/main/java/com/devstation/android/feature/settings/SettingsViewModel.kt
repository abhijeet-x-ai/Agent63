package com.devstation.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.filesystem.StorageStatsCalculator
import com.devstation.android.core.model.AppSettings
import com.devstation.android.core.model.AppTheme
import com.devstation.android.core.model.StorageStats
import com.devstation.android.core.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val storageStats: StorageStats? = null,
    val isLoading: Boolean = false
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val storageStatsCalculator: StorageStatsCalculator
) : ViewModel() {

    private val _storageStats = MutableStateFlow<StorageStats?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.getSettings(),
        _storageStats
    ) { settings, stats ->
        SettingsUiState(
            settings = settings,
            storageStats = stats,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState(isLoading = true)
    )

    init {
        loadStorageInfo()
    }

    private fun loadStorageInfo() {
        viewModelScope.launch {
            _storageStats.value = storageStatsCalculator.calculateStorageStats()
        }
    }

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch {
            settingsRepository.updateTheme(theme)
        }
    }

    fun clearCache(): Boolean {
        val cleared = storageStatsCalculator.clearAppCache()
        loadStorageInfo()
        return cleared
    }

    companion object {
        fun provideFactory(
            settingsRepository: SettingsRepository,
            storageStatsCalculator: StorageStatsCalculator
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(settingsRepository, storageStatsCalculator) as T
            }
        }
    }
}
