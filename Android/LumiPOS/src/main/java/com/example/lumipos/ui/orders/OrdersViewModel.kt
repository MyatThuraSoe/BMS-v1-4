package com.example.lumipos.ui.orders

import android.app.Application
import androidx.lifecycle.*
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.Order
import com.example.lumipos.data.local.entity.OrderItem
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.data.local.entity.SaleItem
import com.example.lumipos.data.local.entity.StockMovement
import com.example.lumipos.data.repository.OrderRepository
import com.example.lumipos.data.repository.ProductRepository
import com.example.lumipos.data.repository.SaleRepository
import com.example.lumipos.data.repository.ShiftRepository
import com.example.lumipos.data.repository.StockMovementRepository
import com.example.lumipos.util.DateFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OrdersViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val orderRepo = OrderRepository(db.orderDao(), db.orderItemDao())
    private val saleRepo = SaleRepository(db.saleDao(), db.saleItemDao())
    private val productRepo = ProductRepository(db.productDao())
    private val shiftRepo = ShiftRepository(db.shiftDao())
    private val stockMovementRepo = StockMovementRepository(db.stockMovementDao())

    val allOrders: LiveData<List<Order>> = orderRepo.allOrders

    private val _statusFilter = MutableLiveData("ALL")
    val statusFilter: LiveData<String> = _statusFilter

    private val _filteredOrders = MutableLiveData<List<Order>>()
    val filteredOrders: LiveData<List<Order>> = _filteredOrders

    /** Line items of the order whose detail dialog is open. */
    private val _orderItems = MutableLiveData<List<OrderItem>>(emptyList())
    val orderItems: LiveData<List<OrderItem>> = _orderItems

    private val _convertSuccess = MutableLiveData<Boolean>()
    val convertSuccess: LiveData<Boolean> = _convertSuccess

    fun loadOrderItems(orderId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _orderItems.postValue(orderRepo.getItemsByOrderId(orderId))
        }
    }

    fun clearOrderItems() {
        _orderItems.value = emptyList()
    }

    fun filterByStatus(status: String) {
        _statusFilter.value = status
        loadOrders()
    }

    fun cancelOrder(orderId: Long, reason: String) {
        viewModelScope.launch(Dispatchers.IO) {
            orderRepo.cancel(orderId, reason)
            loadOrders()
        }
    }

    fun loadOrders() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = _statusFilter.value ?: "ALL"
            if (status == "ALL") {
                _filteredOrders.postValue(orderRepo.getAllList())
            } else {
                _filteredOrders.postValue(orderRepo.getByStatusList(status))
            }
        }
    }

    fun clearConvertSuccess() {
        _convertSuccess.value = false
    }

    /**
     * Turns a held order into a real sale: writes the Sale + SaleItems, decrements stock,
     * records the stock movements, then marks the order CONVERTED.
     *
     * Lives here rather than in a second ViewModel so the screen has one source of truth and
     * the order list can be refreshed from the same place that changed it.
     */
    fun convertOrder(orderId: Long, paymentMethod: String, amountPaid: Double = 0.0, dueDate: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val order = orderRepo.getById(orderId) ?: return@launch
            val orderItems = orderRepo.getItemsByOrderId(orderId)

            val invoiceNumber = DateFormatter.generateInvoiceNumber()
            val shift = shiftRepo.getCurrentShiftSync()
            val isCredit = paymentMethod == "CREDIT"

            val sale = Sale(
                invoiceNumber = invoiceNumber,
                customerId = order.customerId,
                customerName = order.customerName,
                cashierName = "Cashier",
                paymentMethod = paymentMethod,
                saleType = paymentMethod,
                // A credit conversion is only PAID once the balance is cleared, so that the
                // Accounts Receivable screen can actually find it.
                paymentStatus = when {
                    !isCredit -> "PAID"
                    amountPaid >= order.totalAmount -> "PAID"
                    amountPaid > 0.0 -> "PARTIAL"
                    else -> "UNPAID"
                },
                dueDate = if (isCredit) dueDate else null,
                subtotal = order.subtotal,
                totalAmount = order.totalAmount,
                amountPaid = amountPaid,
                shiftId = shift?.id
            )
            val saleId = saleRepo.insert(sale)

            // SaleItem.costPrice is the only historical cost basis, and this path left it at its
            // 0.0 default exactly as completeSale did. One lookup covers every line, and it is
            // taken before stock is decremented so the figure is the cost at conversion time.
            val costByProductId = productRepo.getCostPriceMap()

            val saleItems = orderItems.map { orderItem ->
                SaleItem(
                    saleId = saleId,
                    productId = orderItem.productId,
                    productName = orderItem.productName,
                    sku = orderItem.sku,
                    quantity = orderItem.quantity,
                    unitPrice = orderItem.unitPrice,
                    costPrice = costByProductId[orderItem.productId] ?: 0.0,
                    totalPrice = orderItem.totalPrice
                )
            }
            saleRepo.insertItems(saleItems)

            for (orderItem in orderItems) {
                productRepo.updateStock(orderItem.productId, -orderItem.quantity)
                stockMovementRepo.insert(
                    StockMovement(
                        productId = orderItem.productId,
                        productName = orderItem.productName,
                        sku = orderItem.sku,
                        movementType = "OUT",
                        quantity = orderItem.quantity,
                        referenceType = "SALE",
                        referenceId = saleId,
                        description = "Order converted to Sale $invoiceNumber"
                    )
                )
            }

            orderRepo.convert(orderId)

            withContext(Dispatchers.Main) {
                _convertSuccess.value = true
            }
        }
    }
}
