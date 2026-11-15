package com.example.lumipos.ui.products

import android.app.Application
import androidx.lifecycle.*
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.Category
import com.example.lumipos.data.local.entity.Product
import com.example.lumipos.data.repository.CategoryRepository
import com.example.lumipos.data.repository.ProductRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProductsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val productRepo = ProductRepository(db.productDao())
    private val categoryRepo = CategoryRepository(db.categoryDao())
    private val productDao = db.productDao()

    val allProducts: LiveData<List<Product>> = productRepo.allProducts
    val lowStockProducts: LiveData<List<Product>> = productRepo.lowStockProducts
    val categories: LiveData<List<Category>> = categoryRepo.allCategories

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategoryFilter = MutableStateFlow<Long?>(null)

    val selectedCategoryFilter: LiveData<Long?> = _selectedCategoryFilter.asLiveData()

    @OptIn(ExperimentalCoroutinesApi::class)
    val products: Flow<PagingData<Product>> =
        combine(_searchQuery, _selectedCategoryFilter) { query, categoryId ->
            query.trim() to categoryId?.takeIf { it != 0L }
        }.flatMapLatest { (query, categoryId) ->
            Pager(
                PagingConfig(
                    pageSize = PAGE_SIZE,
                    initialLoadSize = PAGE_SIZE * 2,
                    enablePlaceholders = false
                )
            ) {
                if (query.isBlank()) {
                    productDao.getAllActivePaged(categoryId)
                } else {
                    productDao.searchActivePaged(query, categoryId)
                }
            }.flow
        }.cachedIn(viewModelScope)

    fun search(query: String) {
        _searchQuery.value = query
    }

    fun filterByCategory(categoryId: Long?) {
        _selectedCategoryFilter.value = categoryId
    }

    fun deleteProduct(product: Product) {
        viewModelScope.launch(Dispatchers.IO) {
            productRepo.delete(product)
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}

class ProductFormViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val productRepo = ProductRepository(db.productDao())
    private val categoryRepo = CategoryRepository(db.categoryDao())
    private val bundleRepo = com.example.lumipos.data.repository.ProductBundleRepository(db.productBundleDao())

    val categories: LiveData<List<Category>> = categoryRepo.allCategories
    val allProducts: LiveData<List<Product>> = productRepo.allActiveProducts

    private val _product = MutableLiveData<Product?>()
    val product: LiveData<Product?> = _product

    private val _bundleItems = MutableLiveData<List<com.example.lumipos.data.local.entity.ProductBundleItem>>(emptyList())
    val bundleItems: LiveData<List<com.example.lumipos.data.local.entity.ProductBundleItem>> = _bundleItems

    private val _saveSuccess = MutableLiveData<Boolean>()
    val saveSuccess: LiveData<Boolean> = _saveSuccess

    fun loadProduct(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _product.postValue(productRepo.getById(id))
            _bundleItems.postValue(bundleRepo.getByBundle(id))
        }
    }

    fun saveProduct(
        product: Product,
        imageBytes: ByteArray? = null,
        bundleComponents: List<com.example.lumipos.data.local.entity.ProductBundleItem> = emptyList()
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val productWithImage = if (imageBytes != null) {
                product.copy(imageBytes = imageBytes, thumbnailBytes = imageBytes)
            } else {
                product
            }

            val savedId = if (productWithImage.id == 0L) {
                val id = productRepo.insert(productWithImage)
                id
            } else {
                productRepo.update(productWithImage)
                productWithImage.id
            }

            if (productWithImage.isBundle) {
                bundleRepo.replace(
                    savedId,
                    bundleComponents.map { it.copy(id = 0, bundleProductId = savedId) }
                )
            } else if (productWithImage.id != 0L) {
                // No longer a bundle: drop any stale component rows.
                bundleRepo.deleteByBundle(savedId)
            }

            withContext(Dispatchers.Main) {
                _saveSuccess.value = true
            }
        }
    }

    fun deleteProductImage(productId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val product = productRepo.getById(productId) ?: return@launch
            productRepo.update(product.copy(imageBytes = null, thumbnailBytes = null))
        }
    }
}
