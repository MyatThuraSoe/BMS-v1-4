package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.StockTake
import com.example.lumipos.util.DateFormatter

class StockTakeAdapter(
    private val onClick: (StockTake) -> Unit,
    private val onDelete: (StockTake) -> Unit
) : RecyclerView.Adapter<StockTakeAdapter.TakeViewHolder>() {

    private val items = mutableListOf<StockTake>()

    class TakeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNumber: TextView = view.findViewById(R.id.tv_take_number)
        val tvDetail: TextView = view.findViewById(R.id.tv_take_detail)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_take)
    }

    fun submitList(list: List<StockTake>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TakeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_stock_take, parent, false)
        return TakeViewHolder(view)
    }

    override fun onBindViewHolder(holder: TakeViewHolder, position: Int) {
        val take = items[position]
        holder.tvNumber.text = take.countNumber
        holder.tvDetail.text = "${take.status} - ${DateFormatter.formatDateTime(take.startedAt)}"
        holder.itemView.setOnClickListener { onClick(take) }
        holder.btnDelete.setOnClickListener { onDelete(take) }
    }

    override fun getItemCount() = items.size
}
