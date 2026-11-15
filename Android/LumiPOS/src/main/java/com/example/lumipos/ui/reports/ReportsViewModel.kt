package com.example.lumipos.ui.reports

import android.app.Application
import androidx.lifecycle.*
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.data.repository.ProductRepository
import com.example.lumipos.data.repository.SaleRepository
import com.example.lumipos.util.DateFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

data class DailySalesReport(
    val totalTransactions: Int = 0,
    val totalRevenue: Double = 0.0,
    val totalItemsSold: Int = 0,
    val averageTransactionValue: Double = 0.0
)

data class SalesTrendEntry(
    val date: String,
    val totalSales: Double
)

data class TopProductEntry(
    val productName: String,
    val totalQuantitySold: Int,
    val totalRevenue: Double
)

/**
 * VAT/GST position for a period: tax charged to customers (output) minus tax paid to suppliers on
 * purchases and expenses (input). A positive [netPayable] is owed to the tax authority.
 */
data class TaxLiabilityReport(
    val outputTax: Double = 0.0,
    val inputTaxPurchases: Double = 0.0,
    val inputTaxExpenses: Double = 0.0
) {
    val inputTax: Double get() = inputTaxPurchases + inputTaxExpenses
    val netPayable: Double get() = outputTax - inputTax
}

/** A product that will run out soon, with a suggested purchase quantity. */
data class ReorderSuggestion(
    val productId: Long,
    val productName: String,
    val stockQuantity: Int,
    val dailyVelocity: Double,
    val daysOfCover: Double,
    val suggestedReorderQty: Int
)

/** A product that has not sold in the dead-stock window (or at all) yet still ties up stock. */
data class DeadStockEntry(
    val productId: Long,
    val productName: String,
    val sku: String,
    val stockQuantity: Int,
    val stockValue: Double,
    val lastSoldAt: Long?
)

class ReportsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val saleRepo = SaleRepository(db.saleDao(), db.saleItemDao())
    private val productRepo = ProductRepository(db.productDao())

    private val _dailyReport = MutableLiveData<DailySalesReport>()
    val dailyReport: LiveData<DailySalesReport> = _dailyReport

    private val _salesTrend = MutableLiveData<List<SalesTrendEntry>>()
    val salesTrend: LiveData<List<SalesTrendEntry>> = _salesTrend

    private val _topProducts = MutableLiveData<List<TopProductEntry>>()
    val topProducts: LiveData<List<TopProductEntry>> = _topProducts

    private val _dateRangeFilter = MutableStateFlow<Pair<Long, Long>?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val dateRangeSales: Flow<PagingData<Sale>> = _dateRangeFilter
        .flatMapLatest { range ->
            Pager(
                PagingConfig(
                    pageSize = PAGE_SIZE,
                    initialLoadSize = PAGE_SIZE * 2,
                    enablePlaceholders = false
                )
            ) {
                if (range == null) {
                    db.saleDao().getPaged()
                } else {
                    db.saleDao().getByDateRangePaged(range.first, range.second)
                }
            }.flow
        }
        .cachedIn(viewModelScope)

    private val _taxLiability = MutableLiveData<TaxLiabilityReport>()
    val taxLiability: LiveData<TaxLiabilityReport> = _taxLiability

    private val _reorderSuggestions = MutableLiveData<List<ReorderSuggestion>>()
    val reorderSuggestions: LiveData<List<ReorderSuggestion>> = _reorderSuggestions

    private val _deadStock = MutableLiveData<List<DeadStockEntry>>()
    val deadStock: LiveData<List<DeadStockEntry>> = _deadStock

    val totalProducts: LiveData<Int> = productRepo.activeCount
    val lowStockCount: LiveData<Int> = productRepo.lowStockCount
    val totalInventoryValue: LiveData<Double?> = productRepo.totalInventoryValue

    fun loadDailyReport(dateStart: Long, dateEnd: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val sales = saleRepo.getByDateRange(dateStart, dateEnd).filter { !it.isVoided }
            val totalRevenue = sales.sumOf { it.totalAmount }
            val totalTransactions = sales.size
            val averageValue = if (totalTransactions > 0) totalRevenue / totalTransactions else 0.0

            // Items sold was declared on the report but never computed, so the metric always
            // read as 0. Net of refunds, matching how Top Products counts quantity.
            val itemsSold = if (sales.isEmpty()) {
                0
            } else {
                saleRepo.getItemsBySaleIds(sales.map { it.id })
                    .sumOf { (it.quantity - it.quantityRefunded).coerceAtLeast(0) }
            }

            _dailyReport.postValue(DailySalesReport(
                totalTransactions = totalTransactions,
                totalRevenue = totalRevenue,
                totalItemsSold = itemsSold,
                averageTransactionValue = averageValue
            ))
        }
    }

    fun loadSalesTrend(days: Int = 7) {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = mutableListOf<SalesTrendEntry>()
            for (i in days - 1 downTo 0) {
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.DAY_OF_YEAR, -i)
                val dayStart = cal.apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                val dayEnd = dayStart + 86400000L - 1

                val sales = saleRepo.getByDateRange(dayStart, dayEnd).filter { !it.isVoided }
                entries.add(SalesTrendEntry(
                    date = DateFormatter.formatDateShort(dayStart),
                    totalSales = sales.sumOf { it.totalAmount }
                ))
            }
            _salesTrend.postValue(entries)
        }
    }

    fun loadDateRangeSales(startDate: Long, endDate: Long) {
        _dateRangeFilter.value = startDate to endDate
    }

    fun loadTaxLiability(startDate: Long, endDate: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            // Output tax is summed from the sales themselves (refunds recorded on the sale are
            // already netted out of totalAmount, and taxAmount is the tax charged at the till).
            val outputTax = saleRepo.getByDateRange(startDate, endDate)
                .filter { !it.isVoided }
                .sumOf { it.taxAmount }
            val inputPurchases = db.purchaseDao().getTaxPaid(startDate, endDate) ?: 0.0
            val inputExpenses = db.expenseDao().getTaxPaid(startDate, endDate) ?: 0.0
            _taxLiability.postValue(
                TaxLiabilityReport(
                    outputTax = outputTax,
                    inputTaxPurchases = inputPurchases,
                    inputTaxExpenses = inputExpenses
                )
            )
        }
    }

    /**
     * "You will run out of X by Thursday": last [days] of sales give each product a daily
     * velocity; anything with under a week of cover gets a suggestion sized to restore a
     * fortnight of stock. Products that have not sold at all are left to the dead-stock report
     * rather than suggested for reorder.
     */
    fun loadReorderSuggestions(days: Int = 30) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cutoff = now - days.toLong() * 86400000L
            val sales = saleRepo.getByDateRange(cutoff, now).filter { !it.isVoided }
            val items = saleRepo.getItemsBySaleIds(sales.map { it.id })
            val soldQty = mutableMapOf<Long, Int>()
            for (item in items) {
                val qty = (item.quantity - item.quantityRefunded).coerceAtLeast(0)
                soldQty[item.productId] = (soldQty[item.productId] ?: 0) + qty
            }

            val suggestions = productRepo.getAllActiveList()
                .filter { !it.isBundle }
                .mapNotNull { product ->
                    val velocity = (soldQty[product.id] ?: 0).toDouble() / days
                    if (velocity <= 0.0) return@mapNotNull null
                    val cover = product.stockQuantity / velocity
                    if (cover > 7.0) return@mapNotNull null
                    val target = maxOf(
                        kotlin.math.ceil(velocity * 14.0).toInt(),
                        product.minStockLevel * 2
                    )
                    ReorderSuggestion(
                        productId = product.id,
                        productName = product.name,
                        stockQuantity = product.stockQuantity,
                        dailyVelocity = velocity,
                        daysOfCover = cover,
                        suggestedReorderQty = (target - product.stockQuantity).coerceAtLeast(1)
                    )
                }
                .sortedBy { it.daysOfCover }
            _reorderSuggestions.postValue(suggestions)
        }
    }

    /**
     * Stock sitting on the shelf with no movement in [minDays]: money that is not working.
     * Value is at cost where a cost is recorded, else at retail.
     */
    fun loadDeadStock(minDays: Int = 90) {
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - minDays.toLong() * 86400000L
            val soldSince = saleRepo.getProductIdsSoldSince(cutoff).toSet()
            val lastSold = saleRepo.getLastSoldDates().associate { it.productId to it.lastSoldAt }
            val entries = productRepo.getAllActiveList()
                .filter { !it.isBundle && it.stockQuantity > 0 && it.id !in soldSince }
                .map { product ->
                    DeadStockEntry(
                        productId = product.id,
                        productName = product.name,
                        sku = product.sku,
                        stockQuantity = product.stockQuantity,
                        stockValue = product.stockQuantity *
                            (if (product.costPrice > 0) product.costPrice else product.unitPrice),
                        lastSoldAt = lastSold[product.id]
                    )
                }
                .sortedByDescending { it.stockValue }
            _deadStock.postValue(entries)
        }
    }

    fun loadTopProducts(startDate: Long, endDate: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val sales = saleRepo.getByDateRange(startDate, endDate).filter { !it.isVoided }
            val items = saleRepo.getItemsBySaleIds(sales.map { it.id })
            val totals = mutableMapOf<Long, TopProductEntry>()
            for (item in items) {
                val qty = item.quantity - item.quantityRefunded
                if (qty <= 0) continue
                val existing = totals[item.productId]
                totals[item.productId] = TopProductEntry(
                    productName = item.productName,
                    totalQuantitySold = (existing?.totalQuantitySold ?: 0) + qty,
                    totalRevenue = (existing?.totalRevenue ?: 0.0) + item.totalPrice
                )
            }
            _topProducts.postValue(
                totals.values
                    .sortedByDescending { it.totalQuantitySold }
                    .take(10)
            )
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
