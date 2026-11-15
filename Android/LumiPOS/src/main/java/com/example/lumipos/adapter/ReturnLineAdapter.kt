package com.example.lumipos.adapter

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.ui.returns.ReturnableLine
import com.example.lumipos.util.CurrencyFormatter

/**
 * Editable list of returnable sale lines.
 *
 * Each row keeps a mutable [ReturnableLine] so the qty typed by the cashier survives
 * RecyclerView recycling. A single reused [TextWatcher] is detached before the text is set
 * on bind, otherwise the recycled row would write the previous line's quantity into this one.
 */
class ReturnLineAdapter(
    private val lines: MutableList<ReturnableLine>,
    private val onChange: () -> Unit
) : RecyclerView.Adapter<ReturnLineAdapter.LineViewHolder>() {

    class LineViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvProduct: TextView = view.findViewById(R.id.tv_return_product)
        val tvSold: TextView = view.findViewById(R.id.tv_return_sold)
        val tvPrice: TextView = view.findViewById(R.id.tv_return_price)
        val etQty: EditText = view.findViewById(R.id.et_return_qty)
        val cbRestock: CheckBox = view.findViewById(R.id.cb_restock)
        var watcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_return_line, parent, false)
        return LineViewHolder(view)
    }

    override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
        val line = lines[position]

        holder.tvProduct.text = line.saleItem.productName
        holder.tvSold.text = holder.itemView.context.getString(
            R.string.format_return_qty,
            line.saleItem.quantity,
            line.saleItem.quantityRefunded
        )
        holder.tvPrice.text = CurrencyFormatter.format(line.saleItem.unitPrice)

        // Detach the previous watcher before setting text: without this the recycled row would
        // run the old line's watcher against this line's value and cross-contaminate quantities.
        holder.watcher?.let { holder.etQty.removeTextChangedListener(it) }
        holder.etQty.setText(if (line.returnQty > 0) line.returnQty.toString() else "")
        holder.cbRestock.isChecked = line.restock

        val newWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val typed = s?.toString()?.toIntOrNull() ?: 0
                // Clamp to what is still returnable so an over-typed quantity can never refund
                // more units than were sold.
                val clamped = typed.coerceIn(0, line.returnable)
                if (clamped != typed) {
                    holder.etQty.setText(if (clamped > 0) clamped.toString() else "")
                }
                line.returnQty = clamped
                onChange()
            }
        }
        holder.watcher = newWatcher
        holder.etQty.addTextChangedListener(newWatcher)

        holder.cbRestock.setOnCheckedChangeListener { _, checked ->
            line.restock = checked
        }
        holder.cbRestock.isEnabled = line.returnable > 0
        holder.etQty.isEnabled = line.returnable > 0
    }

    override fun getItemCount() = lines.size
}
