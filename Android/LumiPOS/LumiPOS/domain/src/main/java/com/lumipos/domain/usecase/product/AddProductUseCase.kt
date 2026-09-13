package com.lumipos.domain.usecase.product

import com.lumipos.data.repository.ProductsRepository
import com.lumipos.data.schema.ProductEntity
import javax.inject.Inject

class AddProductUseCase @Inject constructor(
    private val productsRepository: ProductsRepository
) {
    suspend operator fun invoke(
        name: String,
        categoryId: Long?,
        sku: String,
        price: Double,
        cost: Double,
        stock: Int,
        imageUrl: String?
    ): Long {
        val existing = productsRepository.getProductBySku(sku)
        if (existing != null && existing.id != 0L) {
            error("Product with SKU $sku already exists")
        }
        val product = ProductEntity(
            name = name,
            categoryId = categoryId,
            sku = sku,
            price = price,
            cost = cost,
            stock = stock,
            imageUrl = imageUrl,
            isActive = true
        )
        return productsRepository.insertProduct(product)
    }
}