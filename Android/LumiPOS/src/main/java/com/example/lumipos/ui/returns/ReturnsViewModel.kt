package com.example.lumipos.ui.returns

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.Return
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.data.repository.ReturnRepository
import com.example.lumipos.data.repository.SaleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReturnsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val returnRepo = ReturnRepository(db.returnDao(), db.returnItemDao())
    private val saleRepo = SaleRepository(db.saleDao(), db.saleItemDao())

    val returns: LiveData<List<Return>> = returnRepo.allReturns

    private val _lookedUpSale = MutableLiveData<Sale?>()
    val lookedUpSale: LiveData<Sale?> = _lookedUpSale

    private val _notFound = MutableLiveData(false)
    val notFound: LiveData<Boolean> = _notFound

    fun findSale(invoiceNumber: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val sale = saleRepo.getByInvoiceNumber(invoiceNumber.trim())
            withContext(Dispatchers.Main) {
                if (sale == null) {
                    _notFound.value = true
                } else {
                    _lookedUpSale.value = sale
                }
            }
        }
    }

    fun consumeLookup() {
        _lookedUpSale.value = null
        _notFound.value = false
    }
}
