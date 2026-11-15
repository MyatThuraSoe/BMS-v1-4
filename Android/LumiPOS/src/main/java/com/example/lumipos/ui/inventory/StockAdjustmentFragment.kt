package com.example.lumipos.ui.inventory

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StockAdjustmentFragment : Fragment() {

    private val viewModel: InventoryViewModel by viewModels()
    private var selectedProduct: Product? = null

    private val productRepo by lazy {
        ProductRepository((requireActivity().application as LumiPOSApplication).database.productDao())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_stock_adjustment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val productIdArg = arguments?.getLong("productId", -1) ?: -1
        if (productIdArg > 0) {
            lifecycleScope.launch(Dispatchers.IO) {
                selectedProduct = productRepo.getById(productIdArg)
                withContext(Dispatchers.Main) {
                    selectedProduct?.let { p ->
                        view.findViewById<TextInputEditText>(R.id.et_product_search).setText("${p.name} (${p.sku})")
                    }
                }
            }
        }

        val etSearch = view.findViewById<TextInputEditText>(R.id.et_product_search)
        etSearch.setOnClickListener { showProductSearchDialog() }

        // Make the arrow a real affordance rather than decoration - tapping it opens the same
        // picker. The layout uses `endIconMode="custom"`, which does not wire a click by itself.
        view.findViewById<TextInputLayout>(R.id.til_product_search)
            .setEndIconOnClickListener { showProductSearchDialog() }

        view.findViewById<MaterialButton>(R.id.btn_confirm_adjustment).setOnClickListener {
            val product = selectedProduct ?: run {
                toast(getString(R.string.msg_select_product_first))
                return@setOnClickListener
            }

            val radioGroup = view.findViewById<RadioGroup>(R.id.rg_adjustment_type)
            val qtyStr = view.findViewById<TextInputEditText>(R.id.et_qty).text.toString()
            val reason = view.findViewById<TextInputEditText>(R.id.et_reason).text.toString()

            val qty = qtyStr.toIntOrNull()
            if (qty == null || qty <= 0) {
                toast(getString(R.string.msg_enter_valid_quantity))
                return@setOnClickListener
            }
            if (reason.isBlank()) {
                toast(getString(R.string.msg_reason_required))
                return@setOnClickListener
            }

            // Read the direction explicitly. Previously anything that was not rb_add_stock fell
            // through to REMOVE, so leaving the group untouched silently *removed* stock.
            val change = when (radioGroup.checkedRadioButtonId) {
                R.id.rb_add_stock -> qty
                R.id.rb_remove_stock -> -qty
                else -> {
                    toast(getString(R.string.msg_choose_adjustment_type))
                    return@setOnClickListener
                }
            }

            viewModel.adjustStock(product.id, change, reason)
            toast(getString(R.string.msg_stock_adjusted))
            findNavController().popBackStack()
        }
    }

    private fun showProductSearchDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_select_product, null)

        val etSearch = dialogView.findViewById<EditText>(R.id.et_search)
        val rvProducts = dialogView.findViewById<RecyclerView>(R.id.rv_products)
        val tvNoProducts = dialogView.findViewById<TextView>(R.id.tv_no_products)

        // Declared before the adapter below, because the adapter's click lambda closes over it
        // and Kotlin does not hoist local declarations.
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_product)
            .setView(dialogView)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        // item_stock_product + StockProductAdapter, rather than the cart row this used to borrow.
        // The cart row carries a quantity stepper and a delete icon, none of which the picker
        // wired up, so every result looked interactive while only the row body did anything.
        val adapter = StockProductAdapter(emptyList()) { product ->
            selectedProduct = product
            view?.findViewById<TextInputEditText>(R.id.et_product_search)
                ?.setText("${product.name} (${product.sku})")
            dialog.dismiss()
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
                    tvNoProducts.visibility =
                        if (products.isEmpty()) View.VISIBLE else View.GONE
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
}