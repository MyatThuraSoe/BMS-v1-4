package com.example.lumipos.ui.products

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.BundleComponentAdapter
import com.example.lumipos.data.local.entity.Product
import com.example.lumipos.data.local.entity.ProductBundleItem
import com.example.lumipos.util.Extensions.toast
import com.example.lumipos.util.ImageUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.textfield.TextInputEditText

class ProductFormFragment : Fragment() {

    private val viewModel: ProductFormViewModel by viewModels()
    private var editingProductId: Long = -1
    private var imageBytes: ByteArray? = null
    private var loadedProduct: Product? = null
    private var expiryMillis: Long? = null
    private var products: List<Product> = emptyList()
    private val bundleComponents = mutableListOf<ProductBundleItem>()
    private lateinit var componentAdapter: BundleComponentAdapter

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadImageFromUri(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_product_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editingProductId = arguments?.getLong("productId", -1) ?: -1

        // Cashiers must not see cost prices (RBAC); the field stays in the layout but hidden.
        viewLifecycleOwner.lifecycleScope.launch {
            val db = (requireContext().applicationContext as com.example.lumipos.LumiPOSApplication).database
            val role = withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.example.lumipos.util.Permissions.roleFrom(
                    db.settingDao().getValue(com.example.lumipos.util.Permissions.KEY_ACTIVE_ROLE)
                )
            }
            if (role == com.example.lumipos.util.Permissions.Role.CASHIER) {
                view.findViewById<View>(R.id.til_cost).visibility = View.GONE
            }
        }

        if (editingProductId > 0) {
            viewModel.loadProduct(editingProductId)
        }

        val cbIsBundle = view.findViewById<MaterialCheckBox>(R.id.cb_is_bundle)
        val llBundleSection = view.findViewById<LinearLayout>(R.id.ll_bundle_section)
        val rvComponents = view.findViewById<RecyclerView>(R.id.rv_bundle_components)

        componentAdapter = BundleComponentAdapter { item ->
            bundleComponents.remove(item)
            componentAdapter.submitList(bundleComponents.toList())
        }
        rvComponents.layoutManager = LinearLayoutManager(requireContext())
        rvComponents.adapter = componentAdapter

        cbIsBundle.setOnCheckedChangeListener { _, checked ->
            llBundleSection.visibility = if (checked) View.VISIBLE else View.GONE
        }

        view.findViewById<MaterialButton>(R.id.btn_add_component).setOnClickListener {
            showAddComponentDialog()
        }

        val etExpiry = view.findViewById<TextInputEditText>(R.id.et_expiry_date)
        etExpiry.setOnClickListener { showExpiryPicker(etExpiry) }
        etExpiry.setOnLongClickListener {
            expiryMillis = null
            etExpiry.setText("")
            true
        }

        view.findViewById<MaterialButton>(R.id.btn_choose_image).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        view.findViewById<MaterialButton>(R.id.btn_remove_image).setOnClickListener {
            imageBytes = null
            view.findViewById<ImageView>(R.id.iv_image_preview).setImageResource(R.drawable.ic_products)
            if (editingProductId > 0) viewModel.deleteProductImage(editingProductId)
        }

        view.findViewById<MaterialButton>(R.id.btn_save_product).setOnClickListener {
            val name = view.findViewById<TextInputEditText>(R.id.et_name).text.toString().trim()
            val sku = view.findViewById<TextInputEditText>(R.id.et_sku).text.toString().trim()
            val priceStr = view.findViewById<TextInputEditText>(R.id.et_price).text.toString().trim()
            val costStr = view.findViewById<TextInputEditText>(R.id.et_cost).text.toString().trim()
            val stockStr = view.findViewById<TextInputEditText>(R.id.et_stock).text.toString().trim()
            val minStockStr = view.findViewById<TextInputEditText>(R.id.et_min_stock).text.toString().trim()
            val description = view.findViewById<TextInputEditText>(R.id.et_desc).text.toString().trim()
            val batchNumber = view.findViewById<TextInputEditText>(R.id.et_batch_number).text.toString().trim()

            if (name.isEmpty() || sku.isEmpty() || priceStr.isEmpty()) {
                toast(getString(R.string.msg_product_required))
                return@setOnClickListener
            }

            val isBundle = cbIsBundle.isChecked
            if (isBundle && bundleComponents.isEmpty()) {
                toast(getString(R.string.msg_component_required))
                return@setOnClickListener
            }

            val price = priceStr.toDoubleOrNull() ?: 0.0
            val cost = costStr.toDoubleOrNull() ?: 0.0
            val stock = stockStr.toIntOrNull() ?: 0
            val minStock = minStockStr.toIntOrNull() ?: 10
            val wholesalePrice =
                view.findViewById<TextInputEditText>(R.id.et_wholesale_price).text.toString().trim().toDoubleOrNull()
            val vipPrice =
                view.findViewById<TextInputEditText>(R.id.et_vip_price).text.toString().trim().toDoubleOrNull()

            val base = loadedProduct
            val product = if (base != null && base.id == editingProductId) {
                // Preserve fields the form does not edit (category, barcode, timestamps, image).
                base.copy(
                    name = name,
                    sku = sku,
                    description = description,
                    unitPrice = price,
                    costPrice = cost,
                    stockQuantity = stock,
                    minStockLevel = minStock,
                    isBundle = isBundle,
                    batchNumber = batchNumber,
                    expiryDate = expiryMillis,
                    wholesalePrice = wholesalePrice,
                    vipPrice = vipPrice
                )
            } else {
                Product(
                    id = editingProductId.coerceAtLeast(0),
                    name = name,
                    sku = sku,
                    description = description,
                    unitPrice = price,
                    costPrice = cost,
                    stockQuantity = stock,
                    minStockLevel = minStock,
                    isBundle = isBundle,
                    batchNumber = batchNumber,
                    expiryDate = expiryMillis,
                    wholesalePrice = wholesalePrice,
                    vipPrice = vipPrice
                )
            }
            viewModel.saveProduct(product, imageBytes, bundleComponents.toList())
        }

        viewModel.product.observe(viewLifecycleOwner) { product ->
            product?.let { p ->
                loadedProduct = p
                view.findViewById<TextInputEditText>(R.id.et_name).setText(p.name)
                view.findViewById<TextInputEditText>(R.id.et_sku).setText(p.sku)
                view.findViewById<TextInputEditText>(R.id.et_price).setText(p.unitPrice.toString())
                view.findViewById<TextInputEditText>(R.id.et_cost).setText(if (p.costPrice > 0) p.costPrice.toString() else "")
                view.findViewById<TextInputEditText>(R.id.et_wholesale_price).setText(p.wholesalePrice?.toString() ?: "")
                view.findViewById<TextInputEditText>(R.id.et_vip_price).setText(p.vipPrice?.toString() ?: "")
                view.findViewById<TextInputEditText>(R.id.et_stock).setText(p.stockQuantity.toString())
                view.findViewById<TextInputEditText>(R.id.et_min_stock).setText(p.minStockLevel.toString())
                view.findViewById<TextInputEditText>(R.id.et_desc).setText(p.description)
                view.findViewById<TextInputEditText>(R.id.et_batch_number).setText(p.batchNumber)
                expiryMillis = p.expiryDate
                view.findViewById<TextInputEditText>(R.id.et_expiry_date).setText(
                    p.expiryDate?.let { com.example.lumipos.util.DateFormatter.formatDate(it) } ?: ""
                )
                if (p.isBundle) {
                    cbIsBundle.isChecked = true
                    llBundleSection.visibility = View.VISIBLE
                }
                p.imageBytes?.let { bytes ->
                    view.findViewById<ImageView>(R.id.iv_image_preview).setImageBitmap(
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    )
                    imageBytes = bytes
                }
            }
        }

        viewModel.bundleItems.observe(viewLifecycleOwner) { items ->
            bundleComponents.clear()
            bundleComponents.addAll(items)
            componentAdapter.submitList(bundleComponents.toList())
        }

        viewModel.allProducts.observe(viewLifecycleOwner) { products = it }

        viewModel.saveSuccess.observe(viewLifecycleOwner) { success ->
            if (success == true) {
                toast(getString(R.string.msg_product_saved))
                findNavController().popBackStack()
            }
        }
    }

    private fun showExpiryPicker(target: EditText) {
        val cal = java.util.Calendar.getInstance()
        expiryMillis?.let { cal.timeInMillis = it }
        android.app.DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val picked = java.util.Calendar.getInstance()
                picked.set(year, month, day, 23, 59, 59)
                picked.set(java.util.Calendar.MILLISECOND, 999)
                expiryMillis = picked.timeInMillis
                target.setText(com.example.lumipos.util.DateFormatter.formatDate(picked.timeInMillis))
            },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showAddComponentDialog() {
        val candidates = products.filter { it.id != editingProductId }
        if (candidates.isEmpty()) {
            toast(getString(R.string.msg_no_components))
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_component, null)
        val spComponent = dialogView.findViewById<Spinner>(R.id.sp_component)
        val etQty = dialogView.findViewById<EditText>(R.id.et_component_qty)

        spComponent.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            candidates.map { it.name }
        )

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.btn_add_component)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val idx = spComponent.selectedItemPosition
                if (idx < 0 || idx >= candidates.size) return@setOnClickListener
                val qty = etQty.text.toString().toIntOrNull() ?: 0
                if (qty < 1) {
                    toast(getString(R.string.msg_component_qty_invalid))
                    return@setOnClickListener
                }
                val component = candidates[idx]
                val existing = bundleComponents.find { it.componentProductId == component.id }
                if (existing != null) {
                    val pos = bundleComponents.indexOf(existing)
                    bundleComponents[pos] = existing.copy(quantity = existing.quantity + qty)
                } else {
                    bundleComponents.add(
                        ProductBundleItem(
                            bundleProductId = editingProductId.coerceAtLeast(0),
                            componentProductId = component.id,
                            componentName = component.name,
                            quantity = qty
                        )
                    )
                }
                componentAdapter.submitList(bundleComponents.toList())
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun loadImageFromUri(uri: Uri) {
        val scaled = ImageUtils.loadScaledBitmap(requireContext(), uri, 512)
        if (scaled == null) {
            toast(getString(R.string.msg_failed_load_image))
            return
        }
        imageBytes = ImageUtils.toBytes(scaled, Bitmap.CompressFormat.JPEG, 80)
        view?.findViewById<ImageView>(R.id.iv_image_preview)?.setImageBitmap(scaled)
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap =
        ImageUtils.scaleDown(bitmap, maxSize)
}
