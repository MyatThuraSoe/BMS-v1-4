package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Customer
import com.example.lumipos.util.CurrencyFormatter

class CustomerAdapter(
    private var customers: List<Customer>,
    private val onEdit: (Customer) -> Unit,
    private val onDelete: (Customer) -> Unit
) : RecyclerView.Adapter<CustomerAdapter.ViewHolder>() {

    fun submitList(newCustomers: List<Customer>) {
        customers = newCustomers
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val initialsView: TextView = itemView.findViewById(R.id.tv_customer_initials)
        val nameView: TextView = itemView.findViewById(R.id.tv_customer_name)
        val metaView: TextView = itemView.findViewById(R.id.tv_customer_meta)
        val balanceView: TextView = itemView.findViewById(R.id.tv_customer_balance)
        val btnEdit: ImageButton = itemView.findViewById(R.id.btn_edit)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_customer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val customer = customers[position]
        val initials = customer.fullName.split(" ").take(2).map { it.firstOrNull() ?: '?' }.joinToString("")
        holder.initialsView.text = initials
        holder.nameView.text = customer.fullName
        holder.metaView.text = buildString {
            if (customer.phone.isNotBlank()) append(customer.phone)
            if (customer.city.isNotBlank()) append(" · ${customer.city}")
            if (customer.isQuickAdd) append(" · Quick add")
        }

        if (customer.currentBalance > 0) {
            holder.balanceView.text = "Due: ${CurrencyFormatter.format(customer.currentBalance)}"
            holder.balanceView.visibility = View.VISIBLE
        } else {
            holder.balanceView.visibility = View.GONE
        }

        holder.btnEdit.setOnClickListener { onEdit(customer) }
        holder.btnDelete.setOnClickListener { onDelete(customer) }
    }

    override fun getItemCount(): Int = customers.size
}