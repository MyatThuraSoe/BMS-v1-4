package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.OrderItem
import com.example.lumipos.util.CurrencyFormatter

/**
 * Line items of a held order, shown in the order-detail dialog.
 *
 * Reuses `item_sale_item.xml` — an order line and a sale line carry the same three values
 * (product name, quantity, line total), so a second layout would be duplication.
 */
class OrderItemAdapter : RecyclerView.Adapter<OrderItemAdapter.ViewHolder>() {

    private var items: List<OrderItem> = emptyList()

    fun submitList(newItems: List<OrderItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameView: TextView = itemView.findViewById(R.id.tv_product_name)
        val qtyView: TextView = itemView.findViewById(R.id.tv_item_qty)
        val totalView: TextView = itemView.findViewById(R.id.tv_line_total)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sale_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.nameView.text = item.productName
        holder.qtyView.text = "x${item.quantity}"
        holder.totalView.text = CurrencyFormatter.format(item.totalPrice)
    }

    override fun getItemCount(): Int = items.size
}
