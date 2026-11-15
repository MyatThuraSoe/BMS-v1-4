package com.example.lumipos.ui.sales

import android.app.Application
import androidx.lifecycle.*
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.data.local.entity.SaleItem
import com.example.lumipos.data.local.entity.StockMovement
import com.example.lumipos.data.repository.ProductRepository
import com.example.lumipos.data.repository.SaleRepository
import com.example.lumipos.data.repository.StockMovementRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SalesViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val saleRepo = SaleRepository(db.saleDao(), db.saleItemDao())
    private val productRepo = ProductRepository(db.productDao())
    private val stockMovementRepo = StockMovementRepository(db.stockMovementDao())
    private val saleDao = db.saleDao()

    private val _voidSuccess = MutableLiveData<Boolean>()
    val voidSuccess: LiveData<Boolean> = _voidSuccess

    private val _dateFilter = MutableStateFlow<Pair<Long, Long>?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedSales: Flow<PagingData<Sale>> = _dateFilter
        .flatMapLatest { range ->
            Pager(
                PagingConfig(
                    pageSize = PAGE_SIZE,
                    initialLoadSize = PAGE_SIZE * 2,
                    enablePlaceholders = false
                )
            ) {
                if (range == null) {
                    saleDao.getPaged()
                } else {
                    saleDao.getByDateRangePaged(range.first, range.second)
                }
            }.flow
        }
        .cachedIn(viewModelScope)

    fun filterByDateRange(start: Long?, end: Long?) {
        _dateFilter.value = if (start != null && end != null) start to end else null
    }

    /**
     * Voids a sale and returns its stock to inventory.
     *
     * The stock half is the important part. Voiding used to only flip `isVoided`, so the sale
     * dropped out of every revenue figure while the inventory it consumed stayed gone - each void
     * permanently shrank stock with nothing on the movements screen to explain it.
     *
     * The IN movement written here is the exact mirror of the OUT movement created when the sale
     * was rung up, so `getInboundMovements` picks it up and the audit trail stays complete.
     */
    fun voidSale(saleId: Long, reason: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val sale = saleRepo.getById(saleId) ?: return@launch
            // Guard against a double tap: without this the stock would be credited twice.
            if (sale.isVoided) return@launch

            saleRepo.update(
                sale.copy(
                    isVoided = true,
                    voidReason = reason,
                    voidedAt = System.currentTimeMillis()
                )
            )

            for (item in saleRepo.getItemsBySaleId(saleId)) {
                // Only units the customer actually kept come back; anything already refunded has
                // been credited once already.
                val returned = item.quantity - item.quantityRefunded
                if (returned <= 0) continue

                productRepo.updateStock(item.productId, returned)
                stockMovementRepo.insert(
                    StockMovement(
                        productId = item.productId,
                        productName = item.productName,
                        sku = item.sku,
                        movementType = "IN",
                        quantity = returned,
                        referenceType = "SALE_VOID",
                        referenceId = sale.id,
                        description = "Sale ${sale.invoiceNumber} voided: $reason"
                    )
                )
            }

            withContext(Dispatchers.Main) { _voidSuccess.value = true }
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}

class SaleDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val saleRepo = SaleRepository(db.saleDao(), db.saleItemDao())

    private val _sale = MutableLiveData<Sale?>()
    val sale: LiveData<Sale?> = _sale

    private val _saleItems = MutableLiveData<List<SaleItem>>()
    val saleItems: LiveData<List<SaleItem>> = _saleItems

    fun loadSale(saleId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _sale.postValue(saleRepo.getById(saleId))
            _saleItems.postValue(saleRepo.getItemsBySaleId(saleId))
        }
    }
}
