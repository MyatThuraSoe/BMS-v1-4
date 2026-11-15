package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.ui.ar.OutstandingInvoice
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter
import com.example.lumipos.util.Extensions.applyStatusTint

class OutstandingInvoiceAdapter(
    private var invoices: List<OutstandingInvoice>,
    private val onRecordPayment: (OutstandingInvoice) -> Unit,
    private val onViewDetails: (OutstandingInvoice) -> Unit
) : RecyclerView.Adapter<OutstandingInvoiceAdapter.ViewHolder>() {

    fun submitList(newInvoices: List<OutstandingInvoice>) {
        invoices = newInvoices
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val invoiceView: TextView = itemView.findViewById(R.id.tv_invoice_number)
        val statusView: TextView = itemView.findViewById(R.id.tv_status_chip)
        val customerView: TextView = itemView.findViewById(R.id.tv_customer_name)
        val dueDateView: TextView = itemView.findViewById(R.id.tv_due_date)
        val balanceView: TextView = itemView.findViewById(R.id.tv_balance_due)
        val paidView: TextView = itemView.findViewById(R.id.tv_total_paid)
        val btnPayment: Button = itemView.findViewById(R.id.btn_record_payment)
        val btnDetails: Button = itemView.findViewById(R.id.btn_view_details)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_outstanding_invoice, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val invoice = invoices[position]
        val context = holder.itemView.context

        holder.invoiceView.text = invoice.sale.invoiceNumber
        holder.customerView.text = invoice.sale.customerName

        // Hidden rather than left blank: an empty TextView still consumed its top margin, which
        // left a visible gap in every row for a cash sale that has no due date.
        val dueDate = invoice.sale.dueDate
        holder.dueDateView.text = dueDate?.let { context.getString(R.string.format_due_date, DateFormatter.formatDate(it)) }.orEmpty()
        holder.dueDateView.visibility = if (dueDate == null) View.GONE else View.VISIBLE

        holder.balanceView.text = context.getString(
            R.string.format_balance_amount,
            CurrencyFormatter.format(invoice.balanceDue)
        )
        holder.paidView.text = context.getString(
            R.string.format_paid_amount,
            CurrencyFormatter.format(invoice.totalPaid)
        )

        val isOverdue = dueDate != null &&
            dueDate < System.currentTimeMillis() &&
            invoice.sale.paymentStatus != "PAID"
        val statusLabel = if (isOverdue) context.getString(R.string.status_overdue) else invoice.sale.paymentStatus
        holder.statusView.text = statusLabel
        holder.statusView.applyStatusTint(statusLabel)

        holder.btnPayment.setOnClickListener { onRecordPayment(invoice) }
        holder.btnDetails.setOnClickListener { onViewDetails(invoice) }
    }

    override fun getItemCount(): Int = invoices.size
}