package com.example.lumipos.ui.sales

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.adapter.SaleItemAdapter
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter
import com.example.lumipos.util.Extensions.applyStatusTint
import com.example.lumipos.util.Extensions.toast
import com.google.android.material.button.MaterialButton

class SaleDetailFragment : Fragment() {

    private val viewModel: SaleDetailViewModel by viewModels()

    /**
     * Voiding lives on [SalesViewModel] rather than the detail ViewModel so that the Sales list -
     * which observes the same instance - repaints the moment a sale is voided.
     */
    private val salesViewModel: SalesViewModel by lazy {
        ViewModelProvider(this)[SalesViewModel::class.java]
    }

    private lateinit var itemAdapter: SaleItemAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_sale_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val saleId = arguments?.getLong("saleId", -1L) ?: -1L
        if (saleId > 0) {
            viewModel.loadSale(saleId)
        } else {
            // Previously this rendered a blank screen with no explanation.
            toast(getString(R.string.msg_sale_not_found))
        }

        itemAdapter = SaleItemAdapter(emptyList())
        view.findViewById<RecyclerView>(R.id.rv_items).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = itemAdapter
        }

        view.findViewById<MaterialButton>(R.id.btn_back).setOnClickListener {
            findNavController().popBackStack()
        }

        view.findViewById<MaterialButton>(R.id.btn_receipt).setOnClickListener {
            val sale = viewModel.sale.value ?: return@setOnClickListener
            val bundle = Bundle().apply { putLong("saleId", sale.id) }
            findNavController().navigate(R.id.receiptPreviewFragment, bundle)
        }

        view.findViewById<MaterialButton>(R.id.btn_return).setOnClickListener {
            val sale = viewModel.sale.value ?: return@setOnClickListener
            if (sale.isVoided) {
                toast(getString(R.string.msg_sale_already_voided))
                return@setOnClickListener
            }
            val bundle = Bundle().apply { putLong("saleId", sale.id) }
            findNavController().navigate(R.id.returnFormFragment, bundle)
        }

        view.findViewById<MaterialButton>(R.id.btn_void_sale).setOnClickListener {
            val sale = viewModel.sale.value ?: return@setOnClickListener
            if (sale.isVoided) {
                toast(getString(R.string.msg_sale_already_voided))
                return@setOnClickListener
            }
            // Voiding is a manager/admin action; a cashier must call one over for a PIN.
            com.example.lumipos.util.PermissionGate.require(
                this,
                com.example.lumipos.util.Permissions.Permission.VOID_SALE
            ) {
                showVoidDialog(sale)
            }
        }

        viewModel.sale.observe(viewLifecycleOwner) { sale ->
            sale?.let { bindSale(view, it) }
        }

        viewModel.saleItems.observe(viewLifecycleOwner) { items ->
            itemAdapter.submitList(items)
        }

        salesViewModel.voidSuccess.observe(viewLifecycleOwner) { success ->
            if (success == true) {
                toast(getString(R.string.msg_sale_voided))
                findNavController().popBackStack()
            }
        }
    }

    /**
     * Fills the money rows.
     *
     * Each row already carries its own label in the layout, so these must be bare values - the
     * previous version prefixed them again and every row read "Subtotal    Subtotal: 3,000.00".
     */
    private fun bindSale(view: View, sale: Sale) {
        view.findViewById<TextView>(R.id.tv_invoice_number).text = sale.invoiceNumber
        view.findViewById<TextView>(R.id.tv_sale_date).text = DateFormatter.formatDateTime(sale.saleDate)
        view.findViewById<TextView>(R.id.tv_cashier_section).text =
            getString(R.string.format_cashier_label, sale.cashierName)
        view.findViewById<TextView>(R.id.tv_customer_section).text =
            getString(R.string.format_customer_label, sale.customerName)

        val statusView = view.findViewById<TextView>(R.id.tv_sale_status_chip)
        val statusLabel = if (sale.isVoided) getString(R.string.status_voided) else sale.paymentStatus
        statusView.text = statusLabel
        statusView.applyStatusTint(statusLabel)

        view.findViewById<TextView>(R.id.tv_subtotal).text = CurrencyFormatter.format(sale.subtotal)
        view.findViewById<TextView>(R.id.tv_tax).text = CurrencyFormatter.format(sale.taxAmount)
        view.findViewById<TextView>(R.id.tv_discount).text = CurrencyFormatter.format(sale.discountAmount)
        view.findViewById<TextView>(R.id.tv_total).text = CurrencyFormatter.format(sale.totalAmount)
        view.findViewById<TextView>(R.id.tv_amount_paid).text = CurrencyFormatter.format(sale.amountPaid)

        // tv_change had an id in the layout but was never written to, so the row sat empty forever.
        val change = (sale.amountPaid - sale.totalAmount).coerceAtLeast(0.0)
        setMoneyRow(view, R.id.tv_change, change, change > 0.0)

        // Balance and change are mutually exclusive: whichever way the till was off, one of them
        // is zero and the empty row just adds noise.
        val balance = (sale.totalAmount - sale.amountPaid).coerceAtLeast(0.0)
        setMoneyRow(view, R.id.tv_balance, balance, balance > 0.0)
    }

    private fun setMoneyRow(view: View, valueId: Int, amount: Double, visible: Boolean) {
        val value = view.findViewById<TextView>(valueId)
        value.text = CurrencyFormatter.format(amount)
        // The value's parent is the label/value row; hiding the parent hides the label with it.
        (value.parent as? View)?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showVoidDialog(sale: Sale) {
        val reasonInput = EditText(requireContext()).apply {
            hint = getString(R.string.hint_void_reason)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_confirm_void)
            .setMessage(getString(R.string.msg_void_invoice_confirm, sale.invoiceNumber))
            .setView(reasonInput)
            .setPositiveButton(R.string.btn_void, null) // wired below so it can veto the dismiss
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        // A plain positive-button handler always dismisses, which threw the typed reason away and
        // closed the dialog even when the field was empty - so the sale was never voided and the
        // user got a toast on a screen that had already moved on.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val reason = reasonInput.text.toString().trim()
                if (reason.isBlank()) {
                    reasonInput.error = getString(R.string.msg_void_reason_required)
                } else {
                    salesViewModel.voidSale(sale.id, reason)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
