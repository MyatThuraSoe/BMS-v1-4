package com.example.lumipos.adapter

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.StockTakeItem

/**
 * Editable list of counted quantities for a stock take. Mirrors [ReturnLineAdapter]: a single
 * reused [TextWatcher] is detached before setting text so recycled rows cannot cross-contaminate.
 *
 * While [blind] is true the expected (system) quantity and variance are hidden so the counter is
 * not biased.
 */
class StockTakeLineAdapter(
    private val lines: MutableList<StockTakeItem>,
    private var blind: Boolean,
    private val onCounted: (itemId: Long, counted: Int?) -> Unit
) : RecyclerView.Adapter<StockTakeLineAdapter.LineViewHolder>() {

    class LineViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_line_name)
        val tvSystem: TextView = view.findViewById(R.id.tv_line_system)
        val tvVariance: TextView = view.findViewById(R.id.tv_line_variance)
        val etCounted: EditText = view.findViewById(R.id.et_counted)
        var watcher: TextWatcher? = null
    }

    fun setBlind(value: Boolean) {
        if (blind != value) {
            blind = value
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_stock_take_line, parent, false)
        return LineViewHolder(view)
    }

    override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
        val line = lines[position]
        val ctx = holder.itemView.context

        holder.tvName.text = line.productName
        holder.tvSystem.text = ctx.getString(R.string.format_system_qty, line.systemQuantity)

        val showSystem = !blind
        holder.tvSystem.visibility = if (showSystem) View.VISIBLE else View.GONE

        val variance = line.variance
        if (showSystem && variance != null) {
            holder.tvVariance.visibility = View.VISIBLE
            holder.tvVariance.text = ctx.getString(R.string.format_variance, variance)
            holder.tvVariance.setTextColor(
                ctx.getColor(
                    when {
                        variance > 0 -> R.color.status_paid
                        variance < 0 -> R.color.error
                        else -> R.color.text_secondary
                    }
                )
            )
        } else {
            holder.tvVariance.visibility = View.GONE
        }

        holder.watcher?.let { holder.etCounted.removeTextChangedListener(it) }
        holder.etCounted.setText(line.countedQuantity?.toString() ?: "")
        holder.etCounted.isEnabled = blind

        val newWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                val counted = if (text.isBlank()) null else text.toIntOrNull()
                val updated = line.copy(countedQuantity = counted)
                lines[position] = updated
                onCounted(line.id, counted)
                if (!blind) {
                    val v = updated.variance
                    if (v != null) {
                        holder.tvVariance.visibility = View.VISIBLE
                        holder.tvVariance.text = ctx.getString(R.string.format_variance, v)
                        holder.tvVariance.setTextColor(
                            ctx.getColor(
                                when {
                                    v > 0 -> R.color.status_paid
                                    v < 0 -> R.color.error
                                    else -> R.color.text_secondary
                                }
                            )
                        )
                    } else {
                        holder.tvVariance.visibility = View.GONE
                    }
                }
            }
        }
        holder.watcher = newWatcher
        holder.etCounted.addTextChangedListener(newWatcher)
    }

    override fun getItemCount() = lines.size
}
