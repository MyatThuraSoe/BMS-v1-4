package com.example.lumipos.ui.suppliers

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.SupplierAdapter
import com.example.lumipos.data.local.entity.Supplier
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton

class SuppliersFragment : Fragment() {

    private val viewModel: SuppliersViewModel by viewModels()
    private lateinit var adapter: SupplierAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_suppliers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.loadSuppliers()

        adapter = SupplierAdapter(
            emptyList(),
            onEdit = { supplier -> navigateToSupplierForm(supplier.id) },
            onDelete = { supplier -> showDeleteDialog(supplier) }
        )
        view.findViewById<RecyclerView>(R.id.rv_suppliers).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@SuppliersFragment.adapter
        }

        val etSearch = view.findViewById<EditText>(R.id.et_search)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.search(s?.toString() ?: "")
            }
        })

        view.findViewById<MaterialButton>(R.id.btn_add_supplier).setOnClickListener {
            navigateToSupplierForm(-1)
        }

        viewModel.filteredSuppliers.observe(viewLifecycleOwner) { suppliers ->
            adapter.submitList(suppliers ?: emptyList())
        }
    }

    private fun navigateToSupplierForm(supplierId: Long) {
        val bundle = Bundle().apply { putLong("supplierId", supplierId) }
        findNavController().navigate(R.id.supplierFormFragment, bundle)
    }

    private fun showDeleteDialog(supplier: Supplier) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_confirm_delete)
            .setMessage(getString(R.string.msg_delete_supplier_confirm, supplier.name))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                viewModel.deleteSupplier(supplier)
                toast(getString(R.string.msg_supplier_deleted))
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}