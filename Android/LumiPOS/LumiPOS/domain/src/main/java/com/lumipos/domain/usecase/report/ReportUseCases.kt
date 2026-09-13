package com.lumipos.domain.usecase.report

import com.lumipos.data.repository.ExpensesRepository
import com.lumipos.data.repository.SalesRepository
import javax.inject.Inject

data class DailySalesSummary(
    val totalRevenue: Double,
    val totalOrders: Int,
    val totalItemsSold: Int
)

data class ProfitReport(
    val totalRevenue: Double,
    val totalCost: Double,
    val totalProfit: Double,
    val totalExpenses: Double,
    val netProfit: Double
)

data class SalesTrendPoint(
    val timeLabel: String,
    val revenue: Double,
    val orders: Int
)

data class TopProductEntry(
    val productId: Long,
    val productName: String,
    val unitsSold: Int,
    val revenue: Double
)

data class CashierPerformanceEntry(
    val cashierId: Long,
    val cashierName: String,
    val orders: Int,
    val revenue: Double
)

data class InventoryValueItem(
    val productId: Long,
    val productName: String,
    val stock: Int,
    val unitCost: Double,
    val totalValue: Double
)

data class DeadStockProduct(
    val productId: Long,
    val productName: String,
    val stock: Int,
    val lastSoldAt: Long?
)

data class SalesTimingBucket(
    val hourOfDay: Int,
    val weekday: Int,
    val orders: Int,
    val revenue: Double
)

class SalesReportUseCase @Inject constructor(
    private val salesRepository: SalesRepository
) {
    suspend fun getTotalSales(startTime: Long, endTime: Long, branchId: Long): Double? {
        return salesRepository.getTotalSalesByDateRange(startTime, endTime, branchId)
    }
}

class SalesTrendUseCase @Inject constructor(
    private val salesRepository: SalesRepository
) {
    suspend fun getTrend(startTime: Long, endTime: Long, branchId: Long): List<SalesTrendPoint> {
        var points: List<SalesTrendPoint> = emptyList()
        salesRepository.getSalesByDateRange(startTime, endTime, branchId).collect { saleList ->
            points = saleList.groupBy { sale ->
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = sale.saleDate }
                val dayStart = cal.apply {
                    set(cal.get(java.util.Calendar.HOUR_OF_DAY), 0)
                    set(cal.get(java.util.Calendar.MINUTE), 0)
                    set(cal.get(java.util.Calendar.SECOND), 0)
                    set(cal.get(java.util.Calendar.MILLISECOND), 0)
                }.timeInMillis
                dayStart
            }.map { (dayStart, sales) ->
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = dayStart }
                val label = "${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
                SalesTrendPoint(
                    timeLabel = label,
                    revenue = sales.sumOf { it.total },
                    orders = sales.size
                )
            }.sortedBy { it.timeLabel.let { _ -> java.util.Calendar.getInstance().timeInMillis } }
        }
        return points
    }
}

class TopProductsReportUseCase @Inject constructor(
    private val salesRepository: SalesRepository,
    private val productsRepository: ProductsRepository
) {
    suspend fun getTopProducts(startTime: Long, endTime: Long, branchId: Long, limit: Int = 10): List<TopProductEntry> {
        val productsById = HashMap<Long, String>()
        productsRepository.getAllActiveProducts().collect { list -> list.forEach { productsById[it.id] = it.name } }
        var entries: List<TopProductEntry> = emptyList()
        salesRepository.getSalesByDateRange(startTime, endTime, branchId).collect { saleList ->
            val map = HashMap<Long, TopProductEntry>()
            saleList.forEach { sale ->
                val items = salesRepository.getSaleItems(sale.id)
                items.forEach { item ->
                    val prev = map[item.productId]
                    map[item.productId] = TopProductEntry(
                        productId = item.productId,
                        productName = productsById[item.productId] ?: "Product",
                        unitsSold = (prev?.unitsSold ?: 0) + item.quantity,
                        revenue = (prev?.revenue ?: 0.0) + item.totalPrice
                    )
                }
            }
            entries = map.values.sortedByDescending { it.revenue }.take(limit)
        }
        return entries
    }
}

class CashierPerformanceReportUseCase @Inject constructor(
    private val salesRepository: SalesRepository,
    private val usersRepository: UsersRepository
) {
    suspend fun getCashierPerformance(startTime: Long, endTime: Long, branchId: Long): List<CashierPerformanceEntry> {
        val names = HashMap<Long, String>()
        usersRepository.getAllActiveUsers().collect { list ->
            list.forEach { names[it.id] = "${it.firstName} ${it.lastName}".trim().ifEmpty { it.username } }
        }
        var entries: List<CashierPerformanceEntry> = emptyList()
        salesRepository.getSalesByDateRange(startTime, endTime, branchId).collect { saleList ->
            val map = HashMap<Long, CashierPerformanceEntry>()
            saleList.forEach { sale ->
                val cashierId = sale.cashierId
                if (cashierId != null) {
                    val prev = map[cashierId]
                    map[cashierId] = CashierPerformanceEntry(
                        cashierId = cashierId,
                        cashierName = names[cashierId] ?: "Cashier",
                        orders = (prev?.orders ?: 0) + 1,
                        revenue = (prev?.revenue ?: 0.0) + sale.total
                    )
                }
            }
            entries = map.values.sortedByDescending { it.revenue }
        }
        return entries
    }
}

class InventoryValuationReportUseCase @Inject constructor(
    private val productsRepository: ProductsRepository
) {
    suspend fun getInventoryValuation(): List<InventoryValueItem> {
        var items: List<InventoryValueItem> = emptyList()
        productsRepository.getAllActiveProducts().collect { list ->
            items = list
                .filter { it.stock > 0 }
                .map {
                    InventoryValueItem(
                        productId = it.id,
                        productName = it.name,
                        stock = it.stock,
                        unitCost = it.cost,
                        totalValue = it.stock * it.cost
                    )
                }
        }
        return items
    }
}

class DeadStockReportUseCase @Inject constructor(
    private val salesRepository: SalesRepository,
    private val productsRepository: ProductsRepository
) {
    suspend fun getDeadStock(branchId: Long): List<DeadStockProduct> {
        var products = mutableListOf<DeadStockProduct>()
        productsRepository.getAllActiveProducts().collect { list ->
            products = list
                .filter { it.stock > 0 }
                .map {
                    DeadStockProduct(
                        productId = it.id,
                        productName = it.name,
                        stock = it.stock,
                        lastSoldAt = null
                    )
                }.toMutableList()
        }
        val soldProductIds = HashSet<Long>()
        var latestSoldAt = HashMap<Long, Long>()
        salesRepository.getAllSales().collect { saleList ->
            saleList.forEach { sale ->
                salesRepository.getSaleItems(sale.id).forEach { item ->
                    soldProductIds.add(item.productId)
                    val cur = latestSoldAt[item.productId]
                    if (cur == null || sale.saleDate > cur) latestSoldAt[item.productId] = sale.saleDate
                }
            }
        }
        return products
            .map { it.copy(lastSoldAt = latestSoldAt[it.productId]) }
            .filter { it.lastSoldAt == null || it.lastSoldAt!! < System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000 }
    }
}

class SalesTimingReportUseCase @Inject constructor(
    private val salesRepository: SalesRepository
) {
    suspend fun getTiming(startTime: Long, endTime: Long, branchId: Long): List<SalesTimingBucket> {
        var buckets: List<SalesTimingBucket> = emptyList()
        salesRepository.getSalesByDateRange(startTime, endTime, branchId).collect { saleList ->
            val map = HashMap<String, SalesTimingBucket>()
            saleList.forEach { sale ->
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = sale.saleDate }
                val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                val weekday = cal.get(java.util.Calendar.DAY_OF_WEEK)
                val key = "$hour|$weekday"
                val prev = map[key]
                map[key] = SalesTimingBucket(
                    hourOfDay = hour,
                    weekday = weekday,
                    orders = (prev?.orders ?: 0) + 1,
                    revenue = (prev?.revenue ?: 0.0) + sale.total
                )
            }
            buckets = map.values.toList()
        }
        return buckets
    }
}

class ProfitReportUseCase @Inject constructor(
    private val salesRepository: SalesRepository,
    private val expensesRepository: ExpensesRepository
) {
    suspend fun generateReport(startTime: Long, endTime: Long, branchId: Long): ProfitReport {
        val sales = salesRepository.getSalesByDateRange(startTime, endTime, branchId)
        var revenue = 0.0
        var cost = 0.0
        sales.collect { saleList ->
            revenue = saleList.sumOf { it.total }
            cost = saleList.sumOf { sale ->
                val items = salesRepository.getSaleItems(sale.id)
                items.sumOf { it.costPriceAtSale * it.quantity }
            }
        }
        val expenses = expensesRepository.getTotalExpensesByDateRange(startTime, endTime) ?: 0.0
        return ProfitReport(
            totalRevenue = revenue,
            totalCost = cost,
            totalProfit = revenue - cost,
            totalExpenses = expenses,
            netProfit = revenue - cost - expenses
        )
    }
}