package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Product
import com.example.lumipos.util.CurrencyFormatter
import android.graphics.BitmapFactory

class ProductPosAdapter(
    private val onClick: (Product) -> Unit
) : PagingDataAdapter<Product, ProductPosAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Product>() {
            override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean =
                oldItem.name == newItem.name &&
                    oldItem.unitPrice == newItem.unitPrice &&
                    oldItem.stockQuantity == newItem.stockQuantity &&
                    oldItem.minStockLevel == newItem.minStockLevel &&
                    oldItem.expiryDate == newItem.expiryDate &&
                    oldItem.isBundle == newItem.isBundle &&
                    oldItem.imageBytes === newItem.imageBytes
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.iv_product_image)
        val nameView: TextView = itemView.findViewById(R.id.tv_product_name)
        val priceView: TextView = itemView.findViewById(R.id.tv_product_price)
        val stockView: TextView = itemView.findViewById(R.id.tv_stock_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_product_pos, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val product = getItem(position) ?: return
        holder.nameView.text = product.name
        holder.priceView.text = CurrencyFormatter.format(product.unitPrice)

        if (product.imageBytes != null) {
            holder.imageView.setImageBitmap(BitmapFactory.decodeByteArray(product.imageBytes, 0, product.imageBytes.size))
        } else {
            holder.imageView.setImageResource(R.drawable.ic_products)
            holder.imageView.setColorFilter(ContextCompat.getColor(holder.itemView.context, R.color.text_hint))
        }

        if (product.stockQuantity <= 0) {
            holder.stockView.text = "OUT OF STOCK"
            holder.stockView.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.error))
            holder.itemView.alpha = 0.55f
        } else if (product.stockQuantity <= product.minStockLevel) {
            holder.stockView.text = "${product.stockQuantity} left"
            holder.stockView.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.status_pending))
            holder.itemView.alpha = 1f
        } else {
            holder.stockView.text = "In stock: ${product.stockQuantity}"
            holder.stockView.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.status_paid))
            holder.itemView.alpha = 1f
        }

        holder.itemView.setOnClickListener { onClick(product) }
    }
}
