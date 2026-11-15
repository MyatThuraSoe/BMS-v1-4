package com.example.lumipos.ui.purchases

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.R
import com.example.lumipos.adapter.StockProductAdapter
import com.example.lumipos.data.local.entity.Product
import com.example.lumipos.data.repository.ProductRepository
import com.example.lumipos.data.repository.SupplierRepository
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PurchaseFormFragment : Fragment() {

    private val viewModel: PurchaseFormViewModel by viewModels()
    private var selectedSupplierId: Long = 0
    private var selectedSupplierName = ""

    private val db by lazy { (requireActivity().application as LumiPOSApplication).database }
    private val productRepo by lazy { ProductRepository(db.productDao()) }
    private val supplierRepo by lazy { SupplierRepository(db.supplierDao()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_purchase_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextInputEditText>(R.id.et_supplier_search).setOnClickListener {
            showSupplierDialog()
        }

        view.findViewById<TextInputEditText>(R.id.et_product_lookup).setOnClickListener {
            showProductDialog()
        }

        view.findViewById<MaterialButton>(R.id.btn_save_purchase).setOnClickListener {
            if (viewModel.getSubtotal() <= 0) {
                toast(getString(R.string.msg_purchase_add_product))
                return@setOnClickListener
            }
            if (selectedSupplierId <= 0) {
                toast(getString(R.string.msg_purchase_select_supplier))
                return@setOnClickListener
            }
            val status = view.findViewById<Spinner>(R.id.sp_payment_status).selectedItem?.toString()
                ?: "PENDING"
            val tax = view.findViewById<TextInputEditText>(R.id.et_purchase_tax).text.toString().trim()
                .toDoubleOrNull() ?: 0.0
            viewModel.savePurchase(selectedSupplierId, selectedSupplierName, status, tax)
        }

        viewModel.cartItems.observe(viewLifecycleOwner) { items ->
            renderCartItems(view, items)
        }

        // Subtotal is a separate signal so that editing a quantity refreshes the total without
        // rebuilding the rows - rebuilding would destroy the field being typed into.
        viewModel.subtotal.observe(viewLifecycleOwner) { subtotal ->
            view.findViewById<TextView>(R.id.tv_purchase_subtotal).text =
                getString(R.string.format_total_amount, CurrencyFormatter.format(subtotal))
        }

        viewModel.purchaseSaved.observe(viewLifecycleOwner) { saved ->
            if (saved == true) {
                toast(getString(R.string.msg_purchase_saved))
                findNavController().popBackStack()
            }
        }
    }

    private fun renderCartItems(view: View, items: List<PurchaseCartItem>) {
        val container = view.findViewById<LinearLayout>(R.id.selected_items_container)
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        items.forEachIndexed { index, item ->
            val row = inflater.inflate(R.layout.item_purchase_cart, container, false)
            row.findViewById<TextView>(R.id.tv_cart_product).text = item.productName
            row.findViewById<TextView>(R.id.tv_cart_price).text = CurrencyFormatter.format(item.unitPrice)

            val qtyField = row.findViewById<EditText>(R.id.et_cart_qty)
            qtyField.setText(item.quantity.toString())
            // Attached after setText so populating the row does not look like a user edit. The
            // ViewModel ignores no-op values, so this cannot loop.
            qtyField.doAfterTextChanged { editable ->
                val qty = editable?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
                if (qty > 0) viewModel.updateQuantity(index, qty)
            }

            row.findViewById<ImageButton>(R.id.btn_remove_cart_item).setOnClickListener {
                viewModel.removeItem(index)
            }
            container.addView(row)
        }
    }

    private fun showSupplierDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val suppliers = supplierRepo.getAllList()
            withContext(Dispatchers.Main) {
                if (suppliers.isEmpty()) {
                    toast(getString(R.string.msg_no_suppliers))
                    return@withContext
                }
                val names = suppliers.map { it.name }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.select_supplier)
                    .setItems(names) { _, which ->
                        val supplier = suppliers[which]
                        selectedSupplierId = supplier.id
                        selectedSupplierName = supplier.name
                        view?.findViewById<TextInputEditText>(R.id.et_supplier_search)
                            ?.setText(supplier.name)
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
        }
    }

    /**
     * Product picker.
     *
     * Uses `item_stock_product.xml` via [StockProductAdapter], not the cart row it used to borrow:
     * the cart row carries a quantity stepper and a delete icon, neither of which the picker wired,
     * so every search result looked interactive while only the row body did anything. It also built
     * a brand-new anonymous adapter on every keystroke; the adapter is now created once.
     */
    private fun showProductDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_select_product, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.et_search)
        val rvProducts = dialogView.findViewById<RecyclerView>(R.id.rv_products)
        val tvNoProducts = dialogView.findViewById<TextView>(R.id.tv_no_products)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_product)
            .setView(dialogView)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        val adapter = StockProductAdapter(emptyList()) { product ->
            dialog.dismiss()
            showAddQuantityDialog(product)
        }
        rvProducts.layoutManager = LinearLayoutManager(requireContext())
        rvProducts.adapter = adapter

        fun refresh(query: String?) {
            lifecycleScope.launch(Dispatchers.IO) {
                val products = if (query.isNullOrBlank()) {
                    productRepo.getAllActiveForPos()
                } else {
                    productRepo.searchForPos(query)
                }
                withContext(Dispatchers.Main) {
                    adapter.submitList(products)
                    tvNoProducts.visibility = if (products.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh(s?.toString()) }
        })

        refresh("")
        dialog.show()
    }

    private fun showAddQuantityDialog(product: Product) {
        val qtyField = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.qty_hint)
        }

        val priceField = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.hint_unit_price)
            setText(product.unitPrice.toString())
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(qtyField)
            addView(priceField)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.format_add_product_title, product.name))
            .setView(container)
            .setPositiveButton(R.string.btn_add, null) // wired below so it can veto the dismiss
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        // A plain positive handler always dismisses, so a typo'd quantity closed the dialog and the
        // toast landed on a screen that had already moved on.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val qty = qtyField.text.toString().toIntOrNull()
                val price = priceField.text.toString().toDoubleOrNull()
                if (qty == null || qty <= 0 || price == null || price <= 0) {
                    qtyField.error = getString(R.string.msg_invalid_qty_price)
                } else {
                    viewModel.addProduct(product, qty, price)
                    toast(getString(R.string.msg_product_added))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
