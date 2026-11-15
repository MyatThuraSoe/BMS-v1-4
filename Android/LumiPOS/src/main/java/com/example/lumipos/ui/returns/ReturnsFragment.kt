package com.example.lumipos.ui.returns

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.ReturnAdapter
import com.example.lumipos.data.local.entity.Return
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton

class ReturnsFragment : Fragment() {

    private val viewModel: ReturnsViewModel by viewModels()
    private lateinit var adapter: ReturnAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_returns, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ReturnAdapter { showReturnDetail(it) }
        val rv = view.findViewById<RecyclerView>(R.id.rv_returns)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        val emptyView = view.findViewById<TextView>(R.id.tv_no_returns)

        view.findViewById<MaterialButton>(R.id.btn_new_return).setOnClickListener {
            showInvoiceDialog()
        }

        viewModel.returns.observe(viewLifecycleOwner) { returns ->
            adapter.submitList(returns)
            emptyView.visibility = if (returns.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (returns.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.lookedUpSale.observe(viewLifecycleOwner) { sale ->
            sale ?: return@observe
            val bundle = Bundle().apply { putLong("saleId", sale.id) }
            findNavController().navigate(R.id.returnFormFragment, bundle)
            viewModel.consumeLookup()
        }

        viewModel.notFound.observe(viewLifecycleOwner) { notFound ->
            if (notFound) {
                toast(getString(R.string.msg_return_sale_not_found))
                viewModel.consumeLookup()
            }
        }
    }

    private fun showInvoiceDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.hint_invoice_number)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.btn_new_return)
            .setView(input)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                val invoice = input.text.toString().trim()
                if (invoice.isNotEmpty()) viewModel.findSale(invoice)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showReturnDetail(returnEntity: Return) {
        val message = buildString {
            appendLine("${getString(R.string.label_return_invoice)}: ${returnEntity.invoiceNumber}")
            appendLine("${getString(R.string.format_customer_label, returnEntity.customerName)}")
            appendLine("${getString(R.string.label_refund_method)}: ${returnEntity.refundMethod}")
            appendLine("${getString(R.string.label_refund_total)}: ${CurrencyFormatter.format(returnEntity.totalRefund)}")
            appendLine("${DateFormatter.formatDateTime(returnEntity.createdAt)}")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(returnEntity.returnNumber)
            .setMessage(message.trim())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
