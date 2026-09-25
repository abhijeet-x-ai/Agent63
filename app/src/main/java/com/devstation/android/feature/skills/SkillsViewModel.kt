package com.devstation.android.feature.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.skills.SkillDefinition
import com.devstation.android.core.skills.SkillManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    fun dismissMessage() {
        _userMessage.value = null
    }

    fun setEnabled(skillId: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { skillManager.setEnabled(skillId, enabled) }
                .onFailure { _userMessage.value = "Update failed: ${it.message}" }
        }
    }

    fun remove(skillId: String) {
        viewModelScope.launch {
            runCatching { skillManager.removeSkill(skillId) }
                .onFailure { _userMessage.value = "Delete failed: ${it.message}" }
        }
    }

    /** Phase 8.1 §29: create or update a skill from the editor (validated by SkillManager). */
    fun upsert(skill: SkillDefinition) {
        viewModelScope.launch {
            runCatching {
                if (skillManager.skills.value.containsKey(skill.id)) {
                    skillManager.updateSkill(skill)
                } else {
                    skillManager.registerSkill(skill)
                }
            }.onFailure { _userMessage.value = "Save failed: ${it.message}" }
        }
    }

    /** Phase 8.1 §29: duplicate an existing skill under a new name. */
    fun duplicate(skill: SkillDefinition, newName: String) {
        viewModelScope.launch {
            runCatching {
                skillManager.registerSkill(
                    skill.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = newName,
                        source = com.devstation.android.core.skills.SkillSource.USER,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        runCount = 0,
                        lastRunAt = null
                    )
                )
            }.onFailure { _userMessage.value = "Duplicate failed: ${it.message}" }
        }
    }

    /** Phase 8.1 §28: run a skill through the AgentRuntime pipeline. */
    fun run(skill: SkillDefinition, goal: String, projectId: String) {
        if (goal.isBlank() || projectId.isBlank()) return
        viewModelScope.launch {
            runCatching {
                skillManager.executeSkill(
                    skillId = skill.id,
                    goal = goal,
                    projectId = projectId,
                    conversationId = null
                )
            }.onFailure { _userMessage.value = "Run failed: ${it.message}" }
        }
    }

    class Factory(private val skillManager: SkillManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SkillsViewModel(skillManager) as T
    }
}
