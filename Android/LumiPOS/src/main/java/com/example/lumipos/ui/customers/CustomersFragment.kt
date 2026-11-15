package com.example.lumipos.ui.customers

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
import com.example.lumipos.adapter.CustomerAdapter
import com.example.lumipos.data.local.entity.Customer
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton

class CustomersFragment : Fragment() {

    private val viewModel: CustomersViewModel by viewModels()
    private lateinit var adapter: CustomerAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_customers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.loadCustomers()

        adapter = CustomerAdapter(
            emptyList(),
            onEdit = { customer -> navigateToCustomerForm(customer.id) },
            onDelete = { customer -> showDeleteDialog(customer) }
        )
        view.findViewById<RecyclerView>(R.id.rv_customers).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@CustomersFragment.adapter
        }

        val etSearch = view.findViewById<EditText>(R.id.et_search)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.search(s?.toString() ?: "")
            }
        })

        view.findViewById<MaterialButton>(R.id.btn_add_customer).setOnClickListener {
            navigateToCustomerForm(-1)
        }

        viewModel.filteredCustomers.observe(viewLifecycleOwner) { customers ->
            adapter.submitList(customers ?: emptyList())
        }
    }

    private fun navigateToCustomerForm(customerId: Long) {
        val bundle = Bundle().apply { putLong("customerId", customerId) }
        findNavController().navigate(R.id.customerFormFragment, bundle)
    }

    private fun showDeleteDialog(customer: Customer) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_confirm_delete)
            .setMessage(getString(R.string.msg_delete_customer_confirm, customer.fullName))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                viewModel.deleteCustomer(customer)
                toast(getString(R.string.msg_customer_deleted))
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}