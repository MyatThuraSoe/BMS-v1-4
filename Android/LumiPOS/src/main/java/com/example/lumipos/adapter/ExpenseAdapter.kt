package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Expense
import com.example.lumipos.util.CurrencyFormatter
import com.example.lumipos.util.DateFormatter
import com.example.lumipos.util.ExpenseCategories

class ExpenseAdapter(
    private val onEdit: (Expense) -> Unit,
    private val onDelete: (Expense) -> Unit
) : ListAdapter<Expense, ExpenseAdapter.ExpenseViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExpenseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_expense, parent, false)
        return ExpenseViewHolder(view)
    }

    override fun onBindViewHolder(holder: ExpenseViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ExpenseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val description: TextView = itemView.findViewById(R.id.tv_expense_description)
        private val meta: TextView = itemView.findViewById(R.id.tv_expense_meta)
        private val amount: TextView = itemView.findViewById(R.id.tv_expense_amount)
        private val receiptBadge: ImageView = itemView.findViewById(R.id.iv_expense_receipt)
        private val editButton: ImageButton = itemView.findViewById(R.id.btn_edit)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.btn_delete)

        fun bind(expense: Expense) {
            val context = itemView.context

            description.text = expense.description.ifBlank {
                context.getString(R.string.expense_untitled)
            }
            meta.text = context.getString(
                R.string.format_expense_meta,
                ExpenseCategories.label(context, expense.category),
                DateFormatter.formatDate(expense.expenseDate)
            )
            amount.text = CurrencyFormatter.format(expense.amount)

            // Marks rows that carry a receipt photo, so the list shows which expenses are
            // documented without opening each one.
            receiptBadge.visibility =
                if (expense.receiptImageBytes != null) View.VISIBLE else View.GONE

            editButton.setOnClickListener { onEdit(expense) }
            deleteButton.setOnClickListener { onDelete(expense) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Expense>() {
            override fun areItemsTheSame(oldItem: Expense, newItem: Expense) =
                oldItem.id == newItem.id

            // Expense.equals compares id/description/amount only, which is exactly the set the
            // row renders - editing a category or date alone would not repaint. Compare those too.
            override fun areContentsTheSame(oldItem: Expense, newItem: Expense) =
                oldItem.id == newItem.id &&
                    oldItem.description == newItem.description &&
                    oldItem.amount == newItem.amount &&
                    oldItem.category == newItem.category &&
                    oldItem.expenseDate == newItem.expenseDate &&
                    (oldItem.receiptImageBytes == null) == (newItem.receiptImageBytes == null)
        }
    }
}
