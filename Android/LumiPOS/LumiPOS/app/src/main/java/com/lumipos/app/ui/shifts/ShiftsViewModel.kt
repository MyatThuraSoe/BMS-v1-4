package com.lumipos.app.ui.shifts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.schema.CashShiftEntity
import com.lumipos.domain.usecase.session.ObserveSessionUseCase
import com.lumipos.domain.usecase.shift.CloseCashShiftUseCase
import com.lumipos.domain.usecase.shift.GetAllShiftsUseCase
import com.lumipos.domain.usecase.shift.GetCurrentOpenShiftUseCase
import com.lumipos.domain.usecase.shift.OpenCashShiftUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShiftsUiState(
    val shifts: List<CashShiftEntity> = emptyList(),
    val currentShift: CashShiftEntity? = null,
    val isLoading: Boolean = true,
    val showOpenDialog: Boolean = false,
    val openingAmount: String = "",
    val openingNotes: String = "",
    val showCloseDialog: Boolean = false,
    val closingAmount: String = "",
    val closeNotes: String = "",
    val isSaving: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ShiftsViewModel @Inject constructor(
    private val getAllShiftsUseCase: GetAllShiftsUseCase,
    private val getCurrentOpenShiftUseCase: GetCurrentOpenShiftUseCase,
    private val openCashShiftUseCase: OpenCashShiftUseCase,
    private val closeCashShiftUseCase: CloseCashShiftUseCase,
    private val observeSessionUseCase: ObserveSessionUseCase
) : ViewModel() {

    private var currentUserId: Long? = null
    private val _uiState = MutableStateFlow(ShiftsUiState())
    val uiState: StateFlow<ShiftsUiState> = _uiState

    init {
        viewModelScope.launch {
            currentUserId = observeSessionUseCase().first().userId
            loadShifts()
        }
    }

    private var shiftsJob: kotlinx.coroutines.Job? = null

    private fun loadShifts() {
        shiftsJob?.cancel()
        shiftsJob = viewModelScope.launch {
            getAllShiftsUseCase().collect { list ->
                _uiState.update { it.copy(shifts = list.sortedByDescending { s -> s.openingTime }, isLoading = false) }
            }
        }
    }

    fun refreshCurrentShift() {
        viewModelScope.launch {
            val current = getCurrentOpenShiftUseCase()
            _uiState.update { it.copy(currentShift = current) }
        }
    }

    fun openOpenDialog() = _uiState.update { it.copy(showOpenDialog = true, openingAmount = "", openingNotes = "") }

    fun dismissOpenDialog() = _uiState.update { it.copy(showOpenDialog = false) }

    fun setOpeningAmount(value: String) = _uiState.update { it.copy(openingAmount = value) }

    fun setOpeningNotes(value: String) = _uiState.update { it.copy(openingNotes = value) }

    fun openShift() {
        val userId = currentUserId ?: return
        val amount = _uiState.value.openingAmount.toDoubleOrNull() ?: 0.0
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                openCashShiftUseCase(userId, amount, _uiState.value.openingNotes)
                _uiState.update { it.copy(isSaving = false, showOpenDialog = false) }
                refreshCurrentShift()
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }

    fun openCloseDialog() = _uiState.update {
        it.copy(showCloseDialog = true, closingAmount = "", closeNotes = "")
    }

    fun dismissCloseDialog() = _uiState.update { it.copy(showCloseDialog = false) }

    fun setClosingAmount(value: String) = _uiState.update { it.copy(closingAmount = value) }

    fun setCloseNotes(value: String) = _uiState.update { it.copy(closeNotes = value) }

    fun closeShift() {
        val shift = _uiState.value.currentShift ?: return
        val amount = _uiState.value.closingAmount.toDoubleOrNull() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                closeCashShiftUseCase(shift.id, amount, _uiState.value.closeNotes)
                _uiState.update { it.copy(isSaving = false, showCloseDialog = false) }
                refreshCurrentShift()
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }
}