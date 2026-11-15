package com.example.lumipos.ui.suppliers

import android.app.Application
import androidx.lifecycle.*
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.Supplier
import com.example.lumipos.data.repository.SupplierRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SuppliersViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val supplierRepo = SupplierRepository(db.supplierDao())

    val allSuppliers: LiveData<List<Supplier>> = supplierRepo.allSuppliers
    val count: LiveData<Int> = supplierRepo.count

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    private val _filteredSuppliers = MutableLiveData<List<Supplier>>()
    val filteredSuppliers: LiveData<List<Supplier>> = _filteredSuppliers

    fun search(query: String) {
        _searchQuery.value = query
        viewModelScope.launch(Dispatchers.IO) {
            if (query.isBlank()) {
                _filteredSuppliers.postValue(supplierRepo.getAllList())
            } else {
                val all = supplierRepo.getAllList()
                _filteredSuppliers.postValue(all.filter {
                    it.name.contains(query, true) ||
                    it.contactPerson.contains(query, true) ||
                    it.phone.contains(query, true)
                })
            }
        }
    }

    fun deleteSupplier(supplier: Supplier) {
        viewModelScope.launch(Dispatchers.IO) {
            supplierRepo.delete(supplier)
        }
    }

    fun loadSuppliers() {
        viewModelScope.launch(Dispatchers.IO) {
            _filteredSuppliers.postValue(supplierRepo.getAllList())
        }
    }
}

class SupplierFormViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val supplierRepo = SupplierRepository(db.supplierDao())

    private val _supplier = MutableLiveData<Supplier?>()
    val supplier: LiveData<Supplier?> = _supplier

    private val _saveSuccess = MutableLiveData<Boolean>()
    val saveSuccess: LiveData<Boolean> = _saveSuccess

    fun loadSupplier(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _supplier.postValue(supplierRepo.getById(id))
        }
    }

    fun saveSupplier(supplier: Supplier) {
        viewModelScope.launch(Dispatchers.IO) {
            if (supplier.id == 0L) {
                supplierRepo.insert(supplier)
            } else {
                supplierRepo.update(supplier)
            }
            withContext(Dispatchers.Main) {
                _saveSuccess.value = true
            }
        }
    }
}
