package com.lumipos.app.ui.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.schema.ExpenseEntity
import com.lumipos.domain.usecase.expense.AddExpenseUseCase
import com.lumipos.domain.usecase.expense.DeleteExpenseUseCase
import com.lumipos.domain.usecase.expense.GetExpensesUseCase
import com.lumipos.domain.usecase.session.ObserveSessionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

val EXPENSE_CATEGORIES = listOf(
    "Rent", "Salary", "Utilities", "Supplies", "Transport", "Maintenance", "Other"
)

enum class ExpenseRange(val label: String, val days: Int?) {
    ALL("All", null),
    WEEK("7 Days", 7),
    MONTH("30 Days", 30)
}

data class ExpensesUiState(
    val expenses: List<ExpenseEntity> = emptyList(),
    val category: String = "",
    val description: String = "",
    val amount: String = "",
    val range: ExpenseRange = ExpenseRange.ALL,
    val isLoading: Boolean = true,
    val showForm: Boolean = false,
    val pendingDelete: ExpenseEntity? = null,
    val message: String? = null,
    val error: String? = null
) {
    val total: Double get() = expenses.sumOf { it.amount }
}

@HiltViewModel
class ExpensesViewModel @Inject constructor(
    private val getExpensesUseCase: GetExpensesUseCase,
    private val addExpenseUseCase: AddExpenseUseCase,
    private val deleteExpenseUseCase: DeleteExpenseUseCase,
    private val observeSessionUseCase: ObserveSessionUseCase
) : ViewModel() {

    private var createdBy: Long? = null
    private val _uiState = MutableStateFlow(ExpensesUiState())
    val uiState: StateFlow<ExpensesUiState> = _uiState

    private var expensesJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            observeSessionUseCase().first().userId?.let { createdBy = it }
        }
        reload()
    }

    private fun reload() {
        expensesJob?.cancel()
        expensesJob = viewModelScope.launch {
            val range = _uiState.value.range
            val flow = if (range.days == null) {
                getExpensesUseCase.getAllExpenses()
            } else {
                val start = System.currentTimeMillis() - (range.days * 24L * 60L * 60L * 1000L)
                getExpensesUseCase.getExpensesByDateRange(start, System.currentTimeMillis())
            }
            flow.collect { list ->
                _uiState.update { it.copy(expenses = list, isLoading = false) }
            }
        }
    }

    fun setRange(range: ExpenseRange) {
        _uiState.update { it.copy(range = range) }
        reload()
    }

    fun openForm() = _uiState.update {
        it.copy(showForm = true, category = "", description = "", amount = "")
    }
    fun dismissForm() = _uiState.update { it.copy(showForm = false) }

    fun onCategoryChange(value: String) = _uiState.update { it.copy(category = value) }
    fun onDescriptionChange(value: String) = _uiState.update { it.copy(description = value) }
    fun onAmountChange(value: String) = _uiState.update { it.copy(amount = value) }

    fun saveExpense() {
        val state = _uiState.value
        val amount = state.amount.toDoubleOrNull()
        val category = state.category.trim()
        if (category.isEmpty()) {
            _uiState.update { it.copy(error = "Category is required") }
            return
        }
        if (amount == null || amount <= 0) {
            _uiState.update { it.copy(error = "Enter a valid amount") }
            return
        }
        viewModelScope.launch {
            try {
                addExpenseUseCase(
                    category = category,
                    description = state.description.trim(),
                    amount = amount,
                    createdBy = createdBy
                )
                _uiState.update { it.copy(showForm = false, message = "Expense added") }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun requestDelete(expense: ExpenseEntity) = _uiState.update { it.copy(pendingDelete = expense) }

    fun confirmDelete() {
        val expense = _uiState.value.pendingDelete ?: return
        viewModelScope.launch {
            try {
                deleteExpenseUseCase(expense)
                _uiState.update { it.copy(pendingDelete = null, message = "Expense deleted") }
            } catch (e: Exception) {
                _uiState.update { it.copy(pendingDelete = null, error = e.message) }
            }
        }
    }

    fun cancelDelete() = _uiState.update { it.copy(pendingDelete = null) }
    fun dismissMessage() = _uiState.update { it.copy(message = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }
}