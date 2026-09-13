package com.lumipos.domain.usecase.expense

import com.lumipos.data.repository.ExpensesRepository
import com.lumipos.data.schema.ExpenseEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetExpensesUseCase @Inject constructor(
    private val expensesRepository: ExpensesRepository
) {
    fun getAllExpenses(): Flow<List<ExpenseEntity>> = expensesRepository.getAllExpenses()

    fun getExpensesByDateRange(startTime: Long, endTime: Long): Flow<List<ExpenseEntity>> =
        expensesRepository.getExpensesByDateRange(startTime, endTime)
}

class AddExpenseUseCase @Inject constructor(
    private val expensesRepository: ExpensesRepository
) {
    suspend operator fun invoke(
        category: String,
        description: String,
        amount: Double,
        createdBy: Long?
    ): Long {
        return expensesRepository.insertExpense(
            ExpenseEntity(
                category = category,
                description = description,
                amount = amount,
                createdBy = createdBy
            )
        )
    }
}

class DeleteExpenseUseCase @Inject constructor(
    private val expensesRepository: ExpensesRepository
) {
    suspend operator fun invoke(expense: ExpenseEntity) {
        expensesRepository.deleteExpense(expense)
    }
}