package com.example.lumipos.ui.dashboard

import android.app.Application
import androidx.lifecycle.*
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.*
import com.example.lumipos.data.repository.*
import com.example.lumipos.util.DateFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val productRepo = ProductRepository(db.productDao())
    private val saleRepo = SaleRepository(db.saleDao(), db.saleItemDao())
    private val shiftRepo = ShiftRepository(db.shiftDao())
    private val orderRepo = OrderRepository(db.orderDao(), db.orderItemDao())
    private val stockMovementRepo = StockMovementRepository(db.stockMovementDao())

    private val todayStart = DateFormatter.getTodayStart()
    private val todayEnd = DateFormatter.getTodayEnd()
    private val monthStart = DateFormatter.getMonthStart()
    private val monthEnd = DateFormatter.getTodayEnd()

    val todayRevenue: LiveData<Double?> = saleRepo.getTotalRevenue(todayStart, todayEnd)
    val todayTransactions: LiveData<Int> = saleRepo.getTransactionCount(todayStart, todayEnd)
    val monthRevenue: LiveData<Double?> = saleRepo.getTotalRevenue(monthStart, monthEnd)
    val monthTransactions: LiveData<Int> = saleRepo.getTransactionCount(monthStart, monthEnd)
    val totalProducts: LiveData<Int> = productRepo.activeCount
    val lowStockCount: LiveData<Int> = productRepo.lowStockCount
    val totalInventoryValue: LiveData<Double?> = productRepo.totalInventoryValue
    val currentShift: LiveData<Shift?> = shiftRepo.currentShift
    val recentSales: LiveData<List<Sale>> = saleRepo.getRecent(10)
    val pendingOrders: LiveData<Int> = orderRepo.pendingCount
    val lowStockProducts: LiveData<List<Product>> = productRepo.lowStockProducts

    private val now = System.currentTimeMillis()
    private val expiryWarningCutoff = now + 30L * 24L * 60L * 60L * 1000L
    val expiringSoonCount: LiveData<Int> = productRepo.getExpiringCountBy(expiryWarningCutoff)
    val expiredCount: LiveData<Int> = productRepo.getExpiredCount(now)

    private val _dailySalesData = MutableLiveData<List<DailySalesEntry>>()
    val dailySalesData: LiveData<List<DailySalesEntry>> = _dailySalesData

    init {
        loadWeeklySalesData()
    }

    private fun loadWeeklySalesData() {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = mutableListOf<DailySalesEntry>()
            for (i in 6 downTo 0) {
                val dayStart = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.DAY_OF_YEAR, -i)
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis

                // Derived from dayStart rather than dayStart + 86_400_000 so the bucket stays a
                // real calendar day even where the clocks shift.
                val dayEnd = java.util.Calendar.getInstance().apply {
                    timeInMillis = dayStart
                    set(java.util.Calendar.HOUR_OF_DAY, 23)
                    set(java.util.Calendar.MINUTE, 59)
                    set(java.util.Calendar.SECOND, 59)
                    set(java.util.Calendar.MILLISECOND, 999)
                }.timeInMillis

                val sales = saleRepo.getByDateRange(dayStart, dayEnd)
                val total = sales.filter { !it.isVoided }.sumOf { it.totalAmount }
                entries.add(DailySalesEntry(DateFormatter.formatDateShort(dayStart), total))
            }
            _dailySalesData.postValue(entries)
        }
    }

    fun refreshData() {
        loadWeeklySalesData()
    }
}

data class DailySalesEntry(
    val day: String,
    val amount: Double
)
