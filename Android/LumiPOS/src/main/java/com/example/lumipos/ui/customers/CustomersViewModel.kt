package com.example.lumipos.ui.customers

import android.app.Application
import androidx.lifecycle.*
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.Customer
import com.example.lumipos.data.repository.CustomerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomersViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val customerRepo = CustomerRepository(db.customerDao())

    val allCustomers: LiveData<List<Customer>> = customerRepo.allCustomers
    val cities: LiveData<List<String>> = customerRepo.cities
    val count: LiveData<Int> = customerRepo.count

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    private val _filteredCustomers = MutableLiveData<List<Customer>>()
    val filteredCustomers: LiveData<List<Customer>> = _filteredCustomers

    fun search(query: String) {
        _searchQuery.value = query
        viewModelScope.launch(Dispatchers.IO) {
            if (query.isBlank()) {
                _filteredCustomers.postValue(customerRepo.getAllList())
            } else {
                _filteredCustomers.postValue(customerRepo.searchList(query))
            }
        }
    }

    fun deleteCustomer(customer: Customer) {
        viewModelScope.launch(Dispatchers.IO) {
            customerRepo.delete(customer)
        }
    }

    fun loadCustomers() {
        viewModelScope.launch(Dispatchers.IO) {
            _filteredCustomers.postValue(customerRepo.getAllList())
        }
    }
}

class CustomerFormViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val customerRepo = CustomerRepository(db.customerDao())
    private val loyaltyRepo =
        com.example.lumipos.data.repository.LoyaltyRepository(db.customerDao(), db.loyaltyTransactionDao(), db.settingDao())

    private val _customer = MutableLiveData<Customer?>()
    val customer: LiveData<Customer?> = _customer

    private val _saveSuccess = MutableLiveData<Boolean>()
    val saveSuccess: LiveData<Boolean> = _saveSuccess

    fun loadCustomer(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _customer.postValue(customerRepo.getById(id))
        }
    }

    fun saveCustomer(customer: Customer) {
        viewModelScope.launch(Dispatchers.IO) {
            if (customer.id == 0L) {
                customerRepo.insert(customer)
            } else {
                customerRepo.update(customer)
            }
            withContext(Dispatchers.Main) {
                _saveSuccess.value = true
            }
        }
    }

    /** Tops up the wallet by [amount] and refreshes the on-screen balance. */
    fun addStoreCredit(amount: Double) {
        val id = _customer.value?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            loyaltyRepo.addStoreCredit(id, amount, "Manual top-up")
            _customer.postValue(customerRepo.getById(id))
        }
    }

    /** Burns [points] into store credit and refreshes the on-screen balances. */
    fun redeemPoints(points: Int) {
        val id = _customer.value?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            loyaltyRepo.redeemPoints(id, points)
            _customer.postValue(customerRepo.getById(id))
        }
    }
}
