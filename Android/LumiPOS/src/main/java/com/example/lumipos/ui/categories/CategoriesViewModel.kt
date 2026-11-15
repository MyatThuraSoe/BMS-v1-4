package com.example.lumipos.ui.categories

import android.app.Application
import androidx.lifecycle.*
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.Category
import com.example.lumipos.data.repository.CategoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CategoriesViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val categoryRepo = CategoryRepository(db.categoryDao())

    val allCategories: LiveData<List<Category>> = categoryRepo.allCategories
    val count: LiveData<Int> = categoryRepo.count

    fun deleteCategory(category: Category) {
        viewModelScope.launch(Dispatchers.IO) {
            categoryRepo.delete(category)
        }
    }
}

/**
 * Add/edit form for a single category.
 *
 * Split out from [CategoriesViewModel] to match the other form screens, and because the form needs
 * a load step the list screen does not. The old code had none: it built a brand-new `Category` from
 * whatever was typed, so opening "Edit" showed an empty form, saving blanked the row's fields, and
 * `createdAt` - which defaults to `System.currentTimeMillis()` - was silently reset on every edit.
 */
class CategoryFormViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val categoryRepo = CategoryRepository(db.categoryDao())

    private val _category = MutableLiveData<Category?>()
    val category: LiveData<Category?> = _category

    private val _saveSuccess = MutableLiveData<Boolean>()
    val saveSuccess: LiveData<Boolean> = _saveSuccess

    fun loadCategory(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _category.postValue(categoryRepo.getById(id))
        }
    }

    fun saveCategory(category: Category) {
        viewModelScope.launch(Dispatchers.IO) {
            if (category.id == 0L) {
                categoryRepo.insert(category)
            } else {
                categoryRepo.update(category)
            }
            withContext(Dispatchers.Main) {
                _saveSuccess.value = true
            }
        }
    }
}
