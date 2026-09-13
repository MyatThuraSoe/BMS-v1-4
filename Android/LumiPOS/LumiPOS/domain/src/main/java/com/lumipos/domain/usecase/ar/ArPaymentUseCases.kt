package com.lumipos.domain.usecase.ar

import com.lumipos.data.repository.ArPaymentsRepository
import com.lumipos.data.repository.AuditLogsRepository
import com.lumipos.data.repository.CustomersRepository
import com.lumipos.data.schema.ArPaymentEntity
import com.lumipos.data.schema.AuditLogEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetArPaymentsUseCase @Inject constructor(
    private val arPaymentsRepository: ArPaymentsRepository
) {
    fun getAllPayments(): Flow<List<ArPaymentEntity>> = arPaymentsRepository.getAllPayments()
    fun getPaymentsByCustomer(customerId: Long): Flow<List<ArPaymentEntity>> = arPaymentsRepository.getPaymentsByCustomer(customerId)
}

class RecordArPaymentUseCase @Inject constructor(
    private val arPaymentsRepository: ArPaymentsRepository,
    private val customersRepository: CustomersRepository,
    private val auditLogsRepository: AuditLogsRepository
) {
    suspend operator fun invoke(
        customerId: Long,
        amount: Double,
        paymentMethod: String = "CASH",
        invoiceId: Long? = null,
        reference: String = "",
        notes: String = "",
        createdBy: Long? = null
    ): Long {
        if (amount <= 0) throw IllegalArgumentException("Amount must be positive")
        val paymentId = arPaymentsRepository.insertPayment(
            ArPaymentEntity(
                customerId = customerId,
                invoiceId = invoiceId,
                paymentMethod = paymentMethod,
                amount = amount,
                reference = reference,
                notes = notes,
                createdBy = createdBy
            )
        )

        val customer = customersRepository.getCustomerById(customerId)
        if (customer != null) {
            val newBalance = (customer.currentBalance - amount).coerceAtLeast(0.0)
            customersRepository.updateCustomer(customer.copy(currentBalance = newBalance))
        }

        auditLogsRepository.insertLog(
            AuditLogEntity(
                userId = createdBy,
                action = "AR_PAYMENT",
                entityType = "AR_PAYMENT",
                entityId = paymentId,
                detail = "Customer #$customerId paid $amount via $paymentMethod"
            )
        )

        return paymentId
    }
}