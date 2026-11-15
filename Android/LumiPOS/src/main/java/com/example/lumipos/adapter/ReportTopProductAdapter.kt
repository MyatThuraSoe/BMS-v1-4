package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.ui.reports.TopProductEntry
import com.example.lumipos.util.CurrencyFormatter

class ReportTopProductAdapter(
    private var items: List<TopProductEntry>
) : RecyclerView.Adapter<ReportTopProductAdapter.ViewHolder>() {

    fun submitList(newItems: List<TopProductEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameView: TextView = itemView.findViewById(R.id.tv_product_name)
        val detailView: TextView = itemView.findViewById(R.id.tv_product_detail)
        val revenueView: TextView = itemView.findViewById(R.id.tv_product_revenue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_report_top_product, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.nameView.text = item.productName
        holder.detailView.text = "${item.totalQuantitySold} items sold"
        holder.revenueView.text = CurrencyFormatter.format(item.totalRevenue)
    }

    override fun getItemCount(): Int = items.size
}