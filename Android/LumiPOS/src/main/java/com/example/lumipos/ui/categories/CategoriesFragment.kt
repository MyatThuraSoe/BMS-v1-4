package com.example.lumipos.ui.categories

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.example.lumipos.adapter.CategoryAdapter
import com.example.lumipos.data.local.entity.Category
import com.example.lumipos.data.repository.ProductRepository
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CategoriesFragment : Fragment() {

    private val viewModel: CategoriesViewModel by viewModels()
    private lateinit var adapter: CategoryAdapter
    private val productCounts = mutableMapOf<Long, Int>()

    private val productRepo by lazy {
        ProductRepository((requireActivity().application as LumiPOSApplication).database.productDao())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_categories, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = CategoryAdapter(
            emptyList(),
            onEdit = { category -> navigateToCategoryForm(category.id) },
            onDelete = { category -> showDeleteDialog(category) },
            productCountProvider = { categoryId -> productCounts[categoryId] ?: 0 }
        )
        view.findViewById<RecyclerView>(R.id.rv_categories).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@CategoriesFragment.adapter
        }

        view.findViewById<MaterialButton>(R.id.btn_add_category).setOnClickListener {
            navigateToCategoryForm(-1)
        }

        // item_stat_card leaves its icon and label empty, so set them here.
        view.findViewById<View>(R.id.card_total_categories)?.let { card ->
            card.findViewById<TextView>(R.id.tv_stat_label)?.text = getString(R.string.title_categories)
            card.findViewById<ImageView>(R.id.iv_stat_icon)?.setImageResource(R.drawable.ic_category)
        }

        viewModel.allCategories.observe(viewLifecycleOwner) { categories ->
            adapter.submitList(categories)
            loadProductCounts(categories)
        }

        viewModel.count.observe(viewLifecycleOwner) { count ->
            updateStatValue(view, 0, R.id.tv_stat_value, count?.toString() ?: "0")
        }
    }

    private fun loadProductCounts(categories: List<Category>) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val allProducts = productRepo.getAllActiveList()
            val map = mutableMapOf<Long, Int>()
            for (category in categories) {
                map[category.id] = allProducts.count { it.categoryId == category.id }
            }
            withContext(Dispatchers.Main) {
                productCounts.putAll(map)
                adapter.submitList(categories)
            }
        }
    }

    private fun updateStatValue(view: View, index: Int, textViewId: Int, value: String) {
        val statsRow = view.findViewById<LinearLayout>(R.id.stats_row)
        if (index < statsRow.childCount) {
            val card = statsRow.getChildAt(index)
            card.findViewById<TextView>(textViewId)?.text = value
        }
    }

    private fun navigateToCategoryForm(categoryId: Long) {
        val bundle = Bundle().apply { putLong("categoryId", categoryId) }
        findNavController().navigate(R.id.categoryFormFragment, bundle)
    }

    private fun showDeleteDialog(category: Category) {
        // Product.categoryId has no foreign key to categories, so nothing stops the delete and
        // nothing cleans up afterwards - the products are left pointing at a row that is gone.
        // Say so, with the real count, instead of deleting blind.
        val inUse = productCounts[category.id] ?: 0
        val message = buildString {
            append(getString(R.string.msg_delete_category_confirm, category.name))
            if (inUse > 0) {
                append("\n\n")
                append(getString(R.string.msg_delete_category_in_use, inUse))
                append("\n")
                append(getString(R.string.msg_delete_category_in_use_hint))
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_confirm_delete)
            .setMessage(message)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                viewModel.deleteCategory(category)
                toast(getString(R.string.msg_category_deleted))
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}