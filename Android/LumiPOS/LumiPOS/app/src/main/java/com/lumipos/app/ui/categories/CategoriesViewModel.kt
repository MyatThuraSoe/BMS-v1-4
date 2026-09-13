package com.lumipos.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.schema.CategoryEntity
import com.lumipos.domain.usecase.category.AddCategoryUseCase
import com.lumipos.domain.usecase.category.GetCategoriesUseCase
import com.lumipos.domain.usecase.category.UpdateCategoryUseCase
import com.lumipos.domain.usecase.session.ObserveSessionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryFormState(
    val name: String = "",
    val description: String = "",
    val formError: String? = null
)

data class CategoriesUiState(
    val categories: List<CategoryEntity> = emptyList(),
    val isLoading: Boolean = true,
    val showForm: Boolean = false,
    val editingCategory: CategoryEntity? = null,
    val form: CategoryFormState = CategoryFormState(),
    val error: String? = null
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val getCategoriesUseCase: GetCategoriesUseCase,
    private val addCategoryUseCase: AddCategoryUseCase,
    private val updateCategoryUseCase: UpdateCategoryUseCase,
    private val observeSessionUseCase: ObserveSessionUseCase
) : ViewModel() {

    private var branchId: Long = 0L
    private val _uiState = MutableStateFlow(CategoriesUiState())
    val uiState: StateFlow<CategoriesUiState> = _uiState

    init {
        viewModelScope.launch {
            observeSessionUseCase().first().branchId?.let { branchId = it }
        }
        viewModelScope.launch {
            getCategoriesUseCase.getAllActiveCategories().collect { list ->
                _uiState.update { it.copy(categories = list, isLoading = false) }
            }
        }
    }

    fun openAdd() {
        _uiState.update {
            it.copy(showForm = true, editingCategory = null, form = CategoryFormState(), error = null)
        }
    }

    fun openEdit(category: CategoryEntity) {
        _uiState.update {
            it.copy(
                showForm = true,
                editingCategory = category,
                form = CategoryFormState(name = category.name, description = category.description),
                error = null
            )
        }
    }

    fun closeForm() = _uiState.update { it.copy(showForm = false) }
    fun onNameChange(v: String) = _uiState.update { it.copy(form = it.form.copy(name = v, formError = null)) }
    fun onDescriptionChange(v: String) = _uiState.update { it.copy(form = it.form.copy(description = v)) }

    fun saveCategory() {
        val f = _uiState.value.form
        if (f.name.isBlank()) {
            _uiState.update { it.copy(form = it.form.copy(formError = "Name is required")) }
            return
        }
        viewModelScope.launch {
            try {
                val editing = _uiState.value.editingCategory
                if (editing == null) {
                    addCategoryUseCase(f.name.trim(), f.description.trim(), branchId)
                } else {
                    updateCategoryUseCase(editing.copy(name = f.name.trim(), description = f.description.trim()))
                }
                closeForm()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteCategory(category: CategoryEntity) {
        viewModelScope.launch {
            try {
                updateCategoryUseCase.deleteCategory(category)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
}