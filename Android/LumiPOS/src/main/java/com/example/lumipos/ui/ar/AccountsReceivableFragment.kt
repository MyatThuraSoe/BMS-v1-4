package com.example.lumipos.ui.ar

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.OutstandingInvoiceAdapter
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.textfield.TextInputLayout

class AccountsReceivableFragment : Fragment() {

    private val viewModel: AccountsReceivableViewModel by viewModels()
    private lateinit var adapter: OutstandingInvoiceAdapter

    /** Body view of the invoice-details dialog, held so the async payment history can fill it. */
    private var invoiceDetailsView: View? = null

    /** Last list received from the ViewModel, kept so the search box can filter without a re-query. */
    private var allInvoices: List<OutstandingInvoice> = emptyList()
    private var searchQuery: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_accounts_receivable, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.loadOutstandingInvoices()

        adapter = OutstandingInvoiceAdapter(
            emptyList(),
            onRecordPayment = { invoice -> showPaymentDialog(invoice) },
            onViewDetails = { invoice -> showInvoiceDetails(invoice) }
        )
        view.findViewById<RecyclerView>(R.id.rv_outstanding).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@AccountsReceivableFragment.adapter
        }

        val etSearch = view.findViewById<EditText>(R.id.et_search)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                // Filter the list we already hold. Re-querying on every keystroke reloaded
                // every sale in the database and made typing feel laggy.
                applyFilter()
            }
        })

        viewModel.outstandingInvoices.observe(viewLifecycleOwner) { invoices ->
            allInvoices = invoices
            applyFilter()
        }

        // Payment history is fetched *after* the dialog is already on screen, so it is pushed
        // into the held view instead of being baked into the dialog at build time.
        viewModel.selectedPayments.observe(viewLifecycleOwner) { payments ->
            val target = invoiceDetailsView?.findViewById<TextView>(R.id.tv_payment_history)
                ?: return@observe
            target.text = if (payments.isEmpty()) {
                getString(R.string.msg_no_payments)
            } else {
                payments.joinToString("\n\n") { payment ->
                    val head = "${DateFormatter.formatDate(payment.paymentDate)}  " +
                        CurrencyFormatter.format(payment.amount)
                    if (payment.notes.isBlank()) head else "$head\n${payment.notes}"
                }
            }
        }
    }

    private fun applyFilter() {
        val query = searchQuery.trim().lowercase()
        val filtered = if (query.isBlank()) {
            allInvoices
        } else {
            allInvoices.filter {
                it.sale.invoiceNumber.lowercase().contains(query) ||
                    it.sale.customerName.lowercase().contains(query)
            }
        }
        adapter.submitList(filtered)
    }

    private fun showPaymentDialog(invoice: OutstandingInvoice) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_payment, null)

        dialogView.findViewById<TextView>(R.id.tv_balance_display).text = buildString {
            append(getString(R.string.label_invoice)).append(": ").append(invoice.sale.invoiceNumber)
            append('\n')
            append(getString(R.string.label_customer)).append(": ").append(invoice.sale.customerName)
            append('\n')
            append(getString(R.string.label_balance)).append(": ")
            append(CurrencyFormatter.format(invoice.balanceDue))
        }

        // The layout ships a neutral fallback prefix; show the shop's real currency instead.
        dialogView.findViewById<TextInputLayout>(R.id.til_payment_amount).prefixText =
            CurrencyFormatter.symbol()

        // Positive button is wired by hand: the default handler dismisses unconditionally,
        // which would throw away a typo'd amount instead of letting the user correct it.
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.title_record_payment)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_confirm, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val amountStr = dialogView.findViewById<EditText>(R.id.et_payment_amount).text.toString()
                val notes = dialogView.findViewById<EditText>(R.id.et_payment_notes).text.toString()
                val amount = amountStr.toDoubleOrNull()
                if (amount == null || amount <= 0 || amount > invoice.balanceDue) {
                    toast(
                        getString(
                            R.string.msg_invalid_payment_amount,
                            CurrencyFormatter.format(invoice.balanceDue)
                        )
                    )
                    return@setOnClickListener
                }
                viewModel.recordPayment(invoice.sale.id, amount, notes)
                toast(getString(R.string.msg_payment_recorded))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showInvoiceDetails(invoice: OutstandingInvoice) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_invoice_details, null)

        dialogView.findViewById<TextView>(R.id.tv_invoice_summary).text = buildString {
            append(getString(R.string.label_customer)).append(": ").append(invoice.sale.customerName)
            append('\n')
            append(getString(R.string.label_date)).append(": ")
            append(DateFormatter.formatDateTime(invoice.sale.saleDate))
            append('\n')
            append(getString(R.string.label_subtotal)).append(": ")
            append(CurrencyFormatter.format(invoice.sale.subtotal))
            append('\n')
            append(getString(R.string.label_tax)).append(": ")
            append(CurrencyFormatter.format(invoice.sale.taxAmount))
            append('\n')
            append(getString(R.string.label_total)).append(": ")
            append(CurrencyFormatter.format(invoice.sale.totalAmount))
            append('\n')
            append(getString(R.string.label_amount_paid)).append(": ")
            append(CurrencyFormatter.format(invoice.totalPaid))
            append('\n')
            append(getString(R.string.label_balance)).append(": ")
            append(CurrencyFormatter.format(invoice.balanceDue))
        }

        invoiceDetailsView = dialogView

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("${getString(R.string.label_invoice)} ${invoice.sale.invoiceNumber}")
            .setView(dialogView)
            .setPositiveButton(R.string.btn_close, null)
            .setNegativeButton(R.string.btn_record_payment) { _, _ -> showPaymentDialog(invoice) }
            .create()

        // Drop the reference on dismiss so a late emission cannot write into a dead view.
        dialog.setOnDismissListener { invoiceDetailsView = null }
        dialog.show()

        // Loaded last: the observer needs invoiceDetailsView to already be populated.
        viewModel.loadPayments(invoice.sale.id)
    }
}