package com.lumipos.domain.usecase.purchase

import com.lumipos.data.repository.AuditLogsRepository
import com.lumipos.data.repository.ProductsRepository
import com.lumipos.data.repository.PurchasesRepository
import com.lumipos.data.repository.StockMovementsRepository
import com.lumipos.data.schema.AuditLogEntity
import com.lumipos.data.schema.PurchaseEntity
import com.lumipos.data.schema.PurchaseItemEntity
import com.lumipos.data.schema.StockMovementEntity
import com.lumipos.domain.usecase.sequence.NextPurchaseNumberUseCase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetPurchasesUseCase @Inject constructor(
    private val purchasesRepository: PurchasesRepository
) {
    fun getAllPurchases(): Flow<List<PurchaseEntity>> = purchasesRepository.getAllPurchases()

    suspend operator fun invoke(id: Long): PurchaseEntity? = purchasesRepository.getPurchaseById(id)

    suspend fun getPurchaseItems(purchaseId: Long): List<PurchaseItemEntity> = purchasesRepository.getPurchaseItems(purchaseId)
}

class CreatePurchaseUseCase @Inject constructor(
    private val purchasesRepository: PurchasesRepository,
    private val productsRepository: ProductsRepository,
    private val stockMovementsRepository: StockMovementsRepository,
    private val auditLogsRepository: AuditLogsRepository,
    private val nextPurchaseNumberUseCase: NextPurchaseNumberUseCase
) {
    suspend operator fun invoke(
        supplierId: Long,
        createdBy: Long?,
        subtotal: Double,
        tax: Double,
        total: Double,
        items: List<PurchaseItemEntity>
    ): PurchaseEntity {
        val purchase = PurchaseEntity(
            purchaseNumber = nextPurchaseNumberUseCase(),
            supplierId = supplierId,
            subtotal = subtotal,
            tax = tax,
            total = total,
            createdBy = createdBy
        )
        val purchaseId = purchasesRepository.insertPurchase(purchase)
        val purchaseItems = items.map { it.copy(purchaseId = purchaseId) }
        purchasesRepository.insertPurchaseItems(purchaseItems)

        purchaseItems.forEach { item ->
            productsRepository.updateStock(item.productId, item.quantity)
            val product = productsRepository.getProductById(item.productId)
            if (product != null) {
                if (item.unitCost > 0) {
                    productsRepository.updateProduct(product.copy(cost = item.unitCost))
                }
            }
            stockMovementsRepository.insertMovement(
                StockMovementEntity(
                    productId = item.productId,
                    movementType = "IN",
                    quantity = item.quantity,
                    referenceType = "PURCHASE",
                    referenceId = purchaseId,
                    description = "Purchase ${purchase.purchaseNumber}",
                    createdBy = createdBy
                )
            )
        }

        auditLogsRepository.insertLog(
            AuditLogEntity(
                userId = createdBy,
                action = "PURCHASE_CREATE",
                entityType = "PURCHASE",
                entityId = purchaseId,
                detail = "Purchase ${purchase.purchaseNumber} from supplier #$supplierId for $total"
            )
        )
        return purchase.copy(id = purchaseId)
    }
}