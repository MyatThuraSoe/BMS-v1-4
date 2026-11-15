package com.example.lumipos.adapter

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Product

class LowStockAdapter(
    private var products: List<Product>,
    private val onClick: (Product) -> Unit
) : RecyclerView.Adapter<LowStockAdapter.ViewHolder>() {

    fun submitList(newProducts: List<Product>) {
        products = newProducts
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameView: TextView = itemView.findViewById(R.id.tv_product_name)
        val stockView: TextView = itemView.findViewById(R.id.tv_stock_info)
        val shortageView: TextView = itemView.findViewById(R.id.tv_shortage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_low_stock_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val product = products[position]
        holder.nameView.text = product.name
        holder.stockView.text = "Stock: ${product.stockQuantity} / Min: ${product.minStockLevel}"
        holder.shortageView.text = "Shortage: ${(product.minStockLevel - product.stockQuantity).coerceAtLeast(0)}"
        holder.itemView.setOnClickListener { onClick(product) }
    }

    override fun getItemCount(): Int = products.size
}