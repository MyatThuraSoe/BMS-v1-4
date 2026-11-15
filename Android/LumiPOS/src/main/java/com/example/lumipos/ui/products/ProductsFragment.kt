package com.example.lumipos.ui.products

import android.os.Bundle
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.ProductListAdapter
import com.example.lumipos.data.local.entity.Product
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProductsFragment : Fragment() {

    private val viewModel: ProductsViewModel by viewModels()
    private lateinit var adapter: ProductListAdapter
    private lateinit var etSearch: EditText

    /** Set by the categories observer so the selectedCategoryFilter observer can repaint chips. */
    private var highlightCategoryChip: ((Long?) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_products, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ProductListAdapter(
            onEdit = { product -> navigateToProductForm(product.id) },
            onDelete = { product ->
                // Deleting a product is an admin action; a cashier must call one over for a PIN.
                com.example.lumipos.util.PermissionGate.require(
                    this,
                    com.example.lumipos.util.Permissions.Permission.DELETE_PRODUCT
                ) { showDeleteDialog(product) }
            }
        )
        view.findViewById<RecyclerView>(R.id.rv_products).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@ProductsFragment.adapter
        }

        etSearch = view.findViewById(R.id.et_search)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.search(s?.toString() ?: "")
            }
        })

        view.findViewById<MaterialButton>(R.id.btn_add_product).setOnClickListener {
            navigateToProductForm(-1)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.products.collectLatest { paged ->
                adapter.submitData(paged)
            }
        }

        viewModel.categories.observe(viewLifecycleOwner) { categories ->
            val chipsContainer = view.findViewById<LinearLayout>(R.id.filter_chips)
            chipsContainer.removeAllViews()

            val chips = mutableListOf<Pair<MaterialButton, Long?>>()

            // Every chip looked identical, so the active category was invisible.
            // selectedCategoryFilter already tracked it; nothing was reading it.
            val highlight: (Long?) -> Unit = { selectedId ->
                chips.forEach { (chip, id) ->
                    val active = id == selectedId
                    chip.backgroundTintList = ColorStateList.valueOf(
                        requireContext().getColor(if (active) R.color.primary else R.color.surface)
                    )
                    chip.setTextColor(
                        requireContext().getColor(
                            if (active) R.color.on_primary else R.color.primary
                        )
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
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = 4.dp; marginEnd = 4.dp }
                    setOnClickListener { viewModel.filterByCategory(categoryId) }
                }
                chips.add(chip to categoryId)
                chipsContainer.addView(chip)
            }

            addChip(getString(R.string.tab_all), null)
            categories.forEach { addChip(it.name, it.id) }

            highlight(viewModel.selectedCategoryFilter.value)
        }

        viewModel.selectedCategoryFilter.observe(viewLifecycleOwner) { selectedId ->
            highlightCategoryChip?.invoke(selectedId)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun navigateToProductForm(productId: Long) {
        val bundle = Bundle().apply { putLong("productId", productId) }
        findNavController().navigate(R.id.productFormFragment, bundle)
    }

    private fun showDeleteDialog(product: Product) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_confirm_delete)
            .setMessage(getString(R.string.msg_delete_product_confirm, product.name))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                viewModel.deleteProduct(product)
                toast(getString(R.string.msg_product_deleted))
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}