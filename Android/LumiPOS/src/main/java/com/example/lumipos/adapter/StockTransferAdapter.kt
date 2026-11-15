package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.StockTransfer
import com.example.lumipos.util.Extensions.applyStatusTint

class StockTransferAdapter(
    private val onClick: (StockTransfer) -> Unit,
    private val onDelete: (StockTransfer) -> Unit
) : RecyclerView.Adapter<StockTransferAdapter.TransferViewHolder>() {

    private val items = mutableListOf<StockTransfer>()

    class TransferViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNumber: TextView = view.findViewById(R.id.tv_transfer_number)
        val tvRoute: TextView = view.findViewById(R.id.tv_transfer_route)
        val tvStatus: TextView = view.findViewById(R.id.tv_transfer_status)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_transfer)
    }

    fun submitList(list: List<StockTransfer>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransferViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_stock_transfer, parent, false)
        return TransferViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransferViewHolder, position: Int) {
        val transfer = items[position]
        holder.tvNumber.text = transfer.transferNumber
        holder.tvRoute.text = "${transfer.fromLocation}  \u2192  ${transfer.toLocation}"
        holder.tvStatus.text = transfer.status
        holder.tvStatus.applyStatusTint(transfer.status)
        holder.itemView.setOnClickListener { onClick(transfer) }
        holder.btnDelete.setOnClickListener { onDelete(transfer) }
    }

    override fun getItemCount() = items.size
}
