package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.SaleItem
import com.example.lumipos.util.CurrencyFormatter

class SaleItemAdapter(
    private var items: List<SaleItem>
) : RecyclerView.Adapter<SaleItemAdapter.ViewHolder>() {

    fun submitList(newItems: List<SaleItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameView: TextView = itemView.findViewById(R.id.tv_product_name)
        val qtyView: TextView = itemView.findViewById(R.id.tv_item_qty)
        val totalView: TextView = itemView.findViewById(R.id.tv_line_total)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sale_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.nameView.text = item.productName
        holder.qtyView.text = "${item.quantity} x ${CurrencyFormatter.formatPlain(item.unitPrice)}"
        holder.totalView.text = CurrencyFormatter.format(item.totalPrice)
    }

    override fun getItemCount(): Int = items.size
}