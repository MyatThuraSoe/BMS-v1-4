package com.example.lumipos.ui.promotions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.Category
import com.example.lumipos.data.local.entity.Product
import com.example.lumipos.data.local.entity.Promotion
import com.example.lumipos.data.repository.CategoryRepository
import com.example.lumipos.data.repository.ProductRepository
import com.example.lumipos.data.repository.PromotionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PromotionsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val repo = PromotionRepository(db.promotionDao())
    private val productRepo = ProductRepository(db.productDao())
    private val categoryRepo = CategoryRepository(db.categoryDao())

    val promotions: LiveData<List<Promotion>> = repo.allPromotions
    val products: LiveData<List<Product>> = productRepo.allProducts
    val categories: LiveData<List<Category>> = categoryRepo.allCategories

    fun save(promotion: Promotion) {
        viewModelScope.launch(Dispatchers.IO) {
            if (promotion.id == 0L) repo.insert(promotion) else repo.update(promotion)
        }
    }

    fun delete(promotion: Promotion) {
        viewModelScope.launch(Dispatchers.IO) { repo.delete(promotion) }
    }
}
