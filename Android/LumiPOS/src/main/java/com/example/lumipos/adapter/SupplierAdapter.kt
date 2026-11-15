package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Supplier

class SupplierAdapter(
    private var suppliers: List<Supplier>,
    private val onEdit: (Supplier) -> Unit,
    private val onDelete: (Supplier) -> Unit
) : RecyclerView.Adapter<SupplierAdapter.ViewHolder>() {

    fun submitList(newSuppliers: List<Supplier>) {
        suppliers = newSuppliers
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameView: TextView = itemView.findViewById(R.id.tv_supplier_name)
        val metaView: TextView = itemView.findViewById(R.id.tv_supplier_meta)
        val btnEdit: ImageButton = itemView.findViewById(R.id.btn_edit)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_supplier, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val supplier = suppliers[position]
        holder.nameView.text = supplier.name
        holder.metaView.text = buildString {
            if (supplier.contactPerson.isNotBlank()) append(supplier.contactPerson)
            if (supplier.phone.isNotBlank()) append(" · ${supplier.phone}")
        }

        holder.btnEdit.setOnClickListener { onEdit(supplier) }
        holder.btnDelete.setOnClickListener { onDelete(supplier) }
    }

    override fun getItemCount(): Int = suppliers.size
}