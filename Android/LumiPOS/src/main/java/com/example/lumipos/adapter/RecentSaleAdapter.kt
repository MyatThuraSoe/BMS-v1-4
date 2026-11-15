package com.example.lumipos.adapter

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter

class RecentSaleAdapter(
    private var sales: List<Sale>,
    private val onClick: (Sale) -> Unit
) : RecyclerView.Adapter<RecentSaleAdapter.ViewHolder>() {

    fun submitList(newSales: List<Sale>) {
        sales = newSales
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val invoiceView: TextView = itemView.findViewById(R.id.tv_recent_invoice)
        val dateView: TextView = itemView.findViewById(R.id.tv_recent_date)
        val amountView: TextView = itemView.findViewById(R.id.tv_recent_amount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent_sale, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sale = sales[position]
        holder.invoiceView.text = sale.invoiceNumber
        holder.dateView.text = DateFormatter.formatDateTime(sale.saleDate)
        holder.amountView.text = CurrencyFormatter.format(sale.totalAmount)
        holder.itemView.setOnClickListener { onClick(sale) }
    }

    override fun getItemCount(): Int = sales.size
}