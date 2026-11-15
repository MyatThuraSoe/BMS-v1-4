package com.example.lumipos.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.ui.pos.CartItem
import com.example.lumipos.util.CurrencyFormatter

class CartAdapter(
    private val context: Context,
    private val onQuantityChanged: (Long, Int) -> Unit,
    private val onRemove: (Long) -> Unit,
    private val onLineDiscount: (CartItem) -> Unit = {}
) : RecyclerView.Adapter<CartAdapter.ViewHolder>() {

    private var items: List<CartItem> = emptyList()

    fun submitList(newItems: List<CartItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameView: TextView = itemView.findViewById(R.id.tv_item_name)
        val priceView: TextView = itemView.findViewById(R.id.tv_item_price)
        val discountView: TextView = itemView.findViewById(R.id.tv_item_discount)
        val qtyView: EditText = itemView.findViewById(R.id.et_qty)
        val btnDecrease: ImageButton = itemView.findViewById(R.id.btn_decrease)
        val btnIncrease: ImageButton = itemView.findViewById(R.id.btn_increase)
        val lineTotal: TextView = itemView.findViewById(R.id.tv_line_total)
        val btnRemove: ImageButton = itemView.findViewById(R.id.btn_remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cart, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.nameView.text = item.name
        holder.priceView.text = "${CurrencyFormatter.formatPlain(item.price)} x ${item.quantity}"
        holder.qtyView.setText(item.quantity.toString())
        holder.lineTotal.text = CurrencyFormatter.format(item.netTotal)

        // A line discount shows beneath the name so the cashier can see why the line total is
        // lower than price x qty.
        holder.discountView.visibility = if (item.manualDiscount > 0.0) View.VISIBLE else View.GONE
        holder.discountView.text = context.getString(R.string.format_line_discount, CurrencyFormatter.format(item.manualDiscount))

        holder.itemView.setOnLongClickListener {
            onLineDiscount(item)
            true
        }

        holder.btnIncrease.setOnClickListener {
            holder.qtyView.setText((item.quantity + 1).toString())
            onQuantityChanged(item.productId, item.quantity + 1)
        }

        holder.btnDecrease.setOnClickListener {
            holder.qtyView.setText((item.quantity - 1).toString())
            onQuantityChanged(item.productId, item.quantity - 1)
        }

        holder.qtyView.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = holder.qtyView.text.toString()
                val qty = text.toIntOrNull() ?: item.quantity
                onQuantityChanged(item.productId, qty)
            }
        }

        holder.btnRemove.setOnClickListener { onRemove(item.productId) }
    }

    override fun getItemCount(): Int = items.size
}