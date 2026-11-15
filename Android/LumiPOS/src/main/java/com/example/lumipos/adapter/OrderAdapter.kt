package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Order
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter
import com.example.lumipos.util.Extensions.applyStatusTint

class OrderAdapter(
    private var orders: List<Order>,
    private val onClick: (Order) -> Unit
) : RecyclerView.Adapter<OrderAdapter.ViewHolder>() {

    fun submitList(newOrders: List<Order>) {
        orders = newOrders
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val numberView: TextView = itemView.findViewById(R.id.tv_order_number)
        val customerView: TextView = itemView.findViewById(R.id.tv_order_customer)
        val dateView: TextView = itemView.findViewById(R.id.tv_order_date)
        val totalView: TextView = itemView.findViewById(R.id.tv_order_total)
        val statusView: TextView = itemView.findViewById(R.id.tv_order_status_chip)
        val itemsView: TextView = itemView.findViewById(R.id.tv_order_items)
        val btnView: ImageButton = itemView.findViewById(R.id.btn_view_order)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_order, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val order = orders[position]
        holder.numberView.text = order.orderNumber
        holder.customerView.text = order.customerName
        holder.dateView.text = DateFormatter.formatDateTime(order.createdAt)
        holder.totalView.text = CurrencyFormatter.format(order.totalAmount)
        holder.statusView.text = order.status
        holder.statusView.applyStatusTint(order.status)
        holder.itemsView.text = holder.itemView.context.getString(R.string.format_items, order.itemCount)
        holder.btnView.setOnClickListener { onClick(order) }
    }

    override fun getItemCount(): Int = orders.size
}