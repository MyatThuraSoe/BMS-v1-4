package com.lumipos.domain.usecase.product

import com.lumipos.data.repository.ProductsRepository
import com.lumipos.data.schema.ProductEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetProductsUseCase @Inject constructor(
    private val productsRepository: ProductsRepository
) {
    fun getAllActiveProducts(): Flow<List<ProductEntity>> = productsRepository.getAllActiveProducts()

    fun getAllProducts(): Flow<List<ProductEntity>> = productsRepository.getAllProducts()

    fun getProductsByCategory(categoryId: Long): Flow<List<ProductEntity>> = productsRepository.getProductsByCategory(categoryId)

    fun searchProducts(query: String): Flow<List<ProductEntity>> = productsRepository.searchProducts(query)

    fun getLowStockProducts(threshold: Int): Flow<List<ProductEntity>> = productsRepository.getLowStockProducts(threshold)

    suspend operator fun invoke(id: Long): ProductEntity? = productsRepository.getProductById(id)
}