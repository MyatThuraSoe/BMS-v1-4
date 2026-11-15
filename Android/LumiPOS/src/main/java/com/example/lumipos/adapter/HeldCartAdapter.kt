package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.HeldCart
import java.text.SimpleDateFormat
import java.util.*

class HeldCartAdapter(
    private val items: List<HeldCart>,
    private val onSelect: (HeldCart) -> Unit
) : RecyclerView.Adapter<HeldCartAdapter.HeldCartViewHolder>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    class HeldCartViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCustomer: TextView = view.findViewById(R.id.tv_customer)
        val tvTime: TextView = view.findViewById(R.id.tv_time)
        val tvCount: TextView = view.findViewById(R.id.tv_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeldCartViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_held_cart, parent, false)
        return HeldCartViewHolder(view)
    }

    override fun onBindViewHolder(holder: HeldCartViewHolder, position: Int) {
        val item = items[position]
        holder.tvCustomer.text = item.customerName.ifBlank { "Walk-in" }
        holder.tvTime.text = dateFormat.format(Date(item.heldAt))
        holder.tvCount.text = "Items in cart"
        holder.itemView.setOnClickListener { onSelect(item) }
    }

    override fun getItemCount() = items.size
}
