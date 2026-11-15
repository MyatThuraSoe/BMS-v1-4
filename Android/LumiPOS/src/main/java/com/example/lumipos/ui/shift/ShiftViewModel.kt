package com.example.lumipos.ui.shift

import android.app.Application
import androidx.lifecycle.*
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.SafeDrop
import com.example.lumipos.data.local.entity.Shift
import com.example.lumipos.data.repository.SafeDropRepository
import com.example.lumipos.data.repository.ShiftRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShiftViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val shiftRepo = ShiftRepository(db.shiftDao())
    private val safeDropRepo = SafeDropRepository(db.safeDropDao())

    val currentShift: LiveData<Shift?> = shiftRepo.currentShift
    val allShifts: LiveData<List<Shift>> = shiftRepo.allShifts

    private val _shiftClosed = MutableLiveData<Boolean>()
    val shiftClosed: LiveData<Boolean> = _shiftClosed

    private val _shiftCashSales = MutableLiveData<Double>()
    val shiftCashSales: LiveData<Double> = _shiftCashSales

    private val _safeDropTotal = MutableLiveData<Double>()
    val safeDropTotal: LiveData<Double> = _safeDropTotal

    private val _safeDropRecorded = MutableLiveData<Boolean>()
    val safeDropRecorded: LiveData<Boolean> = _safeDropRecorded

    fun loadShiftCashSales(shiftId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val cash = shiftRepo.getCashSalesForShift(shiftId) ?: 0.0
            withContext(Dispatchers.Main) {
                _shiftCashSales.value = cash
            }
        }
    }

    fun loadSafeDropTotal(shiftId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val total = safeDropRepo.getTotalForShift(shiftId)
            withContext(Dispatchers.Main) { _safeDropTotal.value = total }
        }
    }

    /** Records cash moved from the drawer to the safe mid-shift. */
    fun recordSafeDrop(shiftId: Long, amount: Double, reason: String) {
        viewModelScope.launch(Dispatchers.IO) {
            safeDropRepo.insert(
                SafeDrop(shiftId = shiftId, amount = amount, reason = reason)
            )
            val total = safeDropRepo.getTotalForShift(shiftId)
            withContext(Dispatchers.Main) {
                _safeDropTotal.value = total
                _safeDropRecorded.value = true
            }
        }
    }

    fun openShift(openingAmount: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            val shift = Shift(
                cashierName = "Cashier",
                openingAmount = openingAmount,
                status = "OPEN"
            )
            shiftRepo.insert(shift)
        }
    }

    fun closeShift(shiftId: Long, closingAmount: Double, notes: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val shift = shiftRepo.getById(shiftId) ?: return@launch
            val cashSales = shiftRepo.getCashSalesForShift(shiftId) ?: 0.0
            // Money moved to the safe no longer sits in the drawer, so it comes off the expected
            // figure; the variance is still closing-minus-expected but never shown before submit.
            val safeDrops = safeDropRepo.getTotalForShift(shiftId)
            val expected = shift.openingAmount + cashSales - safeDrops
            val variance = closingAmount - expected

            shiftRepo.closeShift(
                id = shiftId,
                closingTime = System.currentTimeMillis(),
                closingAmount = closingAmount,
                notes = notes,
                variance = variance,
                expected = expected
            )
            withContext(Dispatchers.Main) {
                _shiftClosed.value = true
            }
        }
    }
}
