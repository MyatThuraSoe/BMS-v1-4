package com.lumipos.app.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.schema.CustomerEntity
import com.lumipos.domain.usecase.customer.AddCustomerUseCase
import com.lumipos.domain.usecase.customer.GetCustomersUseCase
import com.lumipos.domain.usecase.customer.UpdateCustomerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CustomerFormState(
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val formError: String? = null
)

data class CustomersUiState(
    val customers: List<CustomerEntity> = emptyList(),
    val isLoading: Boolean = true,
    val showForm: Boolean = false,
    val editingCustomer: CustomerEntity? = null,
    val form: CustomerFormState = CustomerFormState(),
    val error: String? = null,
    val searchQuery: String = ""
)

@HiltViewModel
class CustomersViewModel @Inject constructor(
    private val getCustomersUseCase: GetCustomersUseCase,
    private val addCustomerUseCase: AddCustomerUseCase,
    private val updateCustomerUseCase: UpdateCustomerUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(CustomersUiState())
    val uiState: StateFlow<CustomersUiState> = _uiState

    init {
        viewModelScope.launch {
            getCustomersUseCase.getAllActiveCustomers().collect { list ->
                _uiState.update { it.copy(customers = list, isLoading = false) }
            }
        }
    }

    fun onSearchChange(q: String) = _uiState.update { it.copy(searchQuery = q) }

    fun openAdd() = _uiState.update {
        it.copy(showForm = true, editingCustomer = null, form = CustomerFormState(), error = null)
    }

    fun openEdit(c: CustomerEntity) = _uiState.update {
        it.copy(
            showForm = true,
            editingCustomer = c,
            form = CustomerFormState(name = c.name, phone = c.phone, email = c.email, address = c.address),
            error = null
        )
    }

    fun closeForm() = _uiState.update { it.copy(showForm = false) }
    fun onNameChange(v: String) = _uiState.update { it.copy(form = it.form.copy(name = v, formError = null)) }
    fun onPhoneChange(v: String) = _uiState.update { it.copy(form = it.form.copy(phone = v)) }
    fun onEmailChange(v: String) = _uiState.update { it.copy(form = it.form.copy(email = v)) }
    fun onAddressChange(v: String) = _uiState.update { it.copy(form = it.form.copy(address = v)) }

    fun saveCustomer() {
        val f = _uiState.value.form
        if (f.name.isBlank()) {
            _uiState.update { it.copy(form = it.form.copy(formError = "Name is required")) }
            return
        }
        viewModelScope.launch {
            try {
                val editing = _uiState.value.editingCustomer
                if (editing == null) {
                    addCustomerUseCase(f.name.trim(), f.phone.trim(), f.email.trim(), f.address.trim())
                } else {
                    updateCustomerUseCase(editing.copy(name = f.name.trim(), phone = f.phone.trim(), email = f.email.trim(), address = f.address.trim()))
                }
                closeForm()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteCustomer(c: CustomerEntity) {
        viewModelScope.launch {
            try { updateCustomerUseCase.deleteCustomer(c) }
            catch (e: Exception) { _uiState.update { it.copy(error = e.message) } }
        }
    }
}