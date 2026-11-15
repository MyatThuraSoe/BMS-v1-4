package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Product
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.Extensions.applyStatusTint

class StockProductAdapter(
    private var products: List<Product>,
    private val onAdjust: (Product) -> Unit
) : RecyclerView.Adapter<StockProductAdapter.ViewHolder>() {

    fun submitList(newProducts: List<Product>) {
        products = newProducts
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameView: TextView = itemView.findViewById(R.id.tv_product_name)
        val metaView: TextView = itemView.findViewById(R.id.tv_product_meta)
        val qtyView: TextView = itemView.findViewById(R.id.tv_stock_qty)
        val valueView: TextView = itemView.findViewById(R.id.tv_stock_value)
        val statusView: TextView = itemView.findViewById(R.id.tv_stock_status)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_stock_product, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val product = products[position]
        val context = holder.itemView.context
        holder.nameView.text = product.name
        holder.metaView.text = "${product.sku} · ${product.categoryName}"
        holder.qtyView.text = context.getString(R.string.format_units, product.stockQuantity)
        holder.valueView.text =
            context.getString(R.string.format_value, CurrencyFormatter.format(product.stockValue))
        holder.statusView.text = when (product.stockStatus) {
            "OUT_OF_STOCK" -> context.getString(R.string.label_out_of_stock)
            "LOW_STOCK" -> context.getString(R.string.label_low_stock)
            else -> context.getString(R.string.label_in_stock)
        }
        holder.statusView.applyStatusTint(product.stockStatus)
        holder.itemView.setOnClickListener { onAdjust(product) }
    }

    override fun getItemCount(): Int = products.size
}