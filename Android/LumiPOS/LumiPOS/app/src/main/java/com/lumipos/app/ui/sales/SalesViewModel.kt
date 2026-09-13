package com.lumipos.app.ui.sales

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.app.ui.util.asCurrency
import com.lumipos.app.ui.util.asDateTime
import com.lumipos.data.print.ReceiptData
import com.lumipos.data.print.ReceiptLine
import com.lumipos.data.repository.ShopInfoRepository
import com.lumipos.data.schema.SaleEntity
import com.lumipos.data.schema.SaleItemEntity
import com.lumipos.domain.usecase.customer.GetCustomersUseCase
import com.lumipos.domain.usecase.product.GetProductsUseCase
import com.lumipos.domain.usecase.sale.CreateSaleReturnUseCase
import com.lumipos.domain.usecase.sale.GetSalesUseCase
import com.lumipos.domain.usecase.sale.ReturnLine
import com.lumipos.domain.usecase.sale.VoidSaleUseCase
import com.lumipos.domain.usecase.session.ObserveSessionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SaleRange(val label: String, val days: Int?) {
    ALL("All", null),
    TODAY("Today", 0),
    WEEK("7 Days", 7),
    MONTH("30 Days", 30)
}

data class SalesUiState(
    val sales: List<SaleEntity> = emptyList(),
    val dateRange: SaleRange = SaleRange.ALL,
    val statusFilter: String? = null,
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val selectedSale: SaleEntity? = null,
    val saleItems: List<SaleItemEntity> = emptyList(),
    val productNames: Map<Long, String> = emptyMap(),
    val customerName: String? = null,
    val printReceipt: ReceiptData? = null,
    val showReturnDialog: Boolean = false,
    val returnQuantities: Map<Long, Int> = emptyMap(),
    val returnReason: String = "",
    val isSubmitting: Boolean = false,
    val showVoidDialog: Boolean = false,
    val voidReason: String = "",
    val error: String? = null,
    val message: String? = null
) {
    val returnTotal: Double
        get() {
            val itemsById = saleItems.associateBy { it.id }
            return returnQuantities.entries.sumOf { (id, qty) ->
                qty * (itemsById[id]?.unitPrice ?: 0.0)
            }
        }
}

@HiltViewModel
class SalesViewModel @Inject constructor(
    private val getSalesUseCase: GetSalesUseCase,
    private val getProductsUseCase: GetProductsUseCase,
    private val getCustomersUseCase: GetCustomersUseCase,
    private val createSaleReturnUseCase: CreateSaleReturnUseCase,
    private val voidSaleUseCase: VoidSaleUseCase,
    private val shopInfoRepository: ShopInfoRepository,
    private val observeSessionUseCase: ObserveSessionUseCase
) : ViewModel() {

    private var branchId: Long = 0L
    private var currentUserId: Long? = null
    private val _uiState = MutableStateFlow(SalesUiState())
    val uiState: StateFlow<SalesUiState> = _uiState

    init {
        viewModelScope.launch {
            observeSessionUseCase().first().let { session ->
                branchId = session.branchId ?: 0L
                currentUserId = session.userId
                reloadSales()
            }
        }
        viewModelScope.launch {
            getProductsUseCase.getAllProducts().collect { list ->
                _uiState.update { it.copy(productNames = list.associate { p -> p.id to p.name }) }
            }
        }
    }

    private var salesJob: kotlinx.coroutines.Job? = null

    private fun reloadSales() {
        salesJob?.cancel()
        salesJob = viewModelScope.launch {
            val range = _uiState.value.dateRange
            val flow = when {
                range.days == null -> getSalesUseCase.getSalesByBranch(branchId)
                range.days == 0 -> getSalesUseCase.getSalesByDateRange(
                    startOfDay(), System.currentTimeMillis(), branchId
                )
                else -> getSalesUseCase.getSalesByDateRange(
                    System.currentTimeMillis() - (range.days * 24L * 60L * 60L * 1000L),
                    System.currentTimeMillis(), branchId
                )
            }
            flow.collect { list ->
                _uiState.update { it.copy(sales = list, isLoading = false) }
            }
        }
    }

    private fun startOfDay(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun setDateRange(range: SaleRange) {
        _uiState.update { it.copy(dateRange = range) }
        reloadSales()
    }

    fun setStatusFilter(status: String?) = _uiState.update { it.copy(statusFilter = status) }

    fun onSearchChange(query: String) = _uiState.update { it.copy(searchQuery = query) }

    fun selectSale(sale: SaleEntity) {
        _uiState.update { it.copy(selectedSale = sale, saleItems = emptyList(), customerName = null) }
        viewModelScope.launch {
            try {
                val items = getSalesUseCase.getSaleItems(sale.id)
                val customerName = sale.customerId?.let { getCustomersUseCase(it)?.name }
                _uiState.update { it.copy(saleItems = items, customerName = customerName) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun dismissDetail() = _uiState.update { it.copy(selectedSale = null, saleItems = emptyList()) }

    fun printSelected() {
        val sale = _uiState.value.selectedSale ?: return
        val items = _uiState.value.saleItems
        viewModelScope.launch {
            try {
                val shop = shopInfoRepository.getShopInfoOnce()
                val names = getProductsUseCase.getAllProducts().first().associateBy { it.id }
                val itemLines = items.map { item ->
                    val name = names[item.productId]?.name ?: "Product #${item.productId}"
                    val returned = if (item.quantityRefunded > 0) " (${item.quantityRefunded} returned)" else ""
                    ReceiptLine(left = "${item.quantity - item.quantityRefunded} x $name$returned", right = item.totalPrice.asCurrency())
                }
                val lines = items.sumOf { it.totalPrice }
                val receipt = ReceiptData(
                    storeName = shop?.shopName?.takeIf { it.isNotBlank() } ?: "LumiPOS",
                    storeAddress = shop?.address.orEmpty(),
                    header = listOfNotNull(
                        ReceiptLine.twoCol("Invoice", sale.invoiceNumber),
                        ReceiptLine.twoCol("Date", sale.saleDate.asDateTime()),
                        _uiState.value.customerName?.let { ReceiptLine.twoCol("Customer", it) },
                        ReceiptLine.twoCol("Method", sale.paymentMethod),
                        ReceiptLine.twoCol("Status", sale.paymentStatus)
                    ),
                    items = listOf(
                        ReceiptLine.divider(),
                        ReceiptLine(left = "ITEM", right = "PRICE", bold = true),
                        ReceiptLine.divider()
                    ) + itemLines,
                    totals = listOf(
                        ReceiptLine.divider(),
                        ReceiptLine.twoCol("Subtotal", lines.asCurrency()),
                        ReceiptLine.twoCol("Tax", sale.tax.asCurrency()),
                        ReceiptLine.twoCol("Discount", sale.discount.asCurrency()),
                        ReceiptLine.twoCol("Total", sale.total.asCurrency()),
                        ReceiptLine.twoCol("Paid", sale.amountPaid.asCurrency()),
                        ReceiptLine.twoCol("Change", sale.changeGiven.asCurrency())
                    ),
                    footer = "Thank you for shopping!"
                )
                _uiState.update { it.copy(printReceipt = receipt) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun dismissPrintReceipt() = _uiState.update { it.copy(printReceipt = null) }

    fun openReturnDialog() {
        val sale = _uiState.value.selectedSale ?: return
        if (sale.isVoided) return
        _uiState.update {
            it.copy(
                showReturnDialog = true,
                returnReason = "",
                returnQuantities = emptyMap()
            )
        }
    }

    fun dismissReturnDialog() = _uiState.update { it.copy(showReturnDialog = false, isSubmitting = false) }

    fun updateReturnQuantity(saleItemId: Long, delta: Int) {
        _uiState.update { state ->
            val item = state.saleItems.find { it.id == saleItemId } ?: return@update state
            val maxReturnable = item.quantity - item.quantityRefunded
            val current = state.returnQuantities[saleItemId] ?: 0
            val next = (current + delta).coerceIn(0, maxReturnable)
            state.copy(returnQuantities = if (next == 0) state.returnQuantities - saleItemId else state.returnQuantities + (saleItemId to next))
        }
    }

    fun setReturnReason(reason: String) = _uiState.update { it.copy(returnReason = reason) }

    fun submitReturn() {
        val sale = _uiState.value.selectedSale ?: return
        val lines = _uiState.value.returnQuantities
            .filter { (_, qty) -> qty > 0 }
            .map { (saleItemId, qty) -> ReturnLine(saleItemId, qty) }
        if (lines.isEmpty()) {
            _uiState.update { it.copy(error = "Select at least one item to return") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            try {
                val result = createSaleReturnUseCase(
                    saleId = sale.id,
                    returnedBy = currentUserId,
                    reason = _uiState.value.returnReason,
                    returns = lines
                )
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        showReturnDialog = false,
                        message = "Return recorded (${_uiState.value.returnTotal.asCurrency()})"
                    )
                }
                selectSale(result ?: sale)
            } catch (e: Exception) {
                _uiState.update { it.copy(isSubmitting = false, error = e.message) }
            }
        }
    }

    fun openVoidDialog() {
        val sale = _uiState.value.selectedSale ?: return
        if (sale.isVoided) return
        _uiState.update { it.copy(showVoidDialog = true, voidReason = "") }
    }

    fun dismissVoidDialog() = _uiState.update { it.copy(showVoidDialog = false, isSubmitting = false) }

    fun setVoidReason(reason: String) = _uiState.update { it.copy(voidReason = reason) }

    fun submitVoid() {
        val sale = _uiState.value.selectedSale ?: return
        if (_uiState.value.voidReason.isBlank()) {
            _uiState.update { it.copy(error = "A reason is required to void a sale") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            try {
                voidSaleUseCase(sale.id, _uiState.value.voidReason, currentUserId)
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        showVoidDialog = false,
                        selectedSale = null,
                        saleItems = emptyList(),
                        message = "Sale ${sale.invoiceNumber} voided"
                    )
                }
                reloadSales()
            } catch (e: Exception) {
                _uiState.update { it.copy(isSubmitting = false, error = e.message) }
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}