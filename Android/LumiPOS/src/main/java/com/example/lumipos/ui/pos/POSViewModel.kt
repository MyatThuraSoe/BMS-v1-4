package com.example.lumipos.ui.pos

import android.app.Application
import androidx.lifecycle.*
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.*
import com.example.lumipos.data.repository.*
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter
import com.example.lumipos.util.PromotionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CartItem(
    val productId: Long,
    val name: String,
    val sku: String = "",
    val price: Double,
    /**
     * The product's cost at the moment it was added to the cart. Carried so the sale line can
     * record it: `SaleItem.costPrice` is the only historical cost basis, and leaving it at its
     * 0.0 default would make every gross-profit figure equal revenue.
     */
    val costPrice: Double = 0.0,
    var quantity: Int,
    val stockQuantity: Int,
    val imageBytes: ByteArray? = null,
    /** Used to match CATEGORY-scoped promotions. */
    val categoryId: Long = 0,
    /** Cashier-entered line discount, in currency units (never more than the line gross). */
    var manualDiscount: Double = 0.0,
    /** Composite/kit product: stock is deducted from its components rather than this row. */
    val isBundle: Boolean = false
) {
    val total: Double get() = price * quantity
    val netTotal: Double get() = (total - manualDiscount).coerceAtLeast(0.0)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CartItem) return false
        return productId == other.productId && quantity == other.quantity
    }
    override fun hashCode() = productId.hashCode() * 31 + quantity
}

data class HeldCartSnapshot(
    val items: List<CartItem>,
    val customer: Customer?,
    val heldAt: Long
)

class POSViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val productRepo = ProductRepository(db.productDao())
    private val categoryRepo = CategoryRepository(db.categoryDao())
    private val customerRepo = CustomerRepository(db.customerDao())
    private val saleRepo = SaleRepository(db.saleDao(), db.saleItemDao())
    private val shiftRepo = ShiftRepository(db.shiftDao())
    private val orderRepo = OrderRepository(db.orderDao(), db.orderItemDao())
    private val stockMovementRepo = StockMovementRepository(db.stockMovementDao())
    private val shopInfoRepo = ShopInfoRepository(db.shopInfoDao(), db.receiptCustomizationDao(), db.settingDao())
    private val auditLogRepo = AuditLogRepository(db.auditLogDao())
    private val heldCartRepo = HeldCartRepository(db.heldCartDao())
    private val promotionRepo = PromotionRepository(db.promotionDao())
    private val productBundleRepo = ProductBundleRepository(db.productBundleDao())
    private val loyaltyRepo = LoyaltyRepository(db.customerDao(), db.loyaltyTransactionDao(), db.settingDao())

    private val _cartItems = MutableLiveData<MutableList<CartItem>>(mutableListOf())
    val cartItems: LiveData<MutableList<CartItem>> = _cartItems

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: LiveData<String> = _searchQuery.asLiveData()

    private val _selectedCategoryId = MutableStateFlow<Long?>(null)
    val selectedCategoryId: LiveData<Long?> = _selectedCategoryId.asLiveData()

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedProducts: Flow<PagingData<Product>> =
        combine(_searchQuery, _selectedCategoryId) { query, categoryId ->
            query.trim() to categoryId?.takeIf { it != 0L }
        }.flatMapLatest { (query, categoryId) ->
            Pager(
                PagingConfig(
                    pageSize = PAGE_SIZE,
                    initialLoadSize = PAGE_SIZE * 2,
                    enablePlaceholders = false
                )
            ) {
                db.productDao().getForPosPaged(query, categoryId)
            }.flow
        }.cachedIn(viewModelScope)

    val categories: LiveData<List<Category>> = categoryRepo.allCategories
    val customers: LiveData<List<Customer>> = customerRepo.allCustomers
    val currentShift: LiveData<Shift?> = shiftRepo.currentShift
    val shopInfo: LiveData<ShopInfo?> = shopInfoRepo.shopInfo

    private val _selectedCustomer = MutableLiveData<Customer?>()
    val selectedCustomer: LiveData<Customer?> = _selectedCustomer

    /**
     * Unpaid credit balance for the selected customer, or 0.0 when nobody is selected.
     *
     * Customer.creditLimit was previously written by the customer form and never read, so a
     * limit could be set and then cheerfully ignored forever. The checkout dialog needs the
     * figure *before* the sale is committed, which is why it is published as LiveData.
     */
    private val _customerOutstanding = MutableLiveData(0.0)
    val customerOutstanding: LiveData<Double> = _customerOutstanding

    private val _saleComplete = MutableLiveData<Sale?>()
    val saleComplete: LiveData<Sale?> = _saleComplete

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _orderSaved = MutableLiveData<Boolean>()
    val orderSaved: LiveData<Boolean> = _orderSaved

    private val _heldCount = MutableLiveData(0)
    val heldCount: LiveData<Int> = _heldCount

    val heldCarts: LiveData<List<HeldCart>> = heldCartRepo.getAll()

    private val _quickKeys = MutableLiveData<List<Product>>(emptyList())
    val quickKeys: LiveData<List<Product>> = _quickKeys

    /** Automatic promo discount recomputed from the cart and the active promotions. */
    private val _promotionDiscount = MutableLiveData(0.0)
    val promotionDiscount: LiveData<Double> = _promotionDiscount

    private val _promotionNames = MutableLiveData<List<String>>(emptyList())
    val promotionNames: LiveData<List<String>> = _promotionNames

    /** Cashier-entered bill-level discount, in currency units. */
    private val _billDiscount = MutableLiveData(0.0)
    val billDiscount: LiveData<Double> = _billDiscount

    /** Store credit the customer is choosing to spend on this sale (a tender, not a discount). */
    private val _storeCreditApplied = MutableLiveData(0.0)
    val storeCreditApplied: LiveData<Double> = _storeCreditApplied

    private var activePromotions: List<Promotion> = emptyList()

    init {
        loadQuickKeys()
        loadPromotions()
        viewModelScope.launch(Dispatchers.IO) {
            _heldCount.postValue(heldCartRepo.getAllSync().size)
        }
    }

    private fun loadPromotions() {
        viewModelScope.launch(Dispatchers.IO) {
            val promos = promotionRepo.getActive()
            withContext(Dispatchers.Main) {
                activePromotions = promos
                recomputePromotions()
            }
        }
    }

    fun refreshPromotions() = loadPromotions()

    /** Sum of cashier-entered line discounts across the cart. */
    fun lineDiscountTotal(): Double = _cartItems.value?.sumOf { it.manualDiscount } ?: 0.0

    /** Re-runs the promotion engine over the current cart. Call after any cart mutation. */
    fun recomputePromotions() {
        val cart = _cartItems.value ?: return
        val lines = cart.map {
            PromotionEngine.Line(it.productId, it.categoryId, it.price, it.quantity)
        }
        val result = PromotionEngine.evaluate(lines, activePromotions)
        _promotionDiscount.value = result.total
        _promotionNames.value = result.appliedNames.distinct()
    }

    fun setLineDiscount(productId: Long, amount: Double) {
        val current = _cartItems.value ?: return
        val item = current.find { it.productId == productId } ?: return
        item.manualDiscount = amount.coerceIn(0.0, item.total)
        recomputePromotions()
        _cartItems.value = current
    }

    /** @param percent when true [value] is treated as a percentage of the gross subtotal. */
    fun applyBillDiscount(value: Double, percent: Boolean) {
        val gross = _cartItems.value?.sumOf { it.total } ?: 0.0
        val amount = if (percent) gross * (value / 100.0) else value
        _billDiscount.value = amount.coerceIn(0.0, gross)
    }

    fun clearBillDiscount() {
        _billDiscount.value = 0.0
    }

    fun searchProducts(query: String) {
        _searchQuery.value = query
    }

    fun filterByCategory(categoryId: Long?) {
        _selectedCategoryId.value = categoryId
    }

    fun refreshProducts() {
        loadQuickKeys()
    }

    private fun loadQuickKeys() {
        viewModelScope.launch(Dispatchers.IO) {
            val top = saleRepo.getTopSoldProductIds()
            val ids = top.map { it.productId }
            val products = productRepo.getByIdList(ids).associateBy { it.id }
            val ordered = ids.mapNotNull { pid -> products[pid] }
            if (ordered.isEmpty()) {
                // fallback to active products
                val all = productRepo.getAllActiveForPos().take(8)
                _quickKeys.postValue(all)
            } else {
                _quickKeys.postValue(ordered.take(8))
            }
        }
    }

    fun addToCart(product: Product) {
        val current = _cartItems.value ?: mutableListOf()
        val existing = current.find { it.productId == product.id }
        // A bundle's own stock is virtual (usually 0); its real availability is its components,
        // which is enforced at sale time. So the own-stock guard is skipped for bundles.
        if (existing != null) {
            if (product.isBundle || existing.quantity < product.stockQuantity) {
                existing.quantity++
            } else {
                _error.value = "Insufficient stock for ${product.name}"
                return
            }
        } else {
            if (!product.isBundle && product.stockQuantity <= 0) {
                _error.value = "${product.name} is out of stock"
                return
            }
            current.add(CartItem(
                productId = product.id,
                name = product.name,
                sku = product.sku,
                price = product.priceForTier(_selectedCustomer.value?.tier),
                costPrice = product.costPrice,
                quantity = 1,
                stockQuantity = product.stockQuantity,
                imageBytes = product.imageBytes,
                categoryId = product.categoryId,
                isBundle = product.isBundle
            ))
        }
        _cartItems.value = current
        recomputePromotions()
    }

    fun addToCartByBarcode(barcode: String) {
        val code = barcode.trim()
        if (code.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val product = productRepo.getBySku(code)
                ?: productRepo.searchList(code).firstOrNull()
            withContext(Dispatchers.Main) {
                if (product == null) {
                    _error.value = getApplication<Application>().getString(
                        R.string.msg_barcode_not_found,
                        code
                    )
                } else {
                    addToCart(product)
                }
            }
        }
    }

    fun applyWeightToCart(weightKg: Double) {
        val last = _cartItems.value?.lastOrNull()
        if (last == null) {
            _error.value = getApplication<Application>().getString(R.string.msg_scale_no_item)
            return
        }
        val quantity = maxOf(1, Math.round(weightKg).toInt())
        updateCartQuantity(last.productId, quantity)
    }

    fun holdCart() {
        val items = _cartItems.value ?: return
        if (items.isEmpty()) {
            _error.value = getApplication<Application>().getString(R.string.msg_cart_empty)
            return
        }
        val customer = _selectedCustomer.value
        val serialized = HeldCartSerializer.serialize(items.map {
            HeldCartItem(
                productId = it.productId,
                name = it.name,
                sku = it.sku,
                price = it.price,
                costPrice = it.costPrice,
                quantity = it.quantity,
                stockQuantity = it.stockQuantity,
                categoryId = it.categoryId
            )
        })
        viewModelScope.launch(Dispatchers.IO) {
            heldCartRepo.insert(
                HeldCart(
                    customerId = customer?.id,
                    customerName = customer?.fullName.orEmpty(),
                    heldAt = System.currentTimeMillis(),
                    cartJson = serialized
                )
            )
            _heldCount.postValue(heldCartRepo.getAllSync().size)
        }
        clearCart()
    }

    fun recallCart() {
        viewModelScope.launch(Dispatchers.IO) {
            val held = heldCartRepo.getAllSync().firstOrNull()
            if (held == null) {
                _error.postValue(getApplication<Application>().getString(R.string.msg_no_held_carts))
                return@launch
            }
            recallCart(held)
        }
    }

    fun recallCart(held: HeldCart) {
        val restored = HeldCartSerializer.deserialize(held.cartJson).map {
            CartItem(
                productId = it.productId,
                name = it.name,
                sku = it.sku,
                price = it.price,
                costPrice = it.costPrice,
                quantity = it.quantity,
                stockQuantity = it.stockQuantity,
                categoryId = it.categoryId
            )
        }
        _cartItems.postValue(restored.toMutableList())
        recomputePromotions()
        if (held.customerId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                customerRepo.getById(held.customerId)?.let { _selectedCustomer.postValue(it) }
                heldCartRepo.deleteById(held.id)
                _heldCount.postValue(heldCartRepo.getAllSync().size)
            }
        } else {
            _selectedCustomer.postValue(null)
            viewModelScope.launch(Dispatchers.IO) {
                heldCartRepo.deleteById(held.id)
                _heldCount.postValue(heldCartRepo.getAllSync().size)
            }
        }
    }

    fun updateCartQuantity(productId: Long, quantity: Int) {
        val current = _cartItems.value ?: return
        val item = current.find { it.productId == productId } ?: return
        if (quantity <= 0) {
            current.remove(item)
        } else {
            item.quantity = minOf(quantity, item.stockQuantity)
        }
        // A changed quantity invalidates any line discount that exceeded the new gross.
        if (item.manualDiscount > item.total) item.manualDiscount = item.total
        _cartItems.value = current
        recomputePromotions()
    }

    fun removeFromCart(productId: Long) {
        val current = _cartItems.value ?: return
        current.removeAll { it.productId == productId }
        _cartItems.value = current
        recomputePromotions()
    }

    fun clearCart() {
        _cartItems.value = mutableListOf()
        _selectedCustomer.value = null
        _customerOutstanding.value = 0.0
        _storeCreditApplied.value = 0.0
        recomputePromotions()
    }

    fun selectCustomer(customer: Customer?) {
        _selectedCustomer.value = customer
        // A different (or no) customer invalidates any credit the previous one had applied.
        _storeCreditApplied.value = 0.0
        // A different tier means a different price list for every line already in the cart.
        repriceCart(customer?.tier)
        if (customer == null) {
            _customerOutstanding.value = 0.0
            return
        }
        // Loaded eagerly rather than at checkout so the dialog can show the customer's
        // remaining credit immediately, without a query on the click that opens it.
        viewModelScope.launch(Dispatchers.IO) {
            val outstanding = saleRepo.getOutstandingByCustomer(customer.id)
            withContext(Dispatchers.Main) { _customerOutstanding.value = outstanding }
        }
    }

    /**
     * Re-prices every cart line from the products' price list for [tier]. Called when the selected
     * customer changes; a null tier (walk-in) falls back to the retail price.
     */
    private fun repriceCart(tier: String?) {
        val lines = _cartItems.value ?: return
        if (lines.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val byId = productRepo.getByIdList(lines.map { it.productId }).associateBy { it.id }
            withContext(Dispatchers.Main) {
                val current = _cartItems.value ?: return@withContext
                val repriced = current.map { line ->
                    val newPrice = byId[line.productId]?.priceForTier(tier) ?: line.price
                    if (newPrice == line.price) line else line.copy(price = newPrice)
                }.toMutableList()
                _cartItems.value = repriced
                recomputePromotions()
            }
        }
    }

    fun getSubtotal(): Double {
        return _cartItems.value?.sumOf { it.total } ?: 0.0
    }

    /** Applies store credit toward the current sale, capped by the customer's wallet balance. */
    fun applyStoreCredit(amount: Double) {
        val customer = _selectedCustomer.value ?: return
        _storeCreditApplied.value = amount.coerceIn(0.0, customer.storeCredit)
    }

    fun clearStoreCredit() {
        _storeCreditApplied.value = 0.0
    }

    fun clearError() {
        _error.value = null
    }

    fun completeSale(
        paymentMethod: String,
        amountPaid: Double,
        saleType: String = "CASH",
        dueDate: Long? = null,
        discountAmount: Double = 0.0,
        splitPayments: List<Pair<String, Double>> = emptyList()
    ) {
        val items = _cartItems.value ?: return
        if (items.isEmpty()) {
            _error.value = "Cart is empty"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val subtotal = items.sumOf { it.total }
                val shopInfo = shopInfoRepo.getShopInfoSync()
                val taxPercentage = shopInfo?.taxPercentage ?: 0.0
                val taxAmount = subtotal * taxPercentage / 100.0

                // Three discount sources stack: cashier line discounts, automatic promotions and
                // the bill-level discount passed in from the checkout dialog. All are recorded in
                // Sale.discountAmount and printed on the receipt.
                val lineDiscountTotal = items.sumOf { it.manualDiscount }
                val promoLines = items.map { PromotionEngine.Line(it.productId, it.categoryId, it.price, it.quantity) }
                val promoResult = PromotionEngine.evaluate(promoLines, activePromotions)
                val promoDiscount = promoResult.total
                val billDiscount = discountAmount.coerceAtLeast(0.0)
                val totalDiscount = (lineDiscountTotal + promoDiscount + billDiscount)
                    .coerceAtMost(subtotal)
                val totalAmount = (subtotal + taxAmount - totalDiscount).coerceAtLeast(0.0)

                // A split tender is paid by several methods at once (e.g. 50 cash + 50 card).
                // The per-method rows are recorded separately; the Sale itself carries the sum.
                // Store credit is an additional tender on top, capped at what is still owed.
                val customer = _selectedCustomer.value
                val splitTotal = splitPayments.sumOf { it.second }
                val tendered = if (splitPayments.isNotEmpty()) splitTotal else amountPaid
                val creditToUse = if (customer != null) {
                    (_storeCreditApplied.value ?: 0.0).coerceIn(0.0, minOf(customer.storeCredit, totalAmount))
                } else {
                    0.0
                }
                val effectivePaid = tendered + creditToUse
                val effectiveMethod = when {
                    creditToUse > 0.0 && tendered <= 0.0 -> "STORE_CREDIT"
                    creditToUse > 0.0 && splitPayments.isNotEmpty() -> "SPLIT"
                    creditToUse > 0.0 -> "$paymentMethod+CREDIT"
                    splitPayments.isNotEmpty() -> "SPLIT"
                    else -> paymentMethod
                }

                val invoiceNumber = DateFormatter.generateInvoiceNumber()
                val shift = shiftRepo.getCurrentShiftSync()

                // Credit limit is enforced here and not only in the checkout dialog, so that
                // every path into completeSale is covered rather than just the POS screen.
                // A null limit means "no limit"; 0.0 would otherwise block every credit sale.
                val creditLimit = customer?.creditLimit
                if (saleType == "CREDIT" && customer != null && creditLimit != null && creditLimit > 0.0) {
                    val owed = saleRepo.getOutstandingByCustomer(customer.id)
                    val projected = owed + (totalAmount - effectivePaid)
                    if (projected > creditLimit) {
                        withContext(Dispatchers.Main) {
                            _error.value = getApplication<Application>().getString(
                                R.string.msg_credit_limit_exceeded,
                                CurrencyFormatter.format(creditLimit),
                                CurrencyFormatter.format(owed),
                                CurrencyFormatter.format(projected)
                            )
                        }
                        return@launch
                    }
                }

                val sale = Sale(
                    invoiceNumber = invoiceNumber,
                    customerId = customer?.id,
                    customerName = customer?.fullName ?: "Walk-in",
                    cashierName = "Cashier",
                    paymentMethod = effectiveMethod,
                    saleType = saleType,
                    // Credit sales are only "PAID" once the money is actually in. The AR screen
                    // selects on paymentStatus != "PAID" && saleType == "CREDIT", so a credit
                    // sale must never be written as PAID here.
                    paymentStatus = when {
                        saleType != "CREDIT" -> "PAID"
                        effectivePaid >= totalAmount -> "PAID"
                        effectivePaid > 0.0 -> "PARTIAL"
                        else -> "UNPAID"
                    },
                    dueDate = dueDate,
                    subtotal = subtotal,
                    taxAmount = taxAmount,
                    discountAmount = totalDiscount,
                    totalAmount = totalAmount,
                    amountPaid = effectivePaid,
                    shiftId = shift?.id
                )
                val saleId = saleRepo.insert(sale)

                if (splitPayments.isNotEmpty()) {
                    db.salePaymentDao().insertAll(
                        splitPayments.map { (method, amount) ->
                            SalePayment(saleId = saleId, method = method, amount = amount)
                        }
                    )
                }

                val saleItems = items.map { cartItem ->
                    SaleItem(
                        saleId = saleId,
                        productId = cartItem.productId,
                        productName = cartItem.name,
                        sku = cartItem.sku,
                        quantity = cartItem.quantity,
                        unitPrice = cartItem.price,
                        // Recorded at sale time. This column was previously left at its 0.0
                        // default, so no sale ever carried a cost basis and any gross-profit
                        // calculation would have come out equal to revenue.
                        costPrice = cartItem.costPrice,
                        totalPrice = cartItem.total,
                        discountAmount = cartItem.manualDiscount + (promoResult.perProduct[cartItem.productId] ?: 0.0)
                    )
                }
                saleRepo.insertItems(saleItems)

                for (cartItem in items) {
                    if (cartItem.isBundle) {
                        // Composite product: deduct each component, not the bundle row itself.
                        val components = productBundleRepo.getByBundle(cartItem.productId)
                        for (component in components) {
                            val deduct = component.quantity * cartItem.quantity
                            productRepo.updateStock(component.componentProductId, -deduct)
                            val componentProduct = productRepo.getById(component.componentProductId)
                            stockMovementRepo.insert(StockMovement(
                                productId = component.componentProductId,
                                productName = componentProduct?.name ?: component.componentName,
                                sku = componentProduct?.sku ?: "",
                                movementType = "OUT",
                                quantity = deduct,
                                referenceType = "SALE",
                                referenceId = saleId,
                                createdByName = "Cashier",
                                description = "Bundle ${cartItem.name} - Sale $invoiceNumber"
                            ))
                        }
                    } else {
                        productRepo.updateStock(cartItem.productId, -cartItem.quantity)
                        stockMovementRepo.insert(StockMovement(
                            productId = cartItem.productId,
                            productName = cartItem.name,
                            sku = cartItem.sku,
                            movementType = "OUT",
                            quantity = cartItem.quantity,
                            referenceType = "SALE",
                            referenceId = saleId,
                            createdByName = "Cashier",
                            description = "Sale $invoiceNumber"
                        ))
                    }
                }

                auditLogRepo.insert(AuditLog(
                    username = "Cashier",
                    action = "SALE_CREATED",
                    entityType = "SALE",
                    entityId = saleId,
                    description = "Sale $invoiceNumber - ${CurrencyFormatter.format(totalAmount)}"
                ))

                // Loyalty ledger: spend the wallet credit first, then award points on whatever
                // the customer paid with "new money" so a pure-credit sale does not mint points.
                if (customer != null) {
                    if (creditToUse > 0.0) {
                        loyaltyRepo.useStoreCredit(customer.id, saleId, creditToUse)
                    }
                    val pointsBasis = (totalAmount - creditToUse).coerceAtLeast(0.0)
                    loyaltyRepo.earnPoints(customer.id, saleId, pointsBasis)
                }

                val completedSale = saleRepo.getById(saleId)

                // LAN sync: when this tablet is a client, push the sale to the master terminal.
                // Failures are parked in the outbox and retried, so the sale is never blocked.
                runCatching { com.example.lumipos.sync.SyncManager.onSaleCompleted(db, saleId) }

                withContext(Dispatchers.Main) {
                    _saleComplete.value = completedSale
                    _cartItems.value = mutableListOf()
                    _selectedCustomer.value = null
                    _customerOutstanding.value = 0.0
                    _billDiscount.value = 0.0
                    _storeCreditApplied.value = 0.0
                    recomputePromotions()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Error completing sale: ${e.message}"
                }
            }
        }
    }

    /**
     * Parks the current cart as a PENDING order so it can be converted from the Orders screen.
     *
     * Stock is deliberately NOT touched here. Inventory moves when the order becomes a sale
     * (`OrdersViewModel.convertOrder`), exactly as it does for a direct sale. Decrementing here
     * as well double-counted every conversion, and `cancelOrder` has no way to put the stock
     * back, so a cancelled order would have leaked inventory permanently.
     */
    fun saveOrder(notes: String = "") {
        val items = _cartItems.value ?: return
        if (items.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val subtotal = items.sumOf { it.total }
            val orderNumber = DateFormatter.generateOrderNumber()
            val customer = _selectedCustomer.value

            val order = Order(
                orderNumber = orderNumber,
                customerId = customer?.id,
                customerName = customer?.fullName ?: "Walk-in",
                cashierName = "Cashier",
                status = "PENDING",
                itemCount = items.sumOf { it.quantity },
                totalAmount = subtotal,
                subtotal = subtotal,
                notes = notes
            )
            val orderId = orderRepo.insert(order)

            val orderItems = items.map { cartItem ->
                OrderItem(
                    orderId = orderId,
                    productId = cartItem.productId,
                    productName = cartItem.name,
                    sku = cartItem.sku,
                    quantity = cartItem.quantity,
                    unitPrice = cartItem.price,
                    totalPrice = cartItem.total
                )
            }
            orderRepo.insertItems(orderItems)

            withContext(Dispatchers.Main) {
                _cartItems.value = mutableListOf()
                _selectedCustomer.value = null
                _customerOutstanding.value = 0.0
                _orderSaved.value = true
            }
        }
    }

    fun clearOrderSaved() {
        _orderSaved.value = false
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
