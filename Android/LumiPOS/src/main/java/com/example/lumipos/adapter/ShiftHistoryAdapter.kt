package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Shift
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter
import com.example.lumipos.util.Extensions.applyStatusTint

enum class ShiftHistoryMode { ALL, RECENT }

class ShiftHistoryAdapter(
    private var shifts: List<Shift>
) : RecyclerView.Adapter<ShiftHistoryAdapter.ViewHolder>() {

    fun submitList(newShifts: List<Shift>) {
        shifts = newShifts
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val dateView: TextView = itemView.findViewById(R.id.tv_shift_date)
        val metaView: TextView = itemView.findViewById(R.id.tv_shift_meta)
        val totalView: TextView = itemView.findViewById(R.id.tv_shift_total)
        val statusView: TextView = itemView.findViewById(R.id.tv_shift_status_chip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_shift, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val shift = shifts[position]
        val context = holder.itemView.context
        holder.dateView.text = DateFormatter.formatDateTime(shift.openingTime)
        holder.metaView.text = buildString {
            append(shift.cashierName)
            if (shift.closingTime != null) {
                append(context.getString(R.string.format_closed_at, DateFormatter.formatTime(shift.closingTime)))
            }
        }
        holder.totalView.text = CurrencyFormatter.format(shift.expectedAmount ?: shift.openingAmount)

        val isOpen = shift.status == "OPEN"
        holder.statusView.text =
            context.getString(if (isOpen) R.string.label_shift_open else R.string.label_shift_closed)
        holder.statusView.applyStatusTint(shift.status)
    }

    override fun getItemCount(): Int = shifts.size
}