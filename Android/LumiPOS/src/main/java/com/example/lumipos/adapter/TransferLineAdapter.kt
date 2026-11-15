package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.StockTransferItem

class TransferLineAdapter(
    private val onRemove: ((StockTransferItem) -> Unit)? = null
) : RecyclerView.Adapter<TransferLineAdapter.LineViewHolder>() {

    private val items = mutableListOf<StockTransferItem>()

    class LineViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_transfer_line_name)
        val tvQty: TextView = view.findViewById(R.id.tv_transfer_line_qty)
        val btnRemove: ImageButton = view.findViewById(R.id.btn_transfer_line_remove)
    }

    fun submitList(list: List<StockTransferItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_transfer_line, parent, false)
        return LineViewHolder(view)
    }

    override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.productName
        holder.tvQty.text = "x${item.quantity}"
        if (onRemove != null) {
            holder.btnRemove.visibility = View.VISIBLE
            holder.btnRemove.setOnClickListener { onRemove.invoke(item) }
        } else {
            holder.btnRemove.visibility = View.GONE
            holder.btnRemove.setOnClickListener(null)
        }
    }

    override fun getItemCount() = items.size
}
