package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Category

class CategoryAdapter(
    private var categories: List<Category>,
    private val onEdit: (Category) -> Unit,
    private val onDelete: (Category) -> Unit,
    private val productCountProvider: (Long) -> Int
) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

    fun submitList(newCategories: List<Category>) {
        categories = newCategories
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameView: TextView = itemView.findViewById(R.id.tv_category_name)
        val metaView: TextView = itemView.findViewById(R.id.tv_category_meta)
        val countView: TextView = itemView.findViewById(R.id.tv_category_products)
        val btnEdit: ImageButton = itemView.findViewById(R.id.btn_edit)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = categories[position]
        holder.nameView.text = category.name
        holder.metaView.text = category.description.ifBlank { "No description" }
        holder.countView.text = "${productCountProvider(category.id)} products"

        holder.btnEdit.setOnClickListener { onEdit(category) }
        holder.btnDelete.setOnClickListener { onDelete(category) }
    }

    override fun getItemCount(): Int = categories.size
}