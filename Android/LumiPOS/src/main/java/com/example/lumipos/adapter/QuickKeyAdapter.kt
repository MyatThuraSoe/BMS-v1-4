package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Product
import com.google.android.material.button.MaterialButton

class QuickKeyAdapter(
    private val items: List<Product>,
    private val onClick: (Product) -> Unit
) : RecyclerView.Adapter<QuickKeyAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val btn: MaterialButton = view.findViewById(R.id.btn_quick_key)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_quick_key, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val product = items[position]
        holder.btn.text = product.name.take(20)
        holder.btn.setOnClickListener { onClick(product) }
    }

    override fun getItemCount() = items.size
}
