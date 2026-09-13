package com.lumipos.domain.usecase.sale

import com.lumipos.data.database.TransactionRunner
import com.lumipos.data.repository.AuditLogsRepository
import com.lumipos.data.repository.CustomersRepository
import com.lumipos.data.repository.ProductsRepository
import com.lumipos.data.repository.SaleReturnsRepository
import com.lumipos.data.repository.SalesRepository
import com.lumipos.data.repository.StockMovementsRepository
import com.lumipos.data.schema.AuditLogEntity
import com.lumipos.data.schema.SaleEntity
import com.lumipos.data.schema.SaleItemEntity
import com.lumipos.data.schema.SaleReturnEntity
import com.lumipos.data.schema.SaleReturnItemEntity
import com.lumipos.data.schema.StockMovementEntity
import com.lumipos.domain.usecase.sequence.NextInvoiceNumberUseCase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetSalesUseCase @Inject constructor(
    private val salesRepository: SalesRepository
) {
    fun getSalesByBranch(branchId: Long): Flow<List<SaleEntity>> = salesRepository.getSalesByBranch(branchId)

    fun getSalesByDateRange(startTime: Long, endTime: Long, branchId: Long): Flow<List<SaleEntity>> =
        salesRepository.getSalesByDateRange(startTime, endTime, branchId)

    suspend operator fun invoke(id: Long): SaleEntity? = salesRepository.getSaleById(id)

    suspend fun getSaleItems(saleId: Long) = salesRepository.getSaleItems(saleId)
}

class CreateSaleUseCase @Inject constructor(
    private val salesRepository: SalesRepository,
    private val productsRepository: ProductsRepository,
    private val stockMovementsRepository: StockMovementsRepository,
    private val customersRepository: CustomersRepository,
    private val auditLogsRepository: AuditLogsRepository,
    private val nextInvoiceNumberUseCase: NextInvoiceNumberUseCase,
    private val transactionRunner: TransactionRunner
) {
    suspend operator fun invoke(
        invoiceNumber: String,
        branchId: Long,
        customerId: Long?,
        cashierId: Long?,
        items: List<SaleItemEntity>,
        tax: Double,
        discount: Double,
        amountPaid: Double,
        paymentMethod: String,
        saleType: String,
        paymentStatus: String,
        cashShiftId: Long? = null,
        discountType: String = "FIXED",
        dueDate: Long? = null,
        notes: String = ""
    ): SaleEntity = transactionRunner.run {
        val invoice = invoiceNumber.ifBlank { nextInvoiceNumberUseCase() }
        val subtotal = items.sumOf { it.totalPrice }
        val total = subtotal + tax - discount
        val sale = SaleEntity(
            invoiceNumber = invoice,
            branchId = branchId,
            customerId = customerId,
            cashierId = cashierId,
            subtotal = subtotal,
            tax = tax,
            discount = discount,
            discountType = discountType,
            total = total,
            amountPaid = amountPaid,
            changeGiven = (amountPaid - total).coerceAtLeast(0.0),
            paymentMethod = paymentMethod,
            saleType = saleType,
            paymentStatus = paymentStatus,
            cashShiftId = cashShiftId,
            dueDate = dueDate,
            notes = notes
        )
        val saleId = salesRepository.insertSale(sale)

        val saleItems = items.map { it.copy(saleId = saleId) }
        salesRepository.insertSaleItems(saleItems)

        // Update stock and record movement
        saleItems.forEach { item ->
            productsRepository.updateStock(item.productId, -item.quantity)
            stockMovementsRepository.insertMovement(
                StockMovementEntity(
                    productId = item.productId,
                    movementType = "OUT",
                    quantity = -item.quantity,
                    referenceType = "SALE",
                    referenceId = saleId,
                    description = "Sale $invoice"
                )
            )
        }

        // Update customer loyalty and credit for unpaid balance
        if (customerId != null) {
            val customer = customersRepository.getCustomerById(customerId)
            if (customer != null) {
                val unpaid = (total - amountPaid).coerceAtLeast(0.0)
                customersRepository.updateCustomer(
                    customer.copy(
                        currentBalance = customer.currentBalance + unpaid,
                        totalSpent = customer.totalSpent + total,
                        totalVisits = customer.totalVisits + 1,
                        lastVisitDate = System.currentTimeMillis()
                    )
                )
            }
        }

        auditLogsRepository.insertLog(
            AuditLogEntity(
                userId = cashierId,
                action = "SALE_CREATE",
                entityType = "SALE",
                entityId = saleId,
                detail = "Invoice $invoice created for $total (${paymentStatus.takeIf { it.isNotBlank() } ?: "PAID"})"
            )
        )
        sale.copy(id = saleId)
    }
}

class VoidSaleUseCase @Inject constructor(
    private val salesRepository: SalesRepository,
    private val productsRepository: ProductsRepository,
    private val stockMovementsRepository: StockMovementsRepository,
    private val customersRepository: CustomersRepository,
    private val auditLogsRepository: AuditLogsRepository
) {
    suspend operator fun invoke(saleId: Long, reason: String, userId: Long? = null) {
        val sale = salesRepository.getSaleById(saleId) ?: return
        val items = salesRepository.getSaleItems(saleId)
        items.forEach { item ->
            productsRepository.updateStock(item.productId, item.quantity)
            stockMovementsRepository.insertMovement(
                StockMovementEntity(
                    productId = item.productId,
                    movementType = "IN",
                    quantity = item.quantity,
                    referenceType = "SALE",
                    referenceId = saleId,
                    description = "Void sale ${sale.invoiceNumber}"
                )
            )
        }
        salesRepository.voidSale(saleId, reason)

        // Reverse any credit that was added to the customer balance
        sale.customerId?.let { customerId ->
            val customer = customersRepository.getCustomerById(customerId)
            if (customer != null) {
                val unpaid = (sale.total - sale.amountPaid).coerceAtLeast(0.0)
                customersRepository.updateCustomer(
                    customer.copy(
                        currentBalance = (customer.currentBalance - unpaid).coerceAtLeast(0.0),
                        totalSpent = (customer.totalSpent - sale.total).coerceAtLeast(0.0),
                        totalVisits = (customer.totalVisits - 1).coerceAtLeast(0)
                    )
                )
            }
        }

        auditLogsRepository.insertLog(
            AuditLogEntity(
                userId = userId,
                action = "SALE_VOID",
                entityType = "SALE",
                entityId = saleId,
                detail = "Invoice ${sale.invoiceNumber} voided: $reason"
            )
        )
    }
}

class CreateSaleReturnUseCase @Inject constructor(
    private val salesRepository: SalesRepository,
    private val productsRepository: ProductsRepository,
    private val stockMovementsRepository: StockMovementsRepository,
    private val customersRepository: CustomersRepository,
    private val saleReturnsRepository: SaleReturnsRepository,
    private val auditLogsRepository: AuditLogsRepository
) {
    suspend operator fun invoke(
        saleId: Long,
        returnedBy: Long?,
        reason: String,
        returns: List<ReturnLine>
    ): SaleEntity? {
        val sale = salesRepository.getSaleById(saleId) ?: return null
        if (returns.isEmpty() || returns.all { it.quantityReturned <= 0 }) return sale

        val saleItems = salesRepository.getSaleItems(saleId).associateBy { it.id }
        val totalReturnAmount = returns.sumOf { line ->
            line.quantityReturned * (saleItems[line.saleItemId]?.unitPrice ?: 0.0)
        }

        val returnId = saleReturnsRepository.insertSaleReturn(
            SaleReturnEntity(
                saleId = saleId,
                returnedBy = returnedBy,
                reason = reason,
                totalReturnAmount = totalReturnAmount
            )
        )
        val returnItems = returns.filter { it.quantityReturned > 0 }.map { line ->
            SaleReturnItemEntity(
                saleReturnId = returnId,
                saleItemId = line.saleItemId,
                quantityReturned = line.quantityReturned,
                returnAmount = line.quantityReturned * (saleItems[line.saleItemId]?.unitPrice ?: 0.0)
            )
        }
        saleReturnsRepository.insertReturnItems(returnItems)

        returnItems.forEach { returnItem ->
            val saleItem = saleItems[returnItem.saleItemId] ?: return@forEach
            salesRepository.updateQuantityRefunded(saleItem.id, saleItem.quantityRefunded + returnItem.quantityReturned)
            productsRepository.updateStock(saleItem.productId, returnItem.quantityReturned)
            stockMovementsRepository.insertMovement(
                StockMovementEntity(
                    productId = saleItem.productId,
                    movementType = "IN",
                    quantity = returnItem.quantityReturned,
                    referenceType = "RETURN",
                    referenceId = returnId,
                    description = "Return on invoice ${sale.invoiceNumber}"
                )
            )
        }

        val fullyRefunded = saleItems.values.all { it.quantityRefunded + returns.filter { r -> r.saleItemId == it.id }.sumOf { r -> r.quantityReturned } >= it.quantity }
        val anyRefunded = saleItems.values.any { it.quantityRefunded > 0 } || returns.any { it.quantityReturned > 0 }
        salesRepository.updateReturnStatus(
            saleId,
            when {
                fullyRefunded -> "FULL"
                anyRefunded -> "PARTIAL"
                else -> "COMPLETED"
            }
        )

        // Reduce customer balance by refunded amount if the sale was on credit
        sale.customerId?.let { customerId ->
            val customer = customersRepository.getCustomerById(customerId)
            if (customer != null) {
                customersRepository.updateCustomer(
                    customer.copy(
                        currentBalance = (customer.currentBalance - totalReturnAmount).coerceAtLeast(0.0),
                        totalSpent = (customer.totalSpent - totalReturnAmount).coerceAtLeast(0.0)
                    )
                )
            }
        }

        auditLogsRepository.insertLog(
            AuditLogEntity(
                userId = returnedBy,
                action = "SALE_RETURN",
                entityType = "SALE",
                entityId = saleId,
                detail = "Return $totalReturnAmount on invoice ${sale.invoiceNumber} ($reason)"
            )
        )
        return sale
    }
}

data class ReturnLine(
    val saleItemId: Long,
    val quantityReturned: Int
)