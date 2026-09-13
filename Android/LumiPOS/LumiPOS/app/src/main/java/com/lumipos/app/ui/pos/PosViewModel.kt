package com.lumipos.app.ui.pos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.print.ReceiptBuilder
import com.lumipos.data.print.ReceiptData
import com.lumipos.data.repository.ShopInfoRepository
import com.lumipos.data.schema.CategoryEntity
import com.lumipos.data.schema.CustomerEntity
import com.lumipos.data.schema.ProductEntity
import com.lumipos.data.schema.SaleItemEntity
import com.lumipos.data.schema.ShopInfoEntity
import com.lumipos.domain.usecase.category.GetCategoriesUseCase
import com.lumipos.domain.usecase.customer.GetCustomersUseCase
import com.lumipos.domain.usecase.product.GetProductsUseCase
import com.lumipos.domain.usecase.sale.CreateSaleUseCase
import com.lumipos.domain.usecase.receipt.GetReceiptCustomizationUseCase
import com.lumipos.domain.usecase.session.ObserveSessionUseCase
import com.lumipos.domain.usecase.session.SetSelectedBranchUseCase
import com.lumipos.domain.usecase.branch.EnsureDefaultBranchUseCase
import com.lumipos.domain.usecase.shift.GetCurrentOpenShiftUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CartItem(
    val product: ProductEntity,
    val quantity: Int = 1
) {
    val subtotal: Double get() = product.price * quantity
}

object PaymentMethod {
    const val CASH = "CASH"
    const val CARD = "CARD"
    const val BANK = "BANK"
    const val OTHER = "OTHER"
}

data class PosUiState(
    val products: List<ProductEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val customers: List<CustomerEntity> = emptyList(),
    val cart: List<CartItem> = emptyList(),
    val searchQuery: String = "",
    val selectedCategoryId: Long? = null,
    val selectedCustomerId: Long? = null,
    val paymentMethod: String = PaymentMethod.CASH,
    val isLoading: Boolean = true,
    val showCart: Boolean = false,
    val showCheckout: Boolean = false,
    val amountPaid: String = "",
    val isPaying: Boolean = false,
    val lastInvoice: String? = null,
    val lastReceipt: ReceiptData? = null,
    val error: String? = null
) {
    val cartTotal: Double get() = cart.sumOf { it.subtotal }
    val cartItemCount: Int get() = cart.sumOf { it.quantity }
    val changeDue: Double get() = (amountPaid.toDoubleOrNull() ?: 0.0) - cartTotal
}

@HiltViewModel
class PosViewModel @Inject constructor(
    private val getProductsUseCase: GetProductsUseCase,
    private val getCategoriesUseCase: GetCategoriesUseCase,
    private val getCustomersUseCase: GetCustomersUseCase,
    private val createSaleUseCase: CreateSaleUseCase,
    private val observeSessionUseCase: ObserveSessionUseCase,
    private val getCurrentOpenShiftUseCase: GetCurrentOpenShiftUseCase,
    private val getReceiptCustomizationUseCase: GetReceiptCustomizationUseCase,
    private val shopInfoRepository: ShopInfoRepository,
    private val ensureDefaultBranchUseCase: EnsureDefaultBranchUseCase,
    private val setSelectedBranchUseCase: SetSelectedBranchUseCase
) : ViewModel() {

    private var branchId: Long = 0L
    private var cashierId: Long? = null

    private val _uiState = MutableStateFlow(PosUiState())
    val uiState: StateFlow<PosUiState> = _uiState

    init {
        viewModelScope.launch {
            var session = observeSessionUseCase().first()
            if (session.branchId == null) {
                val branch = ensureDefaultBranchUseCase()
                setSelectedBranchUseCase(branch)
                session = observeSessionUseCase().first()
            }
            branchId = session.branchId ?: 0L
            cashierId = session.userId
        }
        viewModelScope.launch {
            getCategoriesUseCase.getAllActiveCategories().collect { list ->
                _uiState.update { it.copy(categories = list) }
            }
        }
        viewModelScope.launch {
            getProductsUseCase.getAllActiveProducts().collect { list ->
                _uiState.update { it.copy(products = list, isLoading = false) }
            }
        }
        viewModelScope.launch {
            getCustomersUseCase.getAllActiveCustomers().collect { list ->
                _uiState.update { it.copy(customers = list) }
            }
        }
    }

    fun onSearchChange(q: String) = _uiState.update { it.copy(searchQuery = q) }
    fun onCategoryFilterChange(id: Long?) = _uiState.update { it.copy(selectedCategoryId = id) }
    fun onCustomerSelected(id: Long?) = _uiState.update { it.copy(selectedCustomerId = id) }
    fun onPaymentMethodChange(method: String) = _uiState.update { it.copy(paymentMethod = method) }

    fun showCart() = _uiState.update { it.copy(showCart = true) }
    fun dismissCart() = _uiState.update { it.copy(showCart = false) }
    fun showCheckout() {
        val total = _uiState.value.cartTotal
        _uiState.update {
            it.copy(
                showCart = false,
                showCheckout = true,
                amountPaid = String.format(Locale.US, "%.2f", total)
            )
        }
    }
    fun dismissCheckout() = _uiState.update { it.copy(showCheckout = false, isPaying = false) }

    fun onAmountPaidChange(v: String) = _uiState.update { it.copy(amountPaid = v) }

    fun addToCart(product: ProductEntity) {
        _uiState.update { state ->
            val existing = state.cart.find { it.product.id == product.id }
            if (existing != null) {
                if (existing.quantity < product.stock) {
                    state.copy(
                        cart = state.cart.map {
                            if (it.product.id == product.id) it.copy(quantity = it.quantity + 1) else it
                        }
                    )
                } else state.copy(error = "Not enough stock for ${product.name}")
            } else {
                if (product.stock <= 0) {
                    state.copy(error = "${product.name} is out of stock")
                } else {
                    state.copy(cart = state.cart + CartItem(product, 1))
                }
            }
        }
    }

    fun increaseQuantity(productId: Long) {
        _uiState.update { state ->
            val item = state.cart.find { it.product.id == productId } ?: return@update state
            val maxQty = item.product.stock
            state.copy(
                cart = state.cart.map {
                    if (it.product.id == productId && it.quantity < maxQty) it.copy(quantity = it.quantity + 1) else it
                }
            )
        }
    }

    fun decreaseQuantity(productId: Long) {
        _uiState.update { state ->
            state.copy(
                cart = state.cart.map {
                    if (it.product.id == productId && it.quantity > 1) it.copy(quantity = it.quantity - 1) else it
                }
            )
        }
    }

    fun removeFromCart(productId: Long) {
        _uiState.update { state ->
            state.copy(cart = state.cart.filterNot { it.product.id == productId })
        }
    }

    fun clearCart() = _uiState.update { it.copy(cart = emptyList()) }

    fun pay() {
        val state = _uiState.value
        if (state.cart.isEmpty()) return
        if (branchId <= 0L) {
            _uiState.update { it.copy(error = "No store branch selected. Close the app and reopen to fix this.") }
            return
        }
        val amount = state.amountPaid.toDoubleOrNull()
        if (amount == null || amount < state.cartTotal) {
            _uiState.update { it.copy(error = "Amount must cover the total") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isPaying = true, error = null) }
            try {
                val shift = getCurrentOpenShiftUseCase()
                val items = state.cart.map { item ->
                    SaleItemEntity(
                        saleId = 0,
                        productId = item.product.id,
                        quantity = item.quantity,
                        unitPrice = item.product.price,
                        totalPrice = item.subtotal,
                        costPriceAtSale = item.product.cost
                    )
                }
                val sale = createSaleUseCase(
                    invoiceNumber = "",
                    branchId = branchId,
                    customerId = state.selectedCustomerId,
                    cashierId = cashierId,
                    items = items,
                    tax = 0.0,
                    discount = 0.0,
                    amountPaid = amount,
                    paymentMethod = state.paymentMethod,
                    saleType = "REGULAR",
                    paymentStatus = "PAID",
                    cashShiftId = shift?.id
                )
                val customer = state.customers.find { it.id == state.selectedCustomerId }
                val shop = shopInfoRepository.getShopInfoOnce()
                val customization = getReceiptCustomizationUseCase.getOnce()
                _uiState.update {
                    it.copy(
                        cart = emptyList(),
                        isPaying = false,
                        showCheckout = false,
                        amountPaid = "",
                        selectedCustomerId = null,
                        lastInvoice = sale.invoiceNumber,
                        lastReceipt = buildReceipt(
                            shop = shop,
                            customerName = customer?.name,
                            invoice = sale.invoiceNumber,
                            cashierId = cashierId,
                            paymentMethod = state.paymentMethod,
                            items = state.cart,
                            amountPaid = amount,
                            customization = customization
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isPaying = false, error = e.message) }
            }
        }
    }

    private fun buildReceipt(
        shop: ShopInfoEntity?,
        customerName: String?,
        invoice: String,
        cashierId: Long?,
        paymentMethod: String,
        items: List<CartItem>,
        amountPaid: Double,
        customization: com.lumipos.data.schema.ReceiptCustomizationEntity?
    ): ReceiptData = ReceiptBuilder.build(
        customization = customization,
        shop = shop,
        invoice = invoice,
        dateTime = System.currentTimeMillis(),
        metaLines = listOfNotNull(
            "Cashier" to (cashierId?.toString() ?: "-"),
            "Method" to paymentMethod,
            if (customerName != null) "Customer" to customerName else null
        ),
        items = items.map {
            ReceiptBuilder.LineItem(
                name = it.product.name,
                quantity = it.quantity,
                unitPrice = it.product.price,
                barcode = it.product.barcode
            )
        },
        amountPaid = amountPaid
    )

    fun dismissLastInvoice() = _uiState.update { it.copy(lastInvoice = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }
}