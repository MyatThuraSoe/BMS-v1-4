package com.lumipos.domain.usecase.inventory

import com.lumipos.data.repository.AuditLogsRepository
import com.lumipos.data.repository.ProductsRepository
import com.lumipos.data.repository.StockMovementsRepository
import com.lumipos.data.schema.AuditLogEntity
import com.lumipos.data.schema.StockMovementEntity
import javax.inject.Inject

class AdjustStockUseCase @Inject constructor(
    private val productsRepository: ProductsRepository,
    private val stockMovementsRepository: StockMovementsRepository,
    private val auditLogsRepository: AuditLogsRepository
) {
    suspend operator fun invoke(productId: Long, delta: Int, reason: String, userId: Long? = null) {
        if (delta == 0) return
        val product = productsRepository.getProductById(productId)
        productsRepository.updateStock(productId, delta)
        stockMovementsRepository.insertMovement(
            StockMovementEntity(
                productId = productId,
                movementType = if (delta > 0) "IN" else "OUT",
                quantity = delta,
                referenceType = "STOCK_ADJUSTMENT",
                description = reason,
                createdBy = userId
            )
        )
        auditLogsRepository.insertLog(
            AuditLogEntity(
                userId = userId,
                action = "STOCK_ADJUST",
                entityType = "PRODUCT",
                entityId = productId,
                detail = "Adjusted ${product?.name ?: "product"} by $delta: $reason"
            )
        )
    }
}