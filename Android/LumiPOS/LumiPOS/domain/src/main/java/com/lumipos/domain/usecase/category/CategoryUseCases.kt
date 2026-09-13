package com.lumipos.domain.usecase.category

import com.lumipos.data.repository.CategoriesRepository
import com.lumipos.data.schema.CategoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetCategoriesUseCase @Inject constructor(
    private val categoriesRepository: CategoriesRepository
) {
    fun getCategoriesByBranch(branchId: Long): Flow<List<CategoryEntity>> = categoriesRepository.getCategoriesByBranch(branchId)

    fun getAllActiveCategories(): Flow<List<CategoryEntity>> = categoriesRepository.getAllActiveCategories()

    suspend operator fun invoke(id: Long): CategoryEntity? = categoriesRepository.getCategoryById(id)
}

class AddCategoryUseCase @Inject constructor(
    private val categoriesRepository: CategoriesRepository
) {
    suspend operator fun invoke(name: String, description: String, branchId: Long): Long {
        return categoriesRepository.insertCategory(
            CategoryEntity(
                name = name,
                description = description,
                branchId = branchId,
                isActive = true
            )
        )
    }
}

class UpdateCategoryUseCase @Inject constructor(
    private val categoriesRepository: CategoriesRepository
) {
    suspend operator fun invoke(category: CategoryEntity) {
        categoriesRepository.updateCategory(category)
    }

    suspend fun deleteCategory(category: CategoryEntity) {
        categoriesRepository.deleteCategory(category)
    }
}