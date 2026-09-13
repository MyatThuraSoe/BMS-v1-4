package com.lumipos.app.ui.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.repository.UsersRepository
import com.lumipos.data.schema.AuditLogEntity
import com.lumipos.data.schema.UserEntity
import com.lumipos.domain.usecase.audit.GetAuditLogsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuditRange(val label: String, val days: Int?) {
    ALL("All", null),
    WEEK("7 Days", 7),
    MONTH("30 Days", 30)
}

data class AuditLogsUiState(
    val logs: List<AuditLogEntity> = emptyList(),
    val usersById: Map<Long, UserEntity> = emptyMap(),
    val range: AuditRange = AuditRange.ALL,
    val actionFilter: String? = null,
    val isLoading: Boolean = true,
    val actions: List<String> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class AuditLogsViewModel @Inject constructor(
    private val getAuditLogsUseCase: GetAuditLogsUseCase,
    private val usersRepository: UsersRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuditLogsUiState())
    val uiState: StateFlow<AuditLogsUiState> = _uiState

    private var logsJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            usersRepository.getAllUsers().collect { users ->
                _uiState.update { it.copy(usersById = users.associateBy { user -> user.id }) }
            }
        }
        reload()
    }

    private fun reload() {
        logsJob?.cancel()
        logsJob = viewModelScope.launch {
            val range = _uiState.value.range
            val flow = if (range.days == null) {
                getAuditLogsUseCase.getAllLogs()
            } else {
                val start = System.currentTimeMillis() - (range.days * 24L * 60L * 60L * 1000L)
                getAuditLogsUseCase.getLogsByDateRange(start, System.currentTimeMillis())
            }
            flow.collect { logs ->
                _uiState.update {
                    it.copy(logs = logs, actions = logs.map { l -> l.action }.distinct().sorted(), isLoading = false)
                }
            }
        }
    }

    fun setRange(range: AuditRange) {
        _uiState.update { it.copy(range = range) }
        reload()
    }

    fun setActionFilter(action: String?) = _uiState.update { it.copy(actionFilter = action) }

    fun dismissError() = _uiState.update { it.copy(error = null) }
}