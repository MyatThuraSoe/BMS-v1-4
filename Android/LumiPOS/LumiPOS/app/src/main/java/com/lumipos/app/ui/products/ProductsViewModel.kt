package com.lumipos.app.ui.products

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.app.storage.ImageStorageService
import com.lumipos.data.schema.CategoryEntity
import com.lumipos.data.schema.ProductEntity
import com.lumipos.domain.usecase.category.GetCategoriesUseCase
import com.lumipos.domain.usecase.product.AddProductUseCase
import com.lumipos.domain.usecase.product.GetProductsUseCase
import com.lumipos.domain.usecase.product.UpdateProductUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProductFormState(
    val name: String = "",
    val sku: String = "",
    val categoryId: Long? = null,
    val price: String = "",
    val cost: String = "",
    val stock: String = "",
    val imageUrl: String = "",
    val formError: String? = null
)

data class ProductsUiState(
    val products: List<ProductEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val isLoading: Boolean = true,
    val showForm: Boolean = false,
    val editingProduct: ProductEntity? = null,
    val form: ProductFormState = ProductFormState(),
    val error: String? = null,
    val searchQuery: String = "",
    val selectedCategoryId: Long? = null
)

@HiltViewModel
class ProductsViewModel @Inject constructor(
    private val getProductsUseCase: GetProductsUseCase,
    private val addProductUseCase: AddProductUseCase,
    private val updateProductUseCase: UpdateProductUseCase,
    private val getCategoriesUseCase: GetCategoriesUseCase,
    private val imageStorageService: ImageStorageService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProductsUiState())
    val uiState: StateFlow<ProductsUiState> = _uiState

    init {
        viewModelScope.launch {
            getCategoriesUseCase.getAllActiveCategories().collect { cats ->
                _uiState.update { it.copy(categories = cats) }
            }
        }
        viewModelScope.launch {
            getProductsUseCase.getAllActiveProducts().collect { list ->
                _uiState.update { it.copy(products = list, isLoading = false) }
            }
        }
    }

    fun onSearchChange(q: String) = _uiState.update { it.copy(searchQuery = q) }
    fun onCategoryFilterChange(id: Long?) = _uiState.update { it.copy(selectedCategoryId = id) }

    fun openAdd() = _uiState.update {
        it.copy(showForm = true, editingProduct = null, form = ProductFormState(), error = null)
    }

    fun openEdit(p: ProductEntity) = _uiState.update {
        it.copy(
            showForm = true,
            editingProduct = p,
            form = ProductFormState(
                name = p.name,
                sku = p.sku,
                categoryId = p.categoryId,
                price = if (p.price > 0) p.price.toString() else "",
                cost = if (p.cost > 0) p.cost.toString() else "",
                stock = p.stock.toString(),
                imageUrl = p.imageUrl ?: ""
            ),
            error = null
        )
    }

    fun closeForm() {
        viewModelScope.launch {
            val state = _uiState.value
            val editing = state.editingProduct
            if (isPickedFile(state.form.imageUrl, editing)) {
                imageStorageService.delete(state.form.imageUrl)
            }
            _uiState.update { it.copy(showForm = false) }
        }
    }
    fun onNameChange(v: String) = _uiState.update { it.copy(form = it.form.copy(name = v, formError = null)) }
    fun onSkuChange(v: String) = _uiState.update { it.copy(form = it.form.copy(sku = v)) }
    fun onCategoryIdChange(v: Long?) = _uiState.update { it.copy(form = it.form.copy(categoryId = v)) }
    fun onPriceChange(v: String) = _uiState.update { it.copy(form = it.form.copy(price = v)) }
    fun onCostChange(v: String) = _uiState.update { it.copy(form = it.form.copy(cost = v)) }
    fun onStockChange(v: String) = _uiState.update { it.copy(form = it.form.copy(stock = v)) }

    fun onImagePicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val state = _uiState.value
            if (state.form.imageUrl.isNotBlank() && !state.form.imageUrl.equals(state.editingProduct?.imageUrl, ignoreCase = true)) {
                imageStorageService.delete(state.form.imageUrl)
            }
            val path = imageStorageService.importImage(uri)
            if (path != null) {
                _uiState.update { it.copy(form = it.form.copy(imageUrl = path)) }
            }
        }
    }

    fun clearImage() {
        viewModelScope.launch {
            val state = _uiState.value
            if (isPickedFile(state.form.imageUrl, state.editingProduct)) {
                imageStorageService.delete(state.form.imageUrl)
            }
            _uiState.update { it.copy(form = it.form.copy(imageUrl = "")) }
        }
    }

    private fun isPickedFile(path: String, editing: ProductEntity?): Boolean {
        return path.isNotBlank() && (editing == null || path != editing.imageUrl)
    }

    fun saveProduct() {
        val f = _uiState.value.form
        if (f.name.isBlank()) {
            _uiState.update { it.copy(form = it.form.copy(formError = "Name is required")) }
            return
        }
        if (f.sku.isBlank()) {
            _uiState.update { it.copy(form = it.form.copy(formError = "SKU is required")) }
            return
        }
        val price = f.price.toDoubleOrNull() ?: 0.0
        val cost = f.cost.toDoubleOrNull() ?: 0.0
        val stock = f.stock.toIntOrNull() ?: 0

        viewModelScope.launch {
            try {
                val editing = _uiState.value.editingProduct
                if (editing == null) {
                    addProductUseCase(
                        name = f.name.trim(),
                        categoryId = f.categoryId,
                        sku = f.sku.trim(),
                        price = price,
                        cost = cost,
                        stock = stock,
                        imageUrl = f.imageUrl.ifBlank { null }
                    )
                } else {
                    val previousImage = editing.imageUrl
                    updateProductUseCase(
                        editing.copy(
                            name = f.name.trim(),
                            categoryId = f.categoryId,
                            sku = f.sku.trim(),
                            price = price,
                            cost = cost,
                            stock = stock,
                            imageUrl = f.imageUrl.ifBlank { null }
                        )
                    )
                    if (!previousImage.isNullOrBlank() && previousImage != f.imageUrl) {
                        imageStorageService.delete(previousImage)
                    }
                }
                closeForm()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteProduct(p: ProductEntity) {
        viewModelScope.launch {
            try {
                updateProductUseCase.deleteProduct(p)
                imageStorageService.delete(p.imageUrl)
            } catch (e: Exception) { _uiState.update { it.copy(error = e.message) } }
        }
    }
}