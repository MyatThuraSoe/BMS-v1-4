package com.lumipos.app.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.repository.ProductsRepository
import com.lumipos.data.schema.ProductEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class StockControlUiState(
    val isLoading: Boolean = true,
    val lowStock: List<ProductEntity> = emptyList(),
    val productCount: Int = 0
)

@HiltViewModel
class StockControlViewModel @Inject constructor(
    private val productsRepository: ProductsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StockControlUiState())
    val uiState = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = StockControlUiState(
                isLoading = false,
                lowStock = productsRepository.getLowStockProducts(10).first(),
                productCount = productsRepository.getProductCount()
            )
        }
    }
}