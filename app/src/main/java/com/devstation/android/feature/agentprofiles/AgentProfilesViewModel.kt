package com.devstation.android.feature.agentprofiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.agent.profiles.AgentProfile
import com.devstation.android.core.agent.profiles.AgentProfileManager
import com.devstation.android.core.agent.profiles.AgentProfileResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Phase 8 §28: ViewModel backing the Custom Agents (Agent Profiles) screen.
 */
class AgentProfilesViewModel(
    private val profileManager: AgentProfileManager
) : ViewModel() {

    val profiles: StateFlow<List<AgentProfile>> =
        profileManager.profiles.map { map ->
            map.values.sortedBy { it.name }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfileId: StateFlow<String?> =
        profileManager.activeProfile.map { it?.id }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setActive(profileId: String?) {
        profileManager.setActiveProfile(profileId)
    }

    fun delete(profileId: String) {
        viewModelScope.launch { runCatching { profileManager.deleteProfile(profileId) } }
    }

    class Factory(private val profileManager: AgentProfileManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AgentProfilesViewModel(profileManager) as T
    }
}
