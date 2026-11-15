package com.example.lumipos.ui.expenses

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.Expense
import com.example.lumipos.data.repository.ExpenseRepository
import com.example.lumipos.util.DateFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Window the expense list is narrowed to. */
enum class ExpensePeriod { THIS_MONTH, THIS_YEAR, ALL_TIME }

class ExpensesViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val expenseRepo = ExpenseRepository(db.expenseDao())

    private val allExpenses: LiveData<List<Expense>> = expenseRepo.allExpenses

    private val _period = MutableLiveData(ExpensePeriod.THIS_MONTH)
    val period: LiveData<ExpensePeriod> = _period

    /** null means "all categories". */
    private val _category = MutableLiveData<String?>(null)
    val category: LiveData<String?> = _category

    private val expensesMediator = MediatorLiveData<List<Expense>>()
    val expenses: LiveData<List<Expense>> = expensesMediator

    private val _total = MutableLiveData(0.0)
    val total: LiveData<Double> = _total

    init {
        // `allExpenses` re-emits on any change to the expenses table, so filtering in memory here
        // keeps the list and the total reactive - a new or deleted expense is reflected without an
        // explicit reload. ExpenseDao exposes no filtered LiveData query to do this in SQL.
        expensesMediator.addSource(allExpenses) { recompute() }
        expensesMediator.addSource(_period) { recompute() }
        expensesMediator.addSource(_category) { recompute() }
    }

    /**
     * Single place that republishes both the list and the total, so they can never disagree.
     *
     * Deliberately not written as `MediatorLiveData().apply { addSource(x) { value = ... } }`:
     * the SAM parameter of `Observer` is itself named `value`, so inside that lambda `value`
     * refers to the incoming source item, not to the mediator's value.
     */
    private fun recompute() {
        val filtered = applyFilters()
        expensesMediator.value = filtered
        _total.value = filtered.sumOf { it.amount }
    }

    private fun applyFilters(): List<Expense> {
        val from = when (_period.value ?: ExpensePeriod.ALL_TIME) {
            ExpensePeriod.THIS_MONTH -> DateFormatter.getMonthStart()
            ExpensePeriod.THIS_YEAR -> DateFormatter.getYearStart()
            ExpensePeriod.ALL_TIME -> 0L
        }
        val wanted = _category.value
        return (allExpenses.value ?: emptyList()).filter { expense ->
            expense.expenseDate >= from && (wanted == null || expense.category == wanted)
        }
    }

    fun setPeriod(period: ExpensePeriod) {
        _period.value = period
    }

    fun setCategory(category: String?) {
        _category.value = category
    }

    fun deleteExpense(expense: Expense) {
        viewModelScope.launch(Dispatchers.IO) {
            expenseRepo.delete(expense)
        }
    }
}

/**
 * Add/edit form for a single expense.
 *
 * Split from [ExpensesViewModel] to match the other form screens. Loading first matters here for
 * the same reason it did for categories: the entity carries `createdAt` (defaulting to
 * `System.currentTimeMillis()`) and `receiptImageBytes`, so rebuilding a fresh entity from the
 * form would reset the creation stamp and drop the receipt on every edit.
 */
class ExpenseFormViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val expenseRepo = ExpenseRepository(db.expenseDao())

    private val _expense = MutableLiveData<Expense?>()
    val expense: LiveData<Expense?> = _expense

    private val _saveSuccess = MutableLiveData<Boolean>()
    val saveSuccess: LiveData<Boolean> = _saveSuccess

    fun loadExpense(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _expense.postValue(expenseRepo.getById(id))
        }
    }

    fun saveExpense(expense: Expense) {
        viewModelScope.launch(Dispatchers.IO) {
            if (expense.id == 0L) {
                expenseRepo.insert(expense)
            } else {
                expenseRepo.update(expense)
            }
            withContext(Dispatchers.Main) {
                _saveSuccess.value = true
            }
        }
    }
}
