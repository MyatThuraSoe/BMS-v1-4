package com.lumipos.domain.usecase.inventory

import com.lumipos.data.repository.ProductsRepository
import com.lumipos.data.repository.StockMovementsRepository
import com.lumipos.data.schema.ProductEntity
import com.lumipos.data.schema.StockMovementEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetStockMovementsUseCase @Inject constructor(
    private val stockMovementsRepository: StockMovementsRepository
) {
    fun getAllMovements(): Flow<List<StockMovementEntity>> = stockMovementsRepository.getAllMovements()

    fun getMovementsByDateRange(startTime: Long, endTime: Long): Flow<List<StockMovementEntity>> =
        stockMovementsRepository.getMovementsByDateRange(startTime, endTime)
}

class GetInventoryUseCase @Inject constructor(
    private val productsRepository: ProductsRepository
) {
    fun getAllProducts(): Flow<List<ProductEntity>> = productsRepository.getAllProducts()

    fun getLowStockProducts(threshold: Int): Flow<List<ProductEntity>> = productsRepository.getLowStockProducts(threshold)
}