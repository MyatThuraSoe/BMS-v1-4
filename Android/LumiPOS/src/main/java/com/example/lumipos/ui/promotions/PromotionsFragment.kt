package com.example.lumipos.ui.promotions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.PromotionAdapter
import com.example.lumipos.data.local.entity.Category
import com.example.lumipos.data.local.entity.Product
import com.example.lumipos.data.local.entity.Promotion
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

class PromotionsFragment : Fragment() {

    private val viewModel: PromotionsViewModel by viewModels()
    private lateinit var adapter: PromotionAdapter

    private var products: List<Product> = emptyList()
    private var categories: List<Category> = emptyList()

    private val typeCodes = listOf("PERCENT", "AMOUNT", "BOGO", "QUANTITY")
    private val scopeCodes = listOf("ALL", "PRODUCT", "CATEGORY")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_promotions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = PromotionAdapter(
            onClick = { showForm(it) },
            onDelete = { confirmDelete(it) }
        )
        val rv = view.findViewById<RecyclerView>(R.id.rv_promotions)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        val emptyView = view.findViewById<TextView>(R.id.tv_no_promotions)

        view.findViewById<MaterialButton>(R.id.btn_add_promotion).setOnClickListener {
            showForm(null)
        }

        viewModel.promotions.observe(viewLifecycleOwner) { promos ->
            adapter.submitList(promos)
            emptyView.visibility = if (promos.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (promos.isEmpty()) View.GONE else View.VISIBLE
        }
        viewModel.products.observe(viewLifecycleOwner) { products = it }
        viewModel.categories.observe(viewLifecycleOwner) { categories = it }
    }

    private fun confirmDelete(promotion: Promotion) {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.promo_delete_confirm)
            .setPositiveButton(R.string.btn_delete) { _, _ -> viewModel.delete(promotion) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showForm(existing: Promotion?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_promotion, null)

        val etName = dialogView.findViewById<EditText>(R.id.et_promo_name)
        val spType = dialogView.findViewById<Spinner>(R.id.sp_promo_type)
        val spScope = dialogView.findViewById<Spinner>(R.id.sp_promo_scope)
        val spTarget = dialogView.findViewById<Spinner>(R.id.sp_promo_target)
        val etPercent = dialogView.findViewById<EditText>(R.id.et_promo_percent)
        val etAmount = dialogView.findViewById<EditText>(R.id.et_promo_amount)
        val etBuy = dialogView.findViewById<EditText>(R.id.et_promo_buy)
        val etGet = dialogView.findViewById<EditText>(R.id.et_promo_get)
        val etBundleQty = dialogView.findViewById<EditText>(R.id.et_promo_bundle_qty)
        val etBundlePrice = dialogView.findViewById<EditText>(R.id.et_promo_bundle_price)
        val cbActive = dialogView.findViewById<CheckBox>(R.id.cb_promo_active)

        val tilPercent = dialogView.findViewById<TextInputLayout>(R.id.til_promo_percent)
        val tilAmount = dialogView.findViewById<TextInputLayout>(R.id.til_promo_amount)
        val tilBuy = dialogView.findViewById<TextInputLayout>(R.id.til_promo_buy)
        val tilGet = dialogView.findViewById<TextInputLayout>(R.id.til_promo_get)
        val tilBundleQty = dialogView.findViewById<TextInputLayout>(R.id.til_promo_bundle_qty)
        val tilBundlePrice = dialogView.findViewById<TextInputLayout>(R.id.til_promo_bundle_price)

        val typeLabels = listOf(
            getString(R.string.promo_type_percent),
            getString(R.string.promo_type_amount),
            getString(R.string.promo_type_bogo),
            getString(R.string.promo_type_quantity)
        )
        spType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, typeLabels)

        val scopeLabels = listOf(
            getString(R.string.promo_scope_all),
            getString(R.string.title_products),
            getString(R.string.title_categories)
        )
        spScope.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, scopeLabels)

        fun refreshTargetSpinner() {
            val scope = scopeCodes[spScope.selectedItemPosition]
            if (scope == "PRODUCT") {
                spTarget.visibility = View.VISIBLE
                spTarget.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    products.map { it.name }
                )
            } else if (scope == "CATEGORY") {
                spTarget.visibility = View.VISIBLE
                spTarget.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    categories.map { it.name }
                )
            } else {
                spTarget.visibility = View.GONE
            }
        }

        fun refreshValueFields() {
            val type = typeCodes[spType.selectedItemPosition]
            tilPercent.visibility = if (type == "PERCENT") View.VISIBLE else View.GONE
            tilAmount.visibility = if (type == "AMOUNT") View.VISIBLE else View.GONE
            tilBuy.visibility = if (type == "BOGO") View.VISIBLE else View.GONE
            tilGet.visibility = if (type == "BOGO") View.VISIBLE else View.GONE
            tilBundleQty.visibility = if (type == "QUANTITY") View.VISIBLE else View.GONE
            tilBundlePrice.visibility = if (type == "QUANTITY") View.VISIBLE else View.GONE
        }

        spScope.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                refreshTargetSpinner()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        spType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                refreshValueFields()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Pre-fill when editing.
        existing?.let { promo ->
            etName.setText(promo.name)
            typeCodes.indexOf(promo.type).takeIf { it >= 0 }?.let { spType.setSelection(it) }
            scopeCodes.indexOf(promo.scope).takeIf { it >= 0 }?.let { spScope.setSelection(it) }
            etPercent.setText(promo.percentOff.takeIf { it > 0 }?.toString() ?: "")
            etAmount.setText(promo.amountOff.takeIf { it > 0 }?.toString() ?: "")
            etBuy.setText(promo.buyQty.takeIf { it > 0 }?.toString() ?: "")
            etGet.setText(promo.getQty.takeIf { it > 0 }?.toString() ?: "")
            etBundleQty.setText(promo.bundleQty.takeIf { it > 0 }?.toString() ?: "")
            etBundlePrice.setText(promo.bundlePrice.takeIf { it > 0 }?.toString() ?: "")
            cbActive.isChecked = promo.active
        }
        refreshTargetSpinner()
        refreshValueFields()

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) R.string.title_new_promotion else R.string.title_edit_promotion)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text.toString().trim()
                if (name.isBlank()) {
                    toast(getString(R.string.msg_promo_name_required))
                    return@setOnClickListener
                }
                val type = typeCodes[spType.selectedItemPosition]
                val scope = scopeCodes[spScope.selectedItemPosition]
                var productId: Long? = null
                var categoryId: Long? = null
                if (scope == "PRODUCT") {
                    val idx = spTarget.selectedItemPosition
                    if (idx < 0 || idx >= products.size) {
                        toast(getString(R.string.msg_promo_product_required))
                        return@setOnClickListener
                    }
                    productId = products[idx].id
                } else if (scope == "CATEGORY") {
                    val idx = spTarget.selectedItemPosition
                    if (idx < 0 || idx >= categories.size) {
                        toast(getString(R.string.msg_promo_product_required))
                        return@setOnClickListener
                    }
                    categoryId = categories[idx].id
                }

                val promo = Promotion(
                    id = existing?.id ?: 0,
                    name = name,
                    type = type,
                    scope = scope,
                    productId = productId,
                    categoryId = categoryId,
                    percentOff = etPercent.text.toString().toDoubleOrNull() ?: 0.0,
                    amountOff = etAmount.text.toString().toDoubleOrNull() ?: 0.0,
                    buyQty = etBuy.text.toString().toIntOrNull() ?: 0,
                    getQty = etGet.text.toString().toIntOrNull() ?: 0,
                    bundleQty = etBundleQty.text.toString().toIntOrNull() ?: 0,
                    bundlePrice = etBundlePrice.text.toString().toDoubleOrNull() ?: 0.0,
                    startMinute = existing?.startMinute ?: -1,
                    endMinute = existing?.endMinute ?: -1,
                    active = cbActive.isChecked,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis()
                )
                viewModel.save(promo)
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
