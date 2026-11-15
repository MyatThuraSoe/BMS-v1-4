package com.example.lumipos.ui.purchases

import android.app.Application
import androidx.lifecycle.*
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.Purchase
import com.example.lumipos.data.local.entity.PurchaseItem
import com.example.lumipos.data.local.entity.Supplier
import com.example.lumipos.data.local.entity.Product
import com.example.lumipos.data.local.entity.StockMovement
import com.example.lumipos.data.repository.ProductRepository
import com.example.lumipos.data.repository.PurchaseRepository
import com.example.lumipos.data.repository.StockMovementRepository
import com.example.lumipos.data.repository.SupplierRepository
import com.example.lumipos.util.DateFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PurchasesViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val purchaseRepo = PurchaseRepository(db.purchaseDao(), db.purchaseItemDao())

    val allPurchases: LiveData<List<Purchase>> = purchaseRepo.allPurchases

    fun updatePaymentStatus(purchaseId: Long, status: String) {
        viewModelScope.launch(Dispatchers.IO) {
            purchaseRepo.updatePaymentStatus(purchaseId, status)
        }
    }
}

class PurchaseFormViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val purchaseRepo = PurchaseRepository(db.purchaseDao(), db.purchaseItemDao())
    private val productRepo = ProductRepository(db.productDao())
    private val supplierRepo = SupplierRepository(db.supplierDao())
    private val stockMovementRepo = StockMovementRepository(db.stockMovementDao())

    val suppliers: LiveData<List<Supplier>> = supplierRepo.allSuppliers

    private val _cartItems = MutableLiveData<MutableList<PurchaseCartItem>>(mutableListOf())
    val cartItems: LiveData<MutableList<PurchaseCartItem>> = _cartItems

    private val _subtotal = MutableLiveData(0.0)
    val subtotal: LiveData<Double> = _subtotal

    private val _purchaseSaved = MutableLiveData<Boolean>()
    val purchaseSaved: LiveData<Boolean> = _purchaseSaved

    fun addProduct(product: Product, quantity: Int, unitPrice: Double) {
        val current = _cartItems.value ?: mutableListOf()
        current.add(PurchaseCartItem(
            productId = product.id,
            productName = product.name,
            quantity = quantity,
            unitPrice = unitPrice
        ))
        _cartItems.value = current
        refreshSubtotal()
    }

    fun removeItem(index: Int) {
        val current = _cartItems.value ?: return
        if (index in current.indices) {
            current.removeAt(index)
            _cartItems.value = current
            refreshSubtotal()
        }
    }

    /**
     * Edits the quantity of an existing cart line.
     *
     * Deliberately does NOT repost [_cartItems]: the fragment rebuilds every row on that signal,
     * which would destroy the `et_cart_qty` the user is still typing into (losing focus and cursor)
     * and, because the rebuild writes the model value straight back, could fight the keystrokes.
     * Only the subtotal is republished, so the field is left alone.
     */
    fun updateQuantity(index: Int, quantity: Int) {
        val current = _cartItems.value ?: return
        if (index !in current.indices || quantity <= 0) return
        if (current[index].quantity == quantity) return
        current[index] = current[index].copy(quantity = quantity)
        refreshSubtotal()
    }

    private fun refreshSubtotal() {
        _subtotal.value = _cartItems.value?.sumOf { it.total } ?: 0.0
    }

    fun getSubtotal(): Double {
        return _cartItems.value?.sumOf { it.total } ?: 0.0
    }

    fun savePurchase(supplierId: Long, supplierName: String, paymentStatus: String, taxAmount: Double = 0.0) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = _cartItems.value ?: return@launch
            if (items.isEmpty()) return@launch

            val total = items.sumOf { it.total }
            val purchase = Purchase(
                purchaseNumber = DateFormatter.generatePurchaseNumber(),
                supplierId = supplierId,
                supplierName = supplierName,
                totalAmount = total,
                taxAmount = taxAmount.coerceAtLeast(0.0),
                paymentStatus = paymentStatus
            )
            val purchaseId = purchaseRepo.insert(purchase)

            val purchaseItems = items.map { item ->
                PurchaseItem(
                    purchaseId = purchaseId,
                    productId = item.productId,
                    productName = item.productName,
                    quantity = item.quantity,
                    unitPrice = item.unitPrice,
                    totalPrice = item.total
                )
            }
            purchaseRepo.insertItems(purchaseItems)

            for (item in items) {
                productRepo.updateStock(item.productId, item.quantity)
                val product = productRepo.getById(item.productId)
                stockMovementRepo.insert(StockMovement(
                    productId = item.productId,
                    productName = item.productName,
                    sku = product?.sku ?: "",
                    movementType = "IN",
                    quantity = item.quantity,
                    referenceType = "PURCHASE",
                    referenceId = purchaseId,
                    description = "Purchase ${purchase.purchaseNumber}"
                ))
            }

            withContext(Dispatchers.Main) {
                _cartItems.value = mutableListOf()
                _purchaseSaved.value = true
            }
        }
    }

}

data class PurchaseCartItem(
    val productId: Long,
    val productName: String,
    val quantity: Int,
    val unitPrice: Double
) {
    val total: Double get() = quantity * unitPrice
}
