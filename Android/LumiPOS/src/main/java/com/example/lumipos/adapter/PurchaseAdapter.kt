package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Purchase
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter

class PurchaseAdapter(
    private var purchases: List<Purchase>,
    private val onPaymentStatusClick: (Purchase) -> Unit
) : RecyclerView.Adapter<PurchaseAdapter.ViewHolder>() {

    fun submitList(newPurchases: List<Purchase>) {
        purchases = newPurchases
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val numberView: TextView = itemView.findViewById(R.id.tv_purchase_number)
        val supplierView: TextView = itemView.findViewById(R.id.tv_purchase_supplier)
        val dateView: TextView = itemView.findViewById(R.id.tv_purchase_date)
        val totalView: TextView = itemView.findViewById(R.id.tv_purchase_total)
        val statusView: TextView = itemView.findViewById(R.id.tv_payment_status)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_purchase, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val purchase = purchases[position]
        holder.numberView.text = purchase.purchaseNumber
        holder.supplierView.text = purchase.supplierName
        holder.dateView.text = DateFormatter.formatDate(purchase.purchaseDate)
        holder.totalView.text = CurrencyFormatter.format(purchase.totalAmount)
        holder.statusView.text = purchase.paymentStatus
        holder.statusView.setOnClickListener { onPaymentStatusClick(purchase) }
    }

    override fun getItemCount(): Int = purchases.size
}