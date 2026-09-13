package com.lumipos.domain.usecase.shift

import com.lumipos.data.repository.CashShiftsRepository
import com.lumipos.data.repository.SalesRepository
import com.lumipos.data.schema.CashShiftEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetCurrentOpenShiftUseCase @Inject constructor(
    private val cashShiftsRepository: CashShiftsRepository
) {
    suspend operator fun invoke(): CashShiftEntity? = cashShiftsRepository.getCurrentOpenShift()
}

class GetShiftByIdUseCase @Inject constructor(
    private val cashShiftsRepository: CashShiftsRepository
) {
    suspend operator fun invoke(id: Long): CashShiftEntity? = cashShiftsRepository.getShiftById(id)
}

class GetAllShiftsUseCase @Inject constructor(
    private val cashShiftsRepository: CashShiftsRepository
) {
    operator fun invoke(): Flow<List<CashShiftEntity>> = cashShiftsRepository.getAllShifts()
}

class OpenCashShiftUseCase @Inject constructor(
    private val cashShiftsRepository: CashShiftsRepository
) {
    suspend operator fun invoke(cashierId: Long, openingAmount: Double, notes: String = ""): Long {
        val existing = cashShiftsRepository.getOpenShiftByCashier(cashierId)
        if (existing != null) return existing.id
        return cashShiftsRepository.insertShift(
            CashShiftEntity(
                cashierId = cashierId,
                openingAmount = openingAmount,
                openingTime = System.currentTimeMillis(),
                status = "OPEN",
                notes = notes
            )
        )
    }
}

class CloseCashShiftUseCase @Inject constructor(
    private val cashShiftsRepository: CashShiftsRepository,
    private val salesRepository: SalesRepository
) {
    suspend operator fun invoke(shiftId: Long, closingAmount: Double, notes: String = "") {
        val shift = cashShiftsRepository.getShiftById(shiftId) ?: return
        if (shift.status != "OPEN") return
        val sales = salesRepository.getSalesByShift(shiftId)
        val expected = shift.openingAmount + sales.sumOf { it.total }
        val variance = closingAmount - expected
        cashShiftsRepository.updateShift(
            shift.copy(
                closingAmount = closingAmount,
                closingTime = System.currentTimeMillis(),
                expectedAmount = expected,
                variance = variance,
                status = "CLOSED",
                notes = notes.ifBlank { shift.notes }
            )
        )
    }
}