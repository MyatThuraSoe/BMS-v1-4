package com.lumipos.app.ui.balances

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.repository.CustomersRepository
import com.lumipos.data.schema.ArPaymentEntity
import com.lumipos.data.schema.CustomerEntity
import com.lumipos.domain.usecase.ar.GetArPaymentsUseCase
import com.lumipos.domain.usecase.ar.RecordArPaymentUseCase
import com.lumipos.domain.usecase.session.ObserveSessionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CustomerBalancesUiState(
    val customers: List<CustomerEntity> = emptyList(),
    val payments: List<ArPaymentEntity> = emptyList(),
    val isLoading: Boolean = true,
    val selectedCustomer: CustomerEntity? = null,
    val showPaymentDialog: Boolean = false,
    val amount: String = "",
    val paymentMethod: String = "CASH",
    val reference: String = "",
    val notes: String = "",
    val isSaving: Boolean = false,
    val message: String? = null,
    val error: String? = null
) {
    val totalOutstanding: Double get() = customers.sumOf { it.currentBalance }
}

@HiltViewModel
class CustomerBalancesViewModel @Inject constructor(
    private val customersRepository: CustomersRepository,
    private val getArPaymentsUseCase: GetArPaymentsUseCase,
    private val recordArPaymentUseCase: RecordArPaymentUseCase,
    private val observeSessionUseCase: ObserveSessionUseCase
) : ViewModel() {

    private var currentUserId: Long? = null
    private val _uiState = MutableStateFlow(CustomerBalancesUiState())
    val uiState: StateFlow<CustomerBalancesUiState> = _uiState

    init {
        viewModelScope.launch {
            currentUserId = observeSessionUseCase().first().userId
        }
        viewModelScope.launch {
            customersRepository.getAllCustomers().collect { list ->
                _uiState.update { it.copy(customers = list.sortedByDescending { c -> c.currentBalance }, isLoading = false) }
            }
        }
        viewModelScope.launch {
            getArPaymentsUseCase.getAllPayments().collect { list ->
                _uiState.update { it.copy(payments = list) }
            }
        }
    }

    fun selectCustomer(customer: CustomerEntity) {
        _uiState.update { it.copy(selectedCustomer = customer) }
        viewModelScope.launch {
            getArPaymentsUseCase.getPaymentsByCustomer(customer.id).collect { list ->
                _uiState.update { it.copy(payments = list) }
            }
        }
    }

    fun dismissCustomer() = _uiState.update { it.copy(selectedCustomer = null, payments = emptyList()) }

    fun openPaymentDialog(customer: CustomerEntity) = _uiState.update {
        it.copy(selectedCustomer = customer, showPaymentDialog = true, amount = "", reference = "", notes = "")
    }

    fun dismissPaymentDialog() = _uiState.update { it.copy(showPaymentDialog = false) }

    fun setAmount(value: String) = _uiState.update { it.copy(amount = value) }
    fun setPaymentMethod(method: String) = _uiState.update { it.copy(paymentMethod = method) }
    fun setReference(value: String) = _uiState.update { it.copy(reference = value) }
    fun setNotes(value: String) = _uiState.update { it.copy(notes = value) }

    fun recordPayment() {
        val customer = _uiState.value.selectedCustomer ?: return
        val amount = _uiState.value.amount.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            _uiState.update { it.copy(error = "Enter a valid amount") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                recordArPaymentUseCase(
                    customerId = customer.id,
                    amount = amount,
                    paymentMethod = _uiState.value.paymentMethod,
                    reference = _uiState.value.reference.trim(),
                    notes = _uiState.value.notes.trim(),
                    createdBy = currentUserId
                )
                _uiState.update { it.copy(isSaving = false, showPaymentDialog = false, message = "Payment recorded") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }
}