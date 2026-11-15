package com.example.lumipos.ui.pos

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.CartAdapter
import com.example.lumipos.adapter.ProductPosAdapter
import com.example.lumipos.data.local.entity.Category
import com.example.lumipos.data.local.entity.Product
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.ui.pos.BarcodeScannerFragment
import com.example.lumipos.util.BluetoothPrinter
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.CustomerDisplayBus
import com.example.lumipos.util.DateFormatter
import com.example.lumipos.util.Extensions.applyStatusTint
import com.example.lumipos.util.KeyShortcutHandler
import com.example.lumipos.util.WeighingScale
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

class POSFragment : Fragment(), KeyShortcutHandler {

    private val viewModel: POSViewModel by viewModels()
    private lateinit var productAdapter: ProductPosAdapter
    private lateinit var cartAdapter: CartAdapter
    private lateinit var rvProducts: RecyclerView
    private lateinit var rvQuickKeys: RecyclerView
    private lateinit var rvCart: RecyclerView
    private lateinit var categoryChipsContainer: LinearLayout
    private lateinit var tvSubtotal: TextView
    private lateinit var tvTax: TextView
    private lateinit var tvTotal: TextView
    private lateinit var etSearch: EditText
    private lateinit var etDiscount: EditText
    private lateinit var tvCustomer: TextView

    /** Cart-level discount in currency units, parsed from et_discount. */
    private var discountAmount = 0.0

    /** The discount value a manager PIN has already approved at checkout (-1 = none). */
    private var approvedBillDiscount = -1.0

    /** Set by setupCategoryChips so the selectedCategoryId observer can repaint the chips. */
    private var highlightCategoryChip: ((Long?) -> Unit)? = null

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_pos, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvProducts = view.findViewById(R.id.rv_products)
        rvProducts.layoutManager = GridLayoutManager(requireContext(), 2)
        productAdapter = ProductPosAdapter { product ->
            addProductToCart(product)
        }
        rvProducts.adapter = productAdapter

        rvQuickKeys = view.findViewById(R.id.rv_quick_keys)
        rvQuickKeys.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvQuickKeys.adapter = com.example.lumipos.adapter.QuickKeyAdapter(emptyList()) { product ->
            addProductToCart(product)
        }

        rvCart = view.findViewById(R.id.rv_cart)
        rvCart.layoutManager = LinearLayoutManager(requireContext())
        cartAdapter = CartAdapter(
            requireContext(),
            onQuantityChanged = { productId, qty -> viewModel.updateCartQuantity(productId, qty) },
            onRemove = { productId -> viewModel.removeFromCart(productId) },
            onLineDiscount = { item -> showLineDiscountDialog(item) }
        )
        rvCart.adapter = cartAdapter

        categoryChipsContainer = view.findViewById(R.id.category_chips)
        tvSubtotal = view.findViewById(R.id.tv_cart_subtotal)
        tvTax = view.findViewById(R.id.tv_cart_tax)
        tvTotal = view.findViewById(R.id.tv_cart_total)
        etSearch = view.findViewById(R.id.et_search)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.searchProducts(s?.toString() ?: "")
            }
        })

        view.findViewById<MaterialButton>(R.id.btn_checkout).setOnClickListener { showCheckoutDialog() }

        // Abandon a part-built cart in one tap. POSViewModel.clearCart existed but had no caller,
        // so the only way out was removing items one at a time.
        view.findViewById<MaterialButton>(R.id.btn_recall_cart).setOnClickListener {
            showRecallDialog()
        }

        view.findViewById<MaterialButton>(R.id.btn_clear_cart).setOnClickListener {
            if (viewModel.cartItems.value.isNullOrEmpty()) {
                toast(getString(R.string.msg_cart_empty))
            } else {
                AlertDialog.Builder(requireContext())
                    .setMessage(R.string.msg_clear_cart_confirm)
                    .setPositiveButton(R.string.btn_clear) { _, _ ->
                        viewModel.clearCart()
                        etDiscount.setText("")
                        discountAmount = 0.0
                        updateTotals()
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
        }

        // Parks the cart as a PENDING order for the Orders screen. The observer for
        // viewModel.orderSaved already existed but nothing could ever set it, so the Orders
        // screen had no way of being populated at all.
        view.findViewById<MaterialButton>(R.id.btn_save_order).setOnClickListener {
            if (viewModel.cartItems.value.isNullOrEmpty()) {
                toast(getString(R.string.msg_order_needs_items))
            } else {
                viewModel.saveOrder()
            }
        }

        // Cart-level discount. This field existed in the layout but was never read, so the
        // discount stayed 0.00 no matter what the cashier typed.
        etDiscount = view.findViewById(R.id.et_discount)
        etDiscount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                discountAmount = s?.toString()?.toDoubleOrNull() ?: 0.0
                updateTotals()
            }
        })

        // Customer selection â€” required for a credit sale, and previously impossible: the
        // fragment declared a selectedCustomer field that nothing ever assigned.
        tvCustomer = view.findViewById(R.id.tv_customer)
        view.findViewById<View>(R.id.customer_row).setOnClickListener { showCustomerPicker() }

        val btnScan = view.findViewById<ImageButton>(R.id.btn_scan_barcode)
        val btnScale = view.findViewById<ImageButton>(R.id.btn_scale)
        btnScan.setOnClickListener { openBarcodeScanner() }
        btnScale.setOnClickListener { connectScale() }

        // Two separate LiveData feed this one line of text (the customer, and their outstanding
        // balance, which arrives later from a query), so both observers re-render from current
        // values rather than each writing a partial label.
        fun renderCustomer() {
            val customer = viewModel.selectedCustomer.value
            if (customer == null) {
                tvCustomer.text = getString(R.string.walk_in_customer)
                return
            }
            val name = customer.fullName.takeIf { it.isNotBlank() }
                ?: getString(R.string.walk_in_customer)
            val limit = customer.creditLimit
            // Shown here as well as in the checkout dialog, so the cashier does not have to
            // open checkout to discover the customer is already at their limit.
            tvCustomer.text = if (limit == null || limit <= 0.0) {
                name
            } else {
                val owed = viewModel.customerOutstanding.value ?: 0.0
                getString(
                    R.string.format_customer_credit,
                    name,
                    CurrencyFormatter.format(owed),
                    CurrencyFormatter.format(limit)
                )
            }
        }

        viewModel.selectedCustomer.observe(viewLifecycleOwner) { renderCustomer() }
        viewModel.customerOutstanding.observe(viewLifecycleOwner) { renderCustomer() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pagedProducts.collectLatest { paged ->
                productAdapter.submitData(paged)
            }
        }

        viewModel.quickKeys.observe(viewLifecycleOwner) { products ->
            rvQuickKeys.adapter = com.example.lumipos.adapter.QuickKeyAdapter(products) { product ->
                addProductToCart(product)
            }
        }

        viewModel.categories.observe(viewLifecycleOwner) { categories ->
            setupCategoryChips(categories)
        }

        viewModel.selectedCategoryId.observe(viewLifecycleOwner) { selectedId ->
            highlightCategoryChip?.invoke(selectedId)
        }

        viewModel.cartItems.observe(viewLifecycleOwner) { items ->
            cartAdapter.submitList(items)
            updateTotals()
        }

        // Automatic promotions are recomputed inside the ViewModel; the totals row has to repaint
        // when they change, not only when the cart list itself is replaced.
        viewModel.promotionDiscount.observe(viewLifecycleOwner) {
            updateTotals()
        }

        viewModel.shopInfo.observe(viewLifecycleOwner) { shopInfo ->
            if (shopInfo != null) {
                CurrencyFormatter.setCurrency(shopInfo.currency)
                updateTotals()
                CustomerDisplayBus.shopName = shopInfo.shopName
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }

        viewModel.saleComplete.observe(viewLifecycleOwner) { sale ->
            if (sale != null) {
                showSaleCompleteDialog(sale)
                etDiscount.setText("")
                discountAmount = 0.0
                updateTotals()
                viewModel.refreshProducts()
                if (sale.paymentMethod == "CASH") {
                    BluetoothPrinter.openCashDrawer(requireContext())
                }
            }
        }

        viewModel.orderSaved.observe(viewLifecycleOwner) { saved ->
            if (saved == true) {
                toast(getString(R.string.msg_order_saved))
                etDiscount.setText("")
                discountAmount = 0.0
                updateTotals()
                viewModel.clearOrderSaved()
            }
        }
    }

    private fun setupCategoryChips(categories: List<Category>) {
        categoryChipsContainer.removeAllViews()
        val chips = mutableListOf<Pair<MaterialButton, Long?>>()

        // Every chip used to look identical, so there was no way to tell which category was
        // active. selectedCategoryId already tracked it; nothing was reading it.
        val highlight: (Long?) -> Unit = { selectedId ->
            chips.forEach { (chip, id) ->
                val active = id == selectedId
                chip.backgroundTintList = ColorStateList.valueOf(
                    requireContext().getColor(if (active) R.color.primary else R.color.surface)
                )
                chip.setTextColor(
                    requireContext().getColor(if (active) R.color.on_primary else R.color.primary)
                )
            }
        }
        highlightCategoryChip = highlight

        fun addChip(label: String, categoryId: Long?) {
            val chip = MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 4.dp; marginEnd = 4.dp }
                setOnClickListener { viewModel.filterByCategory(categoryId) }
            }
            chips.add(chip to categoryId)
            categoryChipsContainer.addView(chip)
        }

        addChip(getString(R.string.tab_all), null)
        categories.forEach { addChip(it.name, it.id) }

        highlight(viewModel.selectedCategoryId.value)
    }

    /**
     * Adds a product to the cart, surfacing an expiry warning first when appropriate. Expired
     * stock is still sellable (the shop decides), but the cashier is told.
     */
    private fun addProductToCart(product: Product) {
        val expiry = product.expiryDate
        when {
            product.isExpired -> toast(
                getString(
                    R.string.msg_expired_product,
                    product.name,
                    expiry?.let { DateFormatter.formatDate(it) } ?: ""
                )
            )
            product.isExpiringWithin(30) -> toast(
                getString(
                    R.string.msg_expiring_product,
                    product.name,
                    expiry?.let { DateFormatter.formatDate(it) } ?: ""
                )
            )
        }
        viewModel.addToCart(product)
    }

    override fun onStart() {
        super.onStart()
        // Promotions may have been edited on the Promotions screen; pick up any changes so the
        // automatic discount reflects them the next time the cart is priced.
        viewModel.refreshPromotions()
        childFragmentManager.setFragmentResultListener(
            BarcodeScannerFragment.REQUEST_BARCODE,
            viewLifecycleOwner,
            barcodeResultListener
        )
    }

    override fun onStop() {
        stopScale()
        super.onStop()
    }

    override fun onKeyShortcut(keyCode: Int, event: android.view.KeyEvent): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_F2 -> {
                etSearch.requestFocus()
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
                return true
            }
            android.view.KeyEvent.KEYCODE_F9 -> {
                showCheckoutDialog()
                return true
            }
            android.view.KeyEvent.KEYCODE_F11 -> {
                viewModel.holdCart()
                toast(getString(R.string.msg_cart_held))
                return true
            }
            android.view.KeyEvent.KEYCODE_F12 -> {
                showRecallDialog()
                return true
            }
            android.view.KeyEvent.KEYCODE_ESCAPE -> {
                return true
            }
        }
        return false
    }

    private fun openBarcodeScanner() {
        val fragment = BarcodeScannerFragment()
        fragment.show(childFragmentManager, "barcode_scan")
    }

    private val barcodeResultListener = androidx.fragment.app.FragmentResultListener { _, bundle ->
        val barcode = bundle.getString(BarcodeScannerFragment.RESULT_BARCODE)
        if (!barcode.isNullOrBlank()) {
            viewModel.addToCartByBarcode(barcode)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectScale() {
        ensureBluetoothForScale()
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        val names = devices.map { "${it.name} (${it.address})" }.toTypedArray()
        if (names.isEmpty()) {
            toast(getString(R.string.msg_no_bonded_devices))
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.title_select_scale)
            .setItems(names) { _, which ->
                val device = devices[which]
                WeighingScale.setAddress(requireContext(), device.address)
                toast(getString(R.string.msg_scale_selected))
                startScaleRead()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun startScaleRead() {
        WeighingScale.start(requireContext(), onWeightKg = { kg ->
            activity?.runOnUiThread {
                viewModel.applyWeightToCart(kg)
                toast("Weight: ${String.format("%.3f", kg)} kg")
            }
        }, onError = { msg ->
            activity?.runOnUiThread { toast(msg) }
        })
    }

    private fun stopScale() {
        WeighingScale.stop()
    }

    private fun ensureBluetoothForScale() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val need = listOf(
                android.Manifest.permission.BLUETOOTH_CONNECT
            )
            if (need.any { ContextCompat.checkSelfPermission(requireContext(), it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                requestPermissions(need.toTypedArray(), 1001)
            }
        }
    }

    private fun updateTotals() {
        val subtotal = viewModel.getSubtotal()
        val taxPercentage = viewModel.shopInfo.value?.taxPercentage ?: 0.0
        val taxAmount = subtotal * taxPercentage / 100.0
        val total = (subtotal + taxAmount - cartDiscountsTotal()).coerceAtLeast(0.0)

        // The subtotal row already draws a "Subtotal" label on the left, so this is the amount
        // only. It used to print the label a second time on the right.
        tvSubtotal.text = CurrencyFormatter.format(subtotal)
        tvTax.text = getString(R.string.format_tax_percent, taxPercentLabel(taxPercentage)) +
            ": " + CurrencyFormatter.format(taxAmount)
        tvTotal.text = getString(R.string.label_total) + ": " + CurrencyFormatter.format(total)
        CustomerDisplayBus.total = total
    }

    /**
     * Sum of every discount currently applied to the cart: cashier line discounts, automatic
     * promotions and the bill-level discount typed into the discount field.
     */
    private fun cartDiscountsTotal(): Double =
        viewModel.lineDiscountTotal() +
            (viewModel.promotionDiscount.value ?: 0.0) +
            discountAmount

    /** "5" rather than "5.0", so the tax caption reads the way a shopkeeper would write it. */
    private fun taxPercentLabel(percentage: Double): String =
        if (percentage == percentage.toLong().toDouble()) percentage.toLong().toString()
        else percentage.toString()

    /** Grand total of the current cart, including tax and every discount. */
    private fun cartTotal(): Double {
        val subtotal = viewModel.getSubtotal()
        val taxPercentage = viewModel.shopInfo.value?.taxPercentage ?: 0.0
        val taxAmount = subtotal * taxPercentage / 100.0
        return (subtotal + taxAmount - cartDiscountsTotal()).coerceAtLeast(0.0)
    }

    /**
     * Line-level discount. The cashier types a currency amount (capped at the line gross by the
     * ViewModel); an empty value clears it. Reached by long-pressing a cart row.
     */
    private fun showLineDiscountDialog(item: com.example.lumipos.ui.pos.CartItem) {
        val input = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = CurrencyFormatter.format(item.total)
            setText(if (item.manualDiscount > 0.0) item.manualDiscount.toString() else "")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_line_discount, item.name))
            .setView(input)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                val amount = input.text.toString().toDoubleOrNull() ?: 0.0
                viewModel.setLineDiscount(item.productId, amount)
            }
            .setNeutralButton(R.string.btn_clear) { _, _ ->
                viewModel.setLineDiscount(item.productId, 0.0)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showRecallDialog() {
        val held = viewModel.heldCarts.value.orEmpty()
        if (held.isEmpty()) {
            toast(getString(R.string.msg_no_held_carts))
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_recall_cart, null)
        val rv = dialogView.findViewById<RecyclerView>(R.id.rv_held_carts)
        rv.layoutManager = LinearLayoutManager(requireContext())
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.title_recall_cart)
            .setView(dialogView)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        rv.adapter = com.example.lumipos.adapter.HeldCartAdapter(held) { selected ->
            viewModel.recallCart(selected)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showCustomerPicker() {
        val customers = viewModel.customers.value.orEmpty()
        val labels = ArrayList<String>(customers.size + 1)
        labels.add(getString(R.string.walk_in_customer))
        customers.forEach { customer ->
            labels.add(
                if (customer.phone.isBlank()) customer.fullName
                else "${customer.fullName} Â· ${customer.phone}"
            )
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.label_select_customer)
            .setItems(labels.toTypedArray()) { _, which ->
                viewModel.selectCustomer(if (which == 0) null else customers[which - 1])
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showCheckoutDialog() {
        val cartItems = viewModel.cartItems.value ?: return
        if (cartItems.isEmpty()) {
            Toast.makeText(requireContext(), R.string.msg_cart_empty, Toast.LENGTH_SHORT).show()
            return
        }

        // A cart-level discount needs manager/admin approval; the approval sticks to the exact
        // discount value, so changing it afterwards re-triggers the gate.
        if (discountAmount > 0.0 && approvedBillDiscount != discountAmount) {
            com.example.lumipos.util.PermissionGate.require(
                this,
                com.example.lumipos.util.Permissions.Permission.BILL_DISCOUNT
            ) {
                approvedBillDiscount = discountAmount
                showCheckoutDialog()
            }
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_checkout, null)

        val subtotal = viewModel.getSubtotal()
        val taxPercentage = viewModel.shopInfo.value?.taxPercentage ?: 0.0
        val tax = subtotal * taxPercentage / 100.0
        val total = cartTotal()

        dialogView.findViewById<TextView>(R.id.tv_dialog_subtotal).text =
            getString(R.string.label_subtotal) + ": " + CurrencyFormatter.format(subtotal)
        dialogView.findViewById<TextView>(R.id.tv_dialog_tax).text =
            getString(R.string.format_tax_percent, taxPercentLabel(taxPercentage)) +
                ": " + CurrencyFormatter.format(tax)
        dialogView.findViewById<TextView>(R.id.tv_dialog_total).text =
            getString(R.string.label_total) + ": " + CurrencyFormatter.format(total)

        val paymentGroup = dialogView.findViewById<RadioGroup>(R.id.rg_payment_type)
        val cashLayout = dialogView.findViewById<View>(R.id.til_cash_received)
        val etReceived = dialogView.findViewById<EditText>(R.id.et_cash_received)
        val tvChange = dialogView.findViewById<TextView>(R.id.tv_change)
        val dueLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_due_date)
        val etDueDate = dialogView.findViewById<EditText>(R.id.et_due_date)
        val depositLayout = dialogView.findViewById<View>(R.id.til_deposit)
        val etDeposit = dialogView.findViewById<EditText>(R.id.et_deposit)
        val tvCreditStatus = dialogView.findViewById<TextView>(R.id.tv_credit_status)

        val splitSection = dialogView.findViewById<View>(R.id.ll_split_section)
        val spSplitMethod = dialogView.findViewById<Spinner>(R.id.sp_split_method)
        val etSplitAmount = dialogView.findViewById<EditText>(R.id.et_split_amount)
        val btnAddSplit = dialogView.findViewById<View>(R.id.btn_add_split_payment)
        val llSplitPayments = dialogView.findViewById<LinearLayout>(R.id.ll_split_payments)
        val tvSplitRemaining = dialogView.findViewById<TextView>(R.id.tv_split_remaining)

        val llStoreCredit = dialogView.findViewById<View>(R.id.ll_store_credit)
        val tvCreditAvailable = dialogView.findViewById<TextView>(R.id.tv_store_credit_available)
        val btnUseCredit = dialogView.findViewById<View>(R.id.btn_use_store_credit)
        val btnClearCredit = dialogView.findViewById<View>(R.id.btn_clear_store_credit)
        val tvCreditApplied = dialogView.findViewById<TextView>(R.id.tv_store_credit_applied)

        // Store credit acts as an extra tender: it shrinks the amount the customer still has to
        // hand over (payable), rather than discounting the sale.
        var creditApplied = 0.0
        fun payable(): Double = (total - creditApplied).coerceAtLeast(0.0)

        fun renderStoreCredit() {
            val customer = viewModel.selectedCustomer.value
            val wallet = customer?.storeCredit ?: 0.0
            tvCreditAvailable.text = getString(
                R.string.format_store_credit_available,
                CurrencyFormatter.format(wallet)
            )
            if (creditApplied > 0.0) {
                tvCreditApplied.visibility = View.VISIBLE
                tvCreditApplied.text = getString(
                    R.string.format_store_credit_applied,
                    CurrencyFormatter.format(creditApplied)
                )
                btnClearCredit.visibility = View.VISIBLE
            } else {
                tvCreditApplied.visibility = View.GONE
                btnClearCredit.visibility = View.GONE
            }
        }

        // Applied split payments as (method, amount); the running sum drives the remaining balance.
        val splitPayments = mutableListOf<Pair<String, Double>>()

        val splitMethods = listOf(
            "CASH" to getString(R.string.payment_cash),
            "CARD" to getString(R.string.payment_card),
            "STORE_CREDIT" to getString(R.string.payment_store_credit)
        )
        spSplitMethod.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            splitMethods.map { it.second }
        )

        fun updateSplitRemaining() {
            val paid = splitPayments.sumOf { it.second }
            val remaining = (payable() - paid).coerceAtLeast(0.0)
            tvSplitRemaining.text = getString(R.string.label_split_remaining) + ": " +
                CurrencyFormatter.format(remaining)
        }

        fun renderSplitPayments() {
            llSplitPayments.removeAllViews()
            for ((method, amount) in splitPayments) {
                val row = TextView(requireContext()).apply {
                    text = getString(
                        R.string.format_split_line,
                        splitMethods.firstOrNull { it.first == method }?.second ?: method,
                        CurrencyFormatter.format(amount)
                    )
                    textSize = 14f
                }
                llSplitPayments.addView(row)
            }
            updateSplitRemaining()
        }

        btnAddSplit.setOnClickListener {
            val amount = etSplitAmount.text.toString().toDoubleOrNull() ?: 0.0
            if (amount <= 0.0) {
                toast(getString(R.string.msg_insufficient_cash))
                return@setOnClickListener
            }
            val method = splitMethods[spSplitMethod.selectedItemPosition].first
            splitPayments.add(method to amount)
            etSplitAmount.setText("")
            renderSplitPayments()
        }

        var dueDateMillis: Long? = null

        fun pickDueDate() {
            val cal = Calendar.getInstance()
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val picked = DateFormatter.toStartOfDay(year, month, day)
                    dueDateMillis = picked
                    etDueDate.setText(DateFormatter.formatDate(picked))
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        etDueDate.setOnClickListener { pickDueDate() }
        dueLayout.setEndIconOnClickListener { pickDueDate() }

        etReceived.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val received = s?.toString()?.toDoubleOrNull() ?: 0.0
                tvChange.text = getString(R.string.label_change) + ": " +
                    CurrencyFormatter.format((received - payable()).coerceAtLeast(0.0))
            }
        })

        btnUseCredit.setOnClickListener {
            val wallet = viewModel.selectedCustomer.value?.storeCredit ?: 0.0
            creditApplied = minOf(wallet, total).coerceAtLeast(0.0)
            viewModel.applyStoreCredit(creditApplied)
            renderStoreCredit()
            updateSplitRemaining()
        }

        btnClearCredit.setOnClickListener {
            creditApplied = 0.0
            viewModel.clearStoreCredit()
            renderStoreCredit()
            updateSplitRemaining()
        }

        renderStoreCredit()

        // Declared before applyPaymentType, which calls it: Kotlin resolves local declarations
        // top-down, so a later definition would not be visible here.
        //
        // Recomputes as the deposit changes, so the cashier can see the effect of a bigger
        // down payment on the customer's remaining credit before confirming.
        fun updateCreditStatus() {
            val customer = viewModel.selectedCustomer.value
            val limit = customer?.creditLimit
            if (customer == null || limit == null || limit <= 0.0) {
                tvCreditStatus.text = getString(R.string.msg_no_credit_limit)
                tvCreditStatus.applyStatusTint("CLOSED")
                return
            }
            val owed = viewModel.customerOutstanding.value ?: 0.0
            val deposit = etDeposit.text.toString().toDoubleOrNull() ?: 0.0
            val projected = owed + (total - deposit)
            val available = limit - projected
            tvCreditStatus.text = getString(R.string.label_credit_limit) + ": " +
                CurrencyFormatter.format(limit) + "   " +
                getString(R.string.label_credit_owed) + ": " + CurrencyFormatter.format(owed) +
                "\n" + getString(R.string.label_credit_available) + ": " +
                CurrencyFormatter.format(available)
            tvCreditStatus.applyStatusTint(if (available < 0) "OVERDUE" else "PAID")
        }

        fun applyPaymentType() {
            val checked = paymentGroup.checkedRadioButtonId
            val isCredit = checked == R.id.rb_pay_credit
            val isSplit = checked == R.id.rb_pay_split
            cashLayout.visibility = if (isCredit || isSplit) View.GONE else View.VISIBLE
            tvChange.visibility = if (isCredit || isSplit) View.GONE else View.VISIBLE
            dueLayout.visibility = if (isCredit) View.VISIBLE else View.GONE
            depositLayout.visibility = if (isCredit) View.VISIBLE else View.GONE
            tvCreditStatus.visibility = if (isCredit) View.VISIBLE else View.GONE
            splitSection.visibility = if (isSplit) View.VISIBLE else View.GONE

            // Credit sales settle on account; spending the wallet at the same time is not a
            // meaningful combination, so credit is offered only for cash/split tenders.
            val wallet = viewModel.selectedCustomer.value?.storeCredit ?: 0.0
            llStoreCredit.visibility = if (!isCredit && wallet > 0.0) View.VISIBLE else View.GONE
            if (isCredit && creditApplied > 0.0) {
                creditApplied = 0.0
                viewModel.clearStoreCredit()
                renderStoreCredit()
            }

            if (isCredit) updateCreditStatus()
            if (isSplit) updateSplitRemaining()
        }
        paymentGroup.setOnCheckedChangeListener { _, _ -> applyPaymentType() }
        applyPaymentType()

        etDeposit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (paymentGroup.checkedRadioButtonId == R.id.rb_pay_credit) updateCreditStatus()
            }
        })

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.checkout_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_confirm_sale, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        // Wire the positive button by hand so validation can cancel the dismiss.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val checked = paymentGroup.checkedRadioButtonId
                if (checked == R.id.rb_pay_credit) {
                    val customer = viewModel.selectedCustomer.value
                    if (customer == null) {
                        toast(getString(R.string.msg_credit_needs_customer))
                        return@setOnClickListener
                    }
                    val due = dueDateMillis
                    if (due == null) {
                        toast(getString(R.string.msg_choose_due_date))
                        return@setOnClickListener
                    }
                    val deposit = etDeposit.text.toString().toDoubleOrNull() ?: 0.0
                    if (deposit < 0.0 || deposit > total) {
                        toast(getString(R.string.msg_insufficient_cash))
                        return@setOnClickListener
                    }
                    // Caught here as well as in the ViewModel: the ViewModel guard is the one that
                    // guarantees integrity, but by the time it runs the dialog has already
                    // dismissed. Checking first keeps the dialog open so the cashier can raise
                    // the deposit instead of losing the checkout.
                    val limit = customer.creditLimit
                    if (limit != null && limit > 0.0) {
                        val owed = viewModel.customerOutstanding.value ?: 0.0
                        val projected = owed + (total - deposit)
                        if (projected > limit) {
                            toast(
                                getString(
                                    R.string.msg_credit_limit_exceeded,
                                    CurrencyFormatter.format(limit),
                                    CurrencyFormatter.format(owed),
                                    CurrencyFormatter.format(projected)
                                )
                            )
                            return@setOnClickListener
                        }
                    }
                    viewModel.completeSale(
                        paymentMethod = "CREDIT",
                        amountPaid = deposit,
                        saleType = "CREDIT",
                        dueDate = due,
                        discountAmount = discountAmount
                    )
                } else if (checked == R.id.rb_pay_split) {
                    if (splitPayments.isEmpty()) {
                        toast(getString(R.string.msg_no_split_payments))
                        return@setOnClickListener
                    }
                    val paid = splitPayments.sumOf { it.second }
                    if (paid < payable()) {
                        toast(getString(R.string.msg_split_incomplete))
                        return@setOnClickListener
                    }
                    viewModel.completeSale(
                        paymentMethod = "SPLIT",
                        amountPaid = paid,
                        saleType = "CASH",
                        dueDate = null,
                        discountAmount = discountAmount,
                        splitPayments = splitPayments.toList()
                    )
                } else {
                    val received = etReceived.text.toString().toDoubleOrNull() ?: payable()
                    if (received < payable()) {
                        toast(getString(R.string.msg_insufficient_cash))
                        return@setOnClickListener
                    }
                    viewModel.completeSale(
                        paymentMethod = "CASH",
                        amountPaid = received,
                        saleType = "CASH",
                        dueDate = null,
                        discountAmount = discountAmount
                    )
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showSaleCompleteDialog(sale: com.example.lumipos.data.local.entity.Sale) {
        val isCredit = sale.saleType == "CREDIT"
        val message = buildString {
            appendLine("Invoice: ${sale.invoiceNumber}")
            appendLine("Customer: ${sale.customerName}")
            appendLine("Total: ${CurrencyFormatter.format(sale.totalAmount)}")
            appendLine("Paid: ${CurrencyFormatter.format(sale.amountPaid)}")
            if (isCredit) {
                appendLine(getString(R.string.label_amount_due) + ": " +
                    CurrencyFormatter.format(sale.totalAmount - sale.amountPaid))
                sale.dueDate?.let {
                    appendLine(getString(R.string.label_due_on) + ": " + DateFormatter.formatDate(it))
                }
                appendLine()
                append(getString(R.string.sale_credit_recorded))
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.sale_complete_title)
            .setMessage(message)
            .setPositiveButton(R.string.btn_print) { _, _ -> navigateToReceipt(sale.id) }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun navigateToReceipt(saleId: Long) {
        val bundle = Bundle().apply {
            putLong("saleId", saleId)
            putString("from", "pos")
        }
        findNavController().navigate(R.id.receiptPreviewFragment, bundle)
    }
}
