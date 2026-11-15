package com.example.lumipos.ui.transfers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.StockTransfer
import com.example.lumipos.data.local.entity.StockTransferItem
import com.example.lumipos.data.repository.StockTransferRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StockTransfersViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val repo = StockTransferRepository(
        db.stockTransferDao(), db.stockTransferItemDao(), db.productDao(), db.stockMovementDao()
    )

    val transfers: LiveData<List<StockTransfer>> = repo.allTransfers

    fun delete(transfer: StockTransfer) {
        viewModelScope.launch(Dispatchers.IO) { repo.deleteTransfer(transfer.id) }
    }
}

class StockTransferFormViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val repo = StockTransferRepository(
        db.stockTransferDao(), db.stockTransferItemDao(), db.productDao(), db.stockMovementDao()
    )

    private val transferId = MutableLiveData<Long>()
    val transfer: LiveData<StockTransfer?> = transferId.switchMap { repo.getByIdLive(it) }
    val items: LiveData<List<StockTransferItem>> = transferId.switchMap { repo.getItemsLive(it) }

    val products: LiveData<List<com.example.lumipos.data.local.entity.Product>> =
        com.example.lumipos.data.repository.ProductRepository(db.productDao()).allActiveProducts

    private val _createdId = MutableLiveData<Long?>()
    val createdId: LiveData<Long?> = _createdId

    private val _actionDone = MutableLiveData<Boolean>()
    val actionDone: LiveData<Boolean> = _actionDone

    fun load(id: Long) {
        if (id > 0 && transferId.value != id) transferId.value = id
    }

    fun createDraft(fromLocation: String, toLocation: String, notes: String, items: List<StockTransferItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = repo.createDraft(fromLocation, toLocation, notes, "Cashier", items)
            _createdId.postValue(id)
        }
    }

    fun consumedCreatedId() {
        _createdId.value = null
    }

    fun send() {
        val id = transferId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repo.sendTransfer(id, "Cashier")
            _actionDone.postValue(true)
        }
    }

    fun receive() {
        val id = transferId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repo.receiveTransfer(id, "Cashier")
            _actionDone.postValue(true)
        }
    }

    fun cancel() {
        val id = transferId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repo.cancelTransfer(id)
            _actionDone.postValue(true)
        }
    }
}
