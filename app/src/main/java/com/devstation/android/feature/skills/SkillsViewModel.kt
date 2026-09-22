package com.devstation.android.feature.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.skills.SkillDefinition
import com.devstation.android.core.skills.SkillManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Phase 8 §25: ViewModel backing the Skills screen.
 */
class SkillsViewModel(
    private val skillManager: SkillManager
) : ViewModel() {

    val skills: StateFlow<List<SkillDefinition>> =
        skillManager.skills.map { map ->
            map.values.sortedWith(
                compareBy({ it.source.name }, { it.name })
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(skillId: String, enabled: Boolean) {
        viewModelScope.launch { runCatching { skillManager.setEnabled(skillId, enabled) } }
    }

    fun remove(skillId: String) {
        viewModelScope.launch { runCatching { skillManager.removeSkill(skillId) } }
    }

    class Factory(private val skillManager: SkillManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SkillsViewModel(skillManager) as T
    }
}
