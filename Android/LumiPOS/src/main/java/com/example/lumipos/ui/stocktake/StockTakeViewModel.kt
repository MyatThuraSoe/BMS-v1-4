package com.example.lumipos.ui.stocktake

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.StockTake
import com.example.lumipos.data.local.entity.StockTakeItem
import com.example.lumipos.data.repository.StockTakeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StockTakesViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val repo = StockTakeRepository(db.stockTakeDao(), db.stockTakeItemDao(), db.productDao(), db.stockMovementDao())

    val takes: LiveData<List<StockTake>> = repo.allTakes

    private val _startedId = MutableLiveData<Long?>()
    val startedId: LiveData<Long?> = _startedId

    fun startCount() {
        viewModelScope.launch(Dispatchers.IO) {
            val id = repo.startCount(startedBy = "Cashier")
            _startedId.postValue(id)
        }
    }

    fun consumedStartedId() {
        _startedId.value = null
    }

    fun delete(take: StockTake) {
        viewModelScope.launch(Dispatchers.IO) { repo.removeTake(take.id) }
    }
}

class StockTakeFormViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val repo = StockTakeRepository(db.stockTakeDao(), db.stockTakeItemDao(), db.productDao(), db.stockMovementDao())

    private val takeId = MutableLiveData<Long>()
    val take: LiveData<StockTake?> = takeId.switchMap { repo.getByIdLive(it) }
    val items: LiveData<List<StockTakeItem>> = takeId.switchMap { repo.getItemsLive(it) }

    private val _completed = MutableLiveData<Boolean>()
    val completed: LiveData<Boolean> = _completed

    fun load(id: Long) {
        if (takeId.value != id) takeId.value = id
    }

    fun setCounted(itemId: Long, counted: Int?) {
        viewModelScope.launch(Dispatchers.IO) { repo.setCounted(itemId, counted) }
    }

    fun complete() {
        val id = takeId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repo.completeCount(id, completedBy = "Cashier")
            _completed.postValue(true)
        }
    }
}
