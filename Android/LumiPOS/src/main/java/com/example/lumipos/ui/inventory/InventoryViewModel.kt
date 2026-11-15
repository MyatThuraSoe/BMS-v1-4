package com.example.lumipos.ui.inventory

import android.app.Application
import androidx.lifecycle.*
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.Category
import com.example.lumipos.data.local.entity.Product
import com.example.lumipos.data.local.entity.StockMovement
import com.example.lumipos.data.repository.CategoryRepository
import com.example.lumipos.data.repository.ProductRepository
import com.example.lumipos.data.repository.StockMovementRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val productRepo = ProductRepository(db.productDao())
    private val categoryRepo = CategoryRepository(db.categoryDao())
    private val stockMovementRepo = StockMovementRepository(db.stockMovementDao())

    val allProducts: LiveData<List<Product>> = productRepo.allProducts
    val lowStockProducts: LiveData<List<Product>> = productRepo.lowStockProducts
    val categories: LiveData<List<Category>> = categoryRepo.allCategories
    val allMovements: LiveData<List<StockMovement>> = stockMovementRepo.allMovements
    val totalProducts: LiveData<Int> = productRepo.activeCount
    val totalInventoryValue: LiveData<Double?> = productRepo.totalInventoryValue
    val lowStockCount: LiveData<Int> = productRepo.lowStockCount
    val outOfStockCount: LiveData<Int> = productRepo.outOfStockCount
    val totalUnits: LiveData<Int?> = productRepo.totalUnits

    /** Free-text query for the Stock Movements screen. */
    private val movementQuery = MutableLiveData("")

    /** null == "All types". */
    private val movementTypeFilter = MutableLiveData<String?>(null)

    /**
     * Stock movements after the search box and the type filter are applied.
     *
     * Built with MediatorLiveData rather than Transformations.switchMap because the result
     * depends on three inputs at once (the full list, the query and the selected type), and
     * switchMap only watches one.
     */
    val filteredMovements: LiveData<List<StockMovement>> =
        MediatorLiveData<List<StockMovement>>().apply {
            val recompute: () -> Unit = {
                postValue(applyMovementFilters(allMovements.value.orEmpty()))
            }
            addSource(allMovements) { recompute() }
            addSource(movementQuery) { recompute() }
            addSource(movementTypeFilter) { recompute() }
        }

    fun setMovementQuery(query: String) {
        if (movementQuery.value != query) movementQuery.value = query
    }

    fun setMovementTypeFilter(type: String?) {
        if (movementTypeFilter.value != type) movementTypeFilter.value = type
    }

    private fun applyMovementFilters(all: List<StockMovement>): List<StockMovement> {
        val query = movementQuery.value.orEmpty().trim().lowercase()
        val type = movementTypeFilter.value
        return all.filter { movement ->
            val typeMatches = type == null || movement.movementType == type
            val textMatches = query.isEmpty() ||
                movement.productName.lowercase().contains(query) ||
                movement.sku.lowercase().contains(query) ||
                movement.movementType.lowercase().contains(query) ||
                movement.description.lowercase().contains(query)
            typeMatches && textMatches
        }
    }

    /**
     * Applies a signed stock change. The direction is taken from the sign of [quantityChange],
     * so callers do not need to pass it as well — the old `adjustmentType` argument was
     * accepted and then ignored, which is exactly how a sign/argument mismatch goes unnoticed.
     */
    fun adjustStock(productId: Long, quantityChange: Int, reason: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val product = productRepo.getById(productId) ?: return@launch
            val newQuantity = product.stockQuantity + quantityChange
            productRepo.setStock(productId, maxOf(0, newQuantity))

            stockMovementRepo.insert(StockMovement(
                productId = productId,
                productName = product.name,
                sku = product.sku,
                movementType = if (quantityChange > 0) "ADJUSTMENT_IN" else "ADJUSTMENT_OUT",
                quantity = kotlin.math.abs(quantityChange),
                referenceType = "STOCK_ADJUSTMENT",
                description = reason,
                createdByName = "Cashier"
            ))
        }
    }
}
