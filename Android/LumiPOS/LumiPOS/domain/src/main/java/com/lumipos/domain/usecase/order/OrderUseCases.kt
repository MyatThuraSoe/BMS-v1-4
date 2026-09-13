package com.lumipos.domain.usecase.order

import com.lumipos.data.repository.OrdersRepository
import com.lumipos.data.repository.SalesRepository
import com.lumipos.data.schema.OrderEntity
import com.lumipos.data.schema.OrderItemEntity
import com.lumipos.data.schema.SaleEntity
import com.lumipos.data.schema.SaleItemEntity
import com.lumipos.domain.usecase.sequence.NextInvoiceNumberUseCase
import com.lumipos.domain.usecase.sequence.NextOrderNumberUseCase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

object OrderStatus {
    const val PENDING = "PENDING"
    const val IN_PROGRESS = "IN_PROGRESS"
    const val READY = "READY"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
}

class GetOrdersUseCase @Inject constructor(
    private val ordersRepository: OrdersRepository
) {
    fun getOrdersByBranch(branchId: Long): Flow<List<OrderEntity>> = ordersRepository.getOrdersByBranch(branchId)

    fun getOrdersByBranchDateRange(branchId: Long, startTime: Long, endTime: Long): Flow<List<OrderEntity>> =
        ordersRepository.getOrdersByBranchDateRange(branchId, startTime, endTime)

    fun getOrdersByStatus(status: String, branchId: Long): Flow<List<OrderEntity>> = ordersRepository.getOrdersByStatus(status, branchId)

    suspend operator fun invoke(id: Long): OrderEntity? = ordersRepository.getOrderById(id)

    suspend fun getOrderItems(orderId: Long) = ordersRepository.getOrderItems(orderId)
}

class CreateOrderUseCase @Inject constructor(
    private val ordersRepository: OrdersRepository,
    private val nextOrderNumberUseCase: NextOrderNumberUseCase
) {
    suspend operator fun invoke(
        orderNumber: String,
        branchId: Long,
        customerId: Long?,
        items: List<Pair<Long, Pair<Int, Double>>>,
        paymentMethod: String,
        orderType: String = "DINE_IN",
        notes: String = "",
        tax: Double = 0.0
    ): OrderEntity {
        val subtotal = items.sumOf { (_, priceQty) ->
            val (qty, price) = priceQty
            qty * price
        }
        val total = subtotal + tax
        val order = OrderEntity(
            orderNumber = orderNumber.ifBlank { nextOrderNumberUseCase() },
            branchId = branchId,
            customerId = customerId,
            orderType = orderType,
            status = OrderStatus.PENDING,
            subtotal = subtotal,
            taxAmount = tax,
            totalAmount = total,
            notes = notes,
            paymentMethod = paymentMethod
        )
        val orderId = ordersRepository.insertOrder(order)
        val orderItems = items.map { (productId, priceQty) ->
            val (qty, price) = priceQty
            OrderItemEntity(
                orderId = orderId,
                productId = productId,
                quantity = qty,
                unitPrice = price,
                subtotal = qty * price
            )
        }
        ordersRepository.insertOrderItems(orderItems)
        return order.copy(id = orderId)
    }
}

class UpdateOrderUseCase @Inject constructor(
    private val ordersRepository: OrdersRepository
) {
    suspend fun markStatus(orderId: Long, status: String, totalAmount: Double) {
        ordersRepository.updateOrderStatus(orderId, status, totalAmount)
    }

    suspend fun markCompleted(orderId: Long) {
        ordersRepository.updateOrderCompleted(orderId, OrderStatus.COMPLETED, System.currentTimeMillis())
    }

    suspend fun markCancelled(orderId: Long, reason: String?) {
        ordersRepository.updateOrderCancelled(orderId, OrderStatus.CANCELLED, reason, System.currentTimeMillis())
    }

    suspend fun deleteOrder(orderId: Long) {
        ordersRepository.deleteOrderById(orderId)
    }
}

class ConvertOrderToSaleUseCase @Inject constructor(
    private val ordersRepository: OrdersRepository,
    private val salesRepository: SalesRepository,
    private val nextInvoiceNumberUseCase: NextInvoiceNumberUseCase
) {
    suspend operator fun invoke(
        orderId: Long,
        branchId: Long,
        cashierId: Long?,
        customerId: Long?,
        amountPaid: Double,
        paymentMethod: String,
        paymentStatus: String = "PAID",
        cashShiftId: Long? = null
    ): SaleEntity? {
        val order = ordersRepository.getOrderById(orderId) ?: return null
        val orderItems = ordersRepository.getOrderItems(orderId)
        val items = orderItems.map { item ->
            SaleItemEntity(
                saleId = 0,
                productId = item.productId,
                quantity = item.quantity,
                unitPrice = item.unitPrice,
                totalPrice = item.subtotal
            )
        }
        val sale = SaleEntity(
            invoiceNumber = nextInvoiceNumberUseCase(),
            branchId = branchId,
            customerId = customerId ?: order.customerId,
            cashierId = cashierId,
            subtotal = order.subtotal,
            tax = order.taxAmount,
            total = order.totalAmount,
            amountPaid = amountPaid,
            changeGiven = (amountPaid - order.totalAmount).coerceAtLeast(0.0),
            paymentMethod = paymentMethod,
            saleType = "ORDER_SALE",
            paymentStatus = paymentStatus,
            cashShiftId = cashShiftId,
            notes = "From order ${order.orderNumber}"
        )
        val saleId = salesRepository.insertSale(sale)
        salesRepository.insertSaleItems(items.map { it.copy(saleId = saleId) })

        val now = System.currentTimeMillis()
        ordersRepository.updateOrderConvertedSaleId(orderId, saleId)
        ordersRepository.updateOrderCompleted(orderId, OrderStatus.COMPLETED, now)
        return sale.copy(id = saleId)
    }
}

class CleanupStaleDraftOrdersUseCase @Inject constructor(
    private val ordersRepository: OrdersRepository
) {
    suspend operator fun invoke(ttlMillis: Long = 12L * 60L * 60L * 1000L) {
        val threshold = System.currentTimeMillis() - ttlMillis
        ordersRepository.deleteStaleDraftOrders(threshold)
    }
}