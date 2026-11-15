package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.StockMovement
import com.example.lumipos.util.DateFormatter

class StockMovementAdapter(
    private var movements: List<StockMovement>
) : RecyclerView.Adapter<StockMovementAdapter.ViewHolder>() {

    fun submitList(newMovements: List<StockMovement>) {
        movements = newMovements
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val productView: TextView = itemView.findViewById(R.id.tv_movement_product)
        val metaView: TextView = itemView.findViewById(R.id.tv_movement_meta)
        val qtyView: TextView = itemView.findViewById(R.id.tv_movement_qty)
        val dateView: TextView = itemView.findViewById(R.id.tv_movement_date)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_stock_movement, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val movement = movements[position]
        holder.productView.text = movement.productName
        holder.metaView.text = buildString {
            append(movement.movementType)
            if (movement.referenceType.isNotBlank()) append(" · ${movement.referenceType}")
            if (movement.description.isNotBlank()) append(" · ${movement.description}")
        }
        holder.qtyView.text = if (movement.quantity > 0) "+${movement.quantity}" else movement.quantity.toString()
        holder.qtyView.setTextColor(
            when (movement.movementType) {
                "IN", "ADJUSTMENT_IN" -> holder.itemView.context.getColor(R.color.status_paid)
                else -> holder.itemView.context.getColor(R.color.status_void)
            }
        )
        holder.dateView.text = DateFormatter.formatDateTime(movement.movementDate)
    }

    override fun getItemCount(): Int = movements.size
}