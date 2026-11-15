package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Return
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter

class ReturnAdapter(
    private val onClick: (Return) -> Unit
) : RecyclerView.Adapter<ReturnAdapter.ReturnViewHolder>() {

    private val items = mutableListOf<Return>()

    class ReturnViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNumber: TextView = view.findViewById(R.id.tv_return_number)
        val tvReason: TextView = view.findViewById(R.id.tv_return_reason)
        val tvDate: TextView = view.findViewById(R.id.tv_return_date)
        val tvAmount: TextView = view.findViewById(R.id.tv_return_amount)
    }

    fun submitList(returns: List<Return>) {
        items.clear()
        items.addAll(returns)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReturnViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_return, parent, false)
        return ReturnViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReturnViewHolder, position: Int) {
        val item = items[position]
        holder.tvNumber.text = item.returnNumber
        holder.tvReason.text = item.reason.ifBlank { item.invoiceNumber }
        holder.tvDate.text = DateFormatter.formatDateTime(item.createdAt)
        holder.tvAmount.text = "-" + CurrencyFormatter.format(item.totalRefund)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
