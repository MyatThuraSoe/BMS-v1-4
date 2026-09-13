package com.lumipos.domain.usecase.product

import com.lumipos.data.repository.ProductsRepository
import com.lumipos.data.schema.ProductEntity
import javax.inject.Inject

class UpdateProductUseCase @Inject constructor(
    private val productsRepository: ProductsRepository
) {
    suspend operator fun invoke(product: ProductEntity) {
        productsRepository.updateProduct(product)
    }

    suspend fun updateStock(productId: Long, quantity: Int) {
        productsRepository.updateStock(productId, quantity)
    }

    suspend fun deleteProduct(product: ProductEntity) {
        productsRepository.deleteProduct(product)
    }
}