package com.lumipos.app.ui.suppliers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.schema.SupplierEntity
import com.lumipos.domain.usecase.supplier.AddSupplierUseCase
import com.lumipos.domain.usecase.supplier.GetSuppliersUseCase
import com.lumipos.domain.usecase.supplier.UpdateSupplierUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SupplierFormState(
    val name: String = "",
    val contactPerson: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val taxId: String = "",
    val paymentTerms: String = "",
    val notes: String = "",
    val error: String? = null
)

data class SuppliersUiState(
    val suppliers: List<SupplierEntity> = emptyList(),
    val isLoading: Boolean = true,
    val showForm: Boolean = false,
    val editingSupplier: SupplierEntity? = null,
    val form: SupplierFormState = SupplierFormState(),
    val error: String? = null,
    val message: String? = null
)

@HiltViewModel
class SuppliersViewModel @Inject constructor(
    private val getSuppliersUseCase: GetSuppliersUseCase,
    private val addSupplierUseCase: AddSupplierUseCase,
    private val updateSupplierUseCase: UpdateSupplierUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SuppliersUiState())
    val uiState: StateFlow<SuppliersUiState> = _uiState

    init {
        viewModelScope.launch {
            getSuppliersUseCase.getAllSuppliers().collect { list ->
                _uiState.update { it.copy(suppliers = list, isLoading = false) }
            }
        }
    }

    fun openAdd() = _uiState.update { it.copy(showForm = true, editingSupplier = null, form = SupplierFormState()) }

    fun openEdit(supplier: SupplierEntity) = _uiState.update {
        it.copy(
            showForm = true,
            editingSupplier = supplier,
            form = SupplierFormState(
                name = supplier.name,
                contactPerson = supplier.contactPerson,
                email = supplier.email,
                phone = supplier.phone,
                address = supplier.address,
                taxId = supplier.taxId,
                paymentTerms = supplier.paymentTerms,
                notes = supplier.notes
            )
        )
    }

    fun closeForm() = _uiState.update { it.copy(showForm = false, editingSupplier = null) }

    fun onFieldChange(key: String, value: String) = _uiState.update { state ->
        val form = state.form
        state.copy(
            form = when (key) {
                "name" -> form.copy(name = value)
                "contactPerson" -> form.copy(contactPerson = value)
                "email" -> form.copy(email = value)
                "phone" -> form.copy(phone = value)
                "address" -> form.copy(address = value)
                "taxId" -> form.copy(taxId = value)
                "paymentTerms" -> form.copy(paymentTerms = value)
                "notes" -> form.copy(notes = value)
                else -> form
            }
        )
    }

    fun save() {
        val state = _uiState.value
        if (state.form.name.isBlank()) {
            _uiState.update { it.copy(form = it.form.copy(error = "Supplier name is required")) }
            return
        }
        viewModelScope.launch {
            val editing = state.editingSupplier
            try {
                if (editing == null) {
                    addSupplierUseCase(
                        name = state.form.name,
                        contactPerson = state.form.contactPerson,
                        email = state.form.email,
                        phone = state.form.phone,
                        address = state.form.address,
                        taxId = state.form.taxId,
                        paymentTerms = state.form.paymentTerms,
                        notes = state.form.notes
                    )
                } else {
                    updateSupplierUseCase(
                        editing.copy(
                            name = state.form.name,
                            contactPerson = state.form.contactPerson,
                            email = state.form.email,
                            phone = state.form.phone,
                            address = state.form.address,
                            taxId = state.form.taxId,
                            paymentTerms = state.form.paymentTerms,
                            notes = state.form.notes
                        )
                    )
                }
                _uiState.update {
                    it.copy(showForm = false, editingSupplier = null, form = SupplierFormState(), message = if (editing == null) "Supplier added" else "Supplier updated")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteSupplier(supplier: SupplierEntity) {
        viewModelScope.launch {
            try {
                updateSupplierUseCase.deleteSupplier(supplier)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Cannot delete supplier with existing purchases") }
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }
    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}