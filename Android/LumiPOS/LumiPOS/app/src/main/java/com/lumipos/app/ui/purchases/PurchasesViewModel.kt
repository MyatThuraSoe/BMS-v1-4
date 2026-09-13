package com.lumipos.app.ui.purchases

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.app.ui.util.asCurrency
import com.lumipos.data.repository.PurchasesRepository
import com.lumipos.data.schema.ProductEntity
import com.lumipos.data.schema.PurchaseEntity
import com.lumipos.data.schema.PurchaseItemEntity
import com.lumipos.data.schema.SupplierEntity
import com.lumipos.domain.usecase.product.GetProductsUseCase
import com.lumipos.domain.usecase.purchase.CreatePurchaseUseCase
import com.lumipos.domain.usecase.purchase.GetPurchasesUseCase
import com.lumipos.domain.usecase.session.ObserveSessionUseCase
import com.lumipos.domain.usecase.supplier.GetSuppliersUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PurchaseLine(
    val product: ProductEntity,
    val quantity: String = "1",
    val unitCost: String = ""
) {
    val qty: Int get() = quantity.toIntOrNull() ?: 0
    val cost: Double get() = unitCost.toDoubleOrNull() ?: product.cost
    val lineTotal: Double get() = qty * cost
}

data class PurchasesUiState(
    val purchases: List<PurchaseEntity> = emptyList(),
    val products: List<ProductEntity> = emptyList(),
    val suppliers: List<SupplierEntity> = emptyList(),
    val productNames: Map<Long, String> = emptyMap(),
    val supplierNames: Map<Long, String> = emptyMap(),
    val purchaseItems: List<PurchaseItemEntity> = emptyList(),
    val selectedPurchase: PurchaseEntity? = null,
    val isLoading: Boolean = true,
    val showForm: Boolean = false,
    val selectedSupplierId: Long? = null,
    val lines: List<PurchaseLine> = emptyList(),
    val taxRate: String = "0",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val message: String? = null
) {
    val subtotal: Double get() = lines.sumOf { it.lineTotal }
    val tax: Double get() = subtotal * (taxRate.toDoubleOrNull() ?: 0.0) / 100.0
    val total: Double get() = subtotal + tax
}

@HiltViewModel
class PurchasesViewModel @Inject constructor(
    private val getPurchasesUseCase: GetPurchasesUseCase,
    private val createPurchaseUseCase: CreatePurchaseUseCase,
    private val getProductsUseCase: GetProductsUseCase,
    private val getSuppliersUseCase: GetSuppliersUseCase,
    private val purchasesRepository: PurchasesRepository,
    private val observeSessionUseCase: ObserveSessionUseCase
) : ViewModel() {

    private var currentUserId: Long? = null
    private val _uiState = MutableStateFlow(PurchasesUiState())
    val uiState: StateFlow<PurchasesUiState> = _uiState

    init {
        viewModelScope.launch {
            observeSessionUseCase().first().let { currentUserId = it.userId }
        }
        viewModelScope.launch {
            getPurchasesUseCase.getAllPurchases().collect { list ->
                _uiState.update { it.copy(purchases = list, isLoading = false) }
            }
        }
        viewModelScope.launch {
            getProductsUseCase.getAllActiveProducts().collect { list ->
                _uiState.update { it.copy(products = list, productNames = list.associate { p -> p.id to p.name }) }
            }
        }
        viewModelScope.launch {
            getSuppliersUseCase.getAllSuppliers().collect { list ->
                _uiState.update { it.copy(suppliers = list, supplierNames = list.associate { s -> s.id to s.name }) }
            }
        }
    }

    fun openForm() = _uiState.update {
        it.copy(showForm = true, selectedSupplierId = it.suppliers.firstOrNull()?.id, lines = emptyList(), taxRate = "0")
    }

    fun dismissForm() = _uiState.update { it.copy(showForm = false, isSubmitting = false, lines = emptyList()) }

    fun selectSupplier(id: Long) = _uiState.update { it.copy(selectedSupplierId = id) }

    fun addProduct(product: ProductEntity) {
        _uiState.update { state ->
            if (state.lines.any { it.product.id == product.id }) {
                state.copy(message = "${product.name} already in the purchase")
            } else {
                state.copy(lines = state.lines + PurchaseLine(product = product))
            }
        }
    }

    fun removeLine(productId: Long) = _uiState.update { state ->
        state.copy(lines = state.lines.filterNot { it.product.id == productId })
    }

    fun updateQuantity(productId: Long, value: String) = _uiState.update { state ->
        state.copy(lines = state.lines.map { if (it.product.id == productId) it.copy(quantity = value) else it })
    }

    fun updateUnitCost(productId: Long, value: String) = _uiState.update { state ->
        state.copy(lines = state.lines.map { if (it.product.id == productId) it.copy(unitCost = value) else it })
    }

    fun onTaxRateChange(v: String) = _uiState.update { it.copy(taxRate = v) }

    fun submitPurchase() {
        val state = _uiState.value
        val supplierId = state.selectedSupplierId
        val items = state.lines.filter { it.qty > 0 }
        if (supplierId == null) {
            _uiState.update { it.copy(error = "Select a supplier") }
            return
        }
        if (items.isEmpty()) {
            _uiState.update { it.copy(error = "Add at least one item") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            try {
                createPurchaseUseCase(
                    supplierId = supplierId,
                    createdBy = currentUserId,
                    subtotal = state.subtotal,
                    tax = state.tax,
                    total = state.total,
                    items = items.map { line ->
                        PurchaseItemEntity(
                            purchaseId = 0,
                            productId = line.product.id,
                            quantity = line.qty,
                            unitCost = line.cost,
                            totalCost = line.lineTotal
                        )
                    }
                )
                _uiState.update { it.copy(isSubmitting = false, showForm = false, lines = emptyList(), message = "Purchase created") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSubmitting = false, error = e.message) }
            }
        }
    }

    fun selectPurchase(purchase: PurchaseEntity) {
        _uiState.update { it.copy(selectedPurchase = purchase, purchaseItems = emptyList()) }
        viewModelScope.launch {
            try {
                val items = getPurchasesUseCase.getPurchaseItems(purchase.id)
                _uiState.update { it.copy(purchaseItems = items) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun dismissDetail() = _uiState.update { it.copy(selectedPurchase = null, purchaseItems = emptyList()) }

    fun markPaid(purchase: PurchaseEntity) {
        viewModelScope.launch {
            try {
                purchasesRepository.updatePaymentStatus(purchase.id, "PAID")
                _uiState.update { it.copy(selectedPurchase = null, purchaseItems = emptyList(), message = "Marked ${purchase.purchaseNumber} as paid") }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }
    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}