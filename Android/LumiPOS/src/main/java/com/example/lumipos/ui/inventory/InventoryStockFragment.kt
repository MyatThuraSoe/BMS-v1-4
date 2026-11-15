package com.example.lumipos.ui.inventory

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.StockProductAdapter
import com.example.lumipos.data.local.entity.Product
import com.google.android.material.button.MaterialButton

class InventoryStockFragment : Fragment() {

    private val viewModel: InventoryViewModel by viewModels()
    private var allProducts: List<Product> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_inventory_stock, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = StockProductAdapter(emptyList()) { product ->
            val bundle = Bundle().apply { putLong("productId", product.id) }
            findNavController().navigate(R.id.stockAdjustmentFragment, bundle)
        }
        view.findViewById<RecyclerView>(R.id.rv_stock_products).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }

        view.findViewById<MaterialButton>(R.id.btn_adjust_stock).setOnClickListener {
            findNavController().navigate(R.id.stockAdjustmentFragment)
        }

        val etSearch = view.findViewById<EditText>(R.id.et_search)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.lowercase() ?: ""
                val filtered = allProducts.filter {
                    it.name.lowercase().contains(query) || it.sku.lowercase().contains(query)
                }
                adapter.submitList(filtered)
            }
        })

        viewModel.allProducts.observe(viewLifecycleOwner) { products ->
            allProducts = products
            adapter.submitList(products)
        }
    }
}