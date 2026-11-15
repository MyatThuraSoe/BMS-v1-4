package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.ProductBundleItem

class BundleComponentAdapter(
    private val onRemove: (ProductBundleItem) -> Unit
) : RecyclerView.Adapter<BundleComponentAdapter.ComponentViewHolder>() {

    private val items = mutableListOf<ProductBundleItem>()

    class ComponentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_component_name)
        val tvQty: TextView = view.findViewById(R.id.tv_component_qty)
        val btnRemove: ImageButton = view.findViewById(R.id.btn_remove_component)
    }

    fun submitList(list: List<ProductBundleItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ComponentViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bundle_component, parent, false)
        return ComponentViewHolder(view)
    }

    override fun onBindViewHolder(holder: ComponentViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.componentName
        holder.tvQty.text = "x${item.quantity}"
        holder.btnRemove.setOnClickListener { onRemove(item) }
    }

    override fun getItemCount() = items.size
}
