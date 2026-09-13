package com.lumipos.app.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.app.ui.util.asCurrency
import com.lumipos.app.ui.util.asDateTime
import com.lumipos.data.print.ReceiptData
import com.lumipos.data.print.ReceiptLine
import com.lumipos.data.repository.ShopInfoRepository
import com.lumipos.data.schema.OrderEntity
import com.lumipos.data.schema.OrderItemEntity
import com.lumipos.domain.usecase.order.ConvertOrderToSaleUseCase
import com.lumipos.domain.usecase.order.GetOrdersUseCase
import com.lumipos.domain.usecase.order.OrderStatus
import com.lumipos.domain.usecase.order.UpdateOrderUseCase
import com.lumipos.domain.usecase.order.CleanupStaleDraftOrdersUseCase
import com.lumipos.domain.usecase.product.GetProductsUseCase
import com.lumipos.domain.usecase.session.ObserveSessionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OrderRange(val label: String, val days: Int?) {
    ALL("All", null),
    WEEK("7 Days", 7),
    MONTH("30 Days", 30)
}

data class OrdersUiState(
    val orders: List<OrderEntity> = emptyList(),
    val statusFilter: String? = null,
    val dateRange: OrderRange = OrderRange.ALL,
    val isLoading: Boolean = true,
    val selectedOrder: OrderEntity? = null,
    val orderItems: List<OrderItemEntity> = emptyList(),
    val printReceipt: ReceiptData? = null,
    val showConvertDialog: Boolean = false,
    val amountPaid: String = "",
    val paymentMethod: String = "CASH",
    val error: String? = null
)

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val getOrdersUseCase: GetOrdersUseCase,
    private val updateOrderUseCase: UpdateOrderUseCase,
    private val convertOrderToSaleUseCase: ConvertOrderToSaleUseCase,
    private val getProductsUseCase: GetProductsUseCase,
    private val shopInfoRepository: ShopInfoRepository,
    private val observeSessionUseCase: ObserveSessionUseCase,
    private val cleanupStaleDraftOrdersUseCase: CleanupStaleDraftOrdersUseCase
) : ViewModel() {

    private var branchId: Long = 0L
    private var currentUserId: Long? = null
    private val _uiState = MutableStateFlow(OrdersUiState())
    val uiState: StateFlow<OrdersUiState> = _uiState

    init {
        viewModelScope.launch {
            val session = observeSessionUseCase().first()
            currentUserId = session.userId
            session.branchId?.let {
                branchId = it
                reloadOrders()
            } ?: reloadOrders()
        }
        viewModelScope.launch {
            try {
                cleanupStaleDraftOrdersUseCase()
            } catch (e: Exception) {
                // cleanup is best-effort
            }
        }
    }

    private var ordersJob: kotlinx.coroutines.Job? = null

    private fun reloadOrders() {
        ordersJob?.cancel()
        ordersJob = viewModelScope.launch {
            val range = _uiState.value.dateRange
            val flow = if (range.days == null) {
                getOrdersUseCase.getOrdersByBranch(branchId)
            } else {
                val start = System.currentTimeMillis() - (range.days * 24L * 60L * 60L * 1000L)
                getOrdersUseCase.getOrdersByBranchDateRange(branchId, start, System.currentTimeMillis())
            }
            flow.collect { list ->
                _uiState.update { it.copy(orders = list, isLoading = false) }
            }
        }
    }

    fun setStatusFilter(status: String?) = _uiState.update { it.copy(statusFilter = status) }

    fun setDateRange(range: OrderRange) {
        _uiState.update { it.copy(dateRange = range) }
        reloadOrders()
    }

    fun selectOrder(order: OrderEntity) {
        _uiState.update { it.copy(selectedOrder = order, orderItems = emptyList()) }
        viewModelScope.launch {
            try {
                val items = getOrdersUseCase.getOrderItems(order.id)
                _uiState.update { it.copy(orderItems = items) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun dismissDetail() = _uiState.update { it.copy(selectedOrder = null, orderItems = emptyList()) }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun dismissPrintReceipt() = _uiState.update { it.copy(printReceipt = null) }

    fun printSelected() {
        val order = _uiState.value.selectedOrder ?: return
        val items = _uiState.value.orderItems
        if (items.isEmpty()) return
        viewModelScope.launch {
            try {
                val products = getProductsUseCase.getAllActiveProducts().first()
                val shop = shopInfoRepository.getShopInfoOnce()
                val productsById = products.associateBy { it.id }
                val itemLines = items.map { item ->
                    val name = productsById[item.productId]?.name ?: "Product #${item.productId}"
                    ReceiptLine(left = "${item.quantity} x $name", right = item.subtotal.asCurrency())
                }
                val receipt = ReceiptData(
                    storeName = shop?.shopName?.takeIf { it.isNotBlank() } ?: "LumiPOS",
                    storeAddress = shop?.address.orEmpty(),
                    header = listOf(
                        ReceiptLine.twoCol("Order", order.orderNumber),
                        ReceiptLine.twoCol("Date", order.createdAt.asDateTime()),
                        ReceiptLine.twoCol("Status", humanize(order.status))
                    ),
                    items = listOf(
                        ReceiptLine.divider(),
                        ReceiptLine(left = "ITEM", right = "PRICE", bold = true),
                        ReceiptLine.divider()
                    ) + itemLines,
                    totals = listOf(
                        ReceiptLine.divider(),
                        ReceiptLine.twoCol("Total", order.totalAmount.asCurrency())
                    ),
                    footer = "Thank you for shopping!"
                )
                _uiState.update { it.copy(printReceipt = receipt) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun markStatus(order: OrderEntity, status: String) {
        viewModelScope.launch {
            try {
                updateOrderUseCase.markStatus(order.id, status, order.totalAmount)
                if (status == OrderStatus.CANCELLED) {
                    updateOrderUseCase.markCancelled(order.id, null)
                }
                dismissDetail()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun cancelOrder(order: OrderEntity, reason: String?) {
        viewModelScope.launch {
            try {
                updateOrderUseCase.markCancelled(order.id, reason)
                dismissDetail()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun openConvertDialog() = _uiState.update {
        it.copy(showConvertDialog = true, amountPaid = it.selectedOrder?.totalAmount?.toString() ?: "", paymentMethod = "CASH")
    }

    fun dismissConvertDialog() = _uiState.update { it.copy(showConvertDialog = false) }

    fun setAmountPaid(value: String) = _uiState.update { it.copy(amountPaid = value) }

    fun setPaymentMethod(method: String) = _uiState.update { it.copy(paymentMethod = method) }

    fun convertToSale() {
        val order = _uiState.value.selectedOrder ?: return
        val amountPaid = _uiState.value.amountPaid.toDoubleOrNull() ?: order.totalAmount
        viewModelScope.launch {
            try {
                convertOrderToSaleUseCase(
                    orderId = order.id,
                    branchId = branchId,
                    cashierId = currentUserId,
                    customerId = order.customerId,
                    amountPaid = amountPaid,
                    paymentMethod = _uiState.value.paymentMethod
                )
                _uiState.update { it.copy(showConvertDialog = false) }
                dismissDetail()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
}

private fun humanize(status: String): String = when (status) {
    OrderStatus.PENDING -> "Pending"
    OrderStatus.IN_PROGRESS -> "In Progress"
    OrderStatus.READY -> "Ready"
    OrderStatus.COMPLETED -> "Completed"
    OrderStatus.CANCELLED -> "Cancelled"
    else -> status
}