package com.lumipos.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.repository.CashShiftsRepository
import com.lumipos.data.repository.CustomersRepository
import com.lumipos.data.repository.ProductsRepository
import com.lumipos.data.repository.SalesRepository
import com.lumipos.data.repository.SessionRepository
import com.lumipos.data.repository.ShopInfoRepository
import com.lumipos.data.schema.CashShiftEntity
import com.lumipos.data.schema.ProductEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class DashboardUiState(
    val isLoading: Boolean = true,
    val shopName: String = "",
    val currencySymbol: String = "$",
    val todayRevenue: Double = 0.0,
    val todayOrders: Int = 0,
    val todayItems: Int = 0,
    val productCount: Int = 0,
    val customerCount: Int = 0,
    val lowStock: List<ProductEntity> = emptyList(),
    val openShift: CashShiftEntity? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val shopInfoRepository: ShopInfoRepository,
    private val salesRepository: SalesRepository,
    private val productsRepository: ProductsRepository,
    private val customersRepository: CustomersRepository,
    private val cashShiftsRepository: CashShiftsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = load()
        }
    }

    private suspend fun load(): DashboardUiState {
        val branchId = sessionRepository.session.first().branchId ?: 1L
        val shop = shopInfoRepository.getShopInfoOnce()
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = start + TimeUnit.DAYS.toMillis(1) - 1

        val todaySales = salesRepository.getSalesByDateRange(start, end, branchId).first()
        var items = 0
        todaySales.forEach { sale ->
            items += salesRepository.getSaleItems(sale.id).sumOf { it.quantity }
        }

        return DashboardUiState(
            isLoading = false,
            shopName = shop?.shopName ?: "",
            currencySymbol = shop?.currency ?: "$",
            todayRevenue = todaySales.sumOf { it.total },
            todayOrders = todaySales.size,
            todayItems = items,
            productCount = productsRepository.getProductCount(),
            customerCount = customersRepository.getAllActiveCustomers().first().size,
            lowStock = productsRepository.getLowStockProducts(10).first(),
            openShift = cashShiftsRepository.getCurrentOpenShift()
        )
    }
}