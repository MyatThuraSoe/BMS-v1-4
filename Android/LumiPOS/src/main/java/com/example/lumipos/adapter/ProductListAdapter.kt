package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Product
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.Extensions.applyStatusTint

class ProductListAdapter(
    private val onEdit: (Product) -> Unit,
    private val onDelete: (Product) -> Unit
) : PagingDataAdapter<Product, ProductListAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Product>() {
            override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean =
                oldItem.name == newItem.name &&
                    oldItem.sku == newItem.sku &&
                    oldItem.categoryName == newItem.categoryName &&
                    oldItem.stockQuantity == newItem.stockQuantity &&
                    oldItem.unitPrice == newItem.unitPrice
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameView: TextView = itemView.findViewById(R.id.tv_product_name)
        val metaView: TextView = itemView.findViewById(R.id.tv_product_meta)
        val statusView: TextView = itemView.findViewById(R.id.tv_product_status)
        val priceView: TextView = itemView.findViewById(R.id.tv_product_price)
        val btnEdit: ImageButton = itemView.findViewById(R.id.btn_edit)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_product, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val product = getItem(position) ?: return
        val context = holder.itemView.context
        holder.nameView.text = product.name
        holder.metaView.text = context.getString(
            R.string.format_product_meta,
            product.sku,
            product.categoryName,
            product.stockQuantity
        )
        holder.priceView.text = CurrencyFormatter.format(product.unitPrice)

        holder.statusView.text = context.getString(
            when (product.stockStatus) {
                "OUT_OF_STOCK" -> R.string.label_out_of_stock
                "LOW_STOCK" -> R.string.label_low_stock
                else -> R.string.label_in_stock
            }
        )
        holder.statusView.applyStatusTint(product.stockStatus)

        holder.btnEdit.setOnClickListener { onEdit(product) }
        holder.btnDelete.setOnClickListener { onDelete(product) }
    }
}
