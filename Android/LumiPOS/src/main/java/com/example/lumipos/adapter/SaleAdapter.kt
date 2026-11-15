package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter
import com.example.lumipos.util.Extensions.applyStatusTint

class SaleAdapter(
    private val onClick: (Sale) -> Unit
) : PagingDataAdapter<Sale, SaleAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Sale>() {
            override fun areItemsTheSame(oldItem: Sale, newItem: Sale): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Sale, newItem: Sale): Boolean =
                oldItem == newItem
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val invoiceView: TextView = itemView.findViewById(R.id.tv_invoice_number)
        val customerView: TextView = itemView.findViewById(R.id.tv_sale_customer)
        val dateView: TextView = itemView.findViewById(R.id.tv_sale_date)
        val totalView: TextView = itemView.findViewById(R.id.tv_sale_total)
        val statusView: TextView = itemView.findViewById(R.id.tv_sale_status)
        val paidView: TextView = itemView.findViewById(R.id.tv_sale_paid)
        val btnView: ImageButton = itemView.findViewById(R.id.btn_view)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sale, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sale = getItem(position) ?: return
        val context = holder.itemView.context
        holder.invoiceView.text = sale.invoiceNumber
        holder.customerView.text = sale.customerName
        holder.dateView.text = DateFormatter.formatDateTime(sale.saleDate)
        holder.totalView.text = CurrencyFormatter.format(sale.totalAmount)

        val statusLabel = when {
            sale.isVoided -> context.getString(R.string.status_voided)
            sale.paymentStatus == "REFUNDED" -> context.getString(R.string.status_refunded)
            else -> sale.paymentStatus
        }
        holder.statusView.text = statusLabel
        // The chip used to be whatever colour the shared selector happened to resolve to, which was
        // always the same orange. VOIDED now actually looks different from PAID.
        holder.statusView.applyStatusTint(if (sale.isVoided) "VOIDED" else sale.paymentStatus)

        holder.paidView.text = context.getString(
            R.string.format_paid_amount,
            CurrencyFormatter.format(sale.amountPaid)
        )
        holder.btnView.setOnClickListener { onClick(sale) }
    }
}
