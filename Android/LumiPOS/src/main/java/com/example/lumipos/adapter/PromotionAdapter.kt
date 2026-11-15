package com.example.lumipos.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lumipos.R
import com.example.lumipos.data.local.entity.Promotion
import com.example.lumipos.util.CurrencyFormatter

class PromotionAdapter(
    private val onClick: (Promotion) -> Unit,
    private val onDelete: (Promotion) -> Unit
) : RecyclerView.Adapter<PromotionAdapter.PromotionViewHolder>() {

    private val items = mutableListOf<Promotion>()

    class PromotionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_promo_name)
        val tvDetail: TextView = view.findViewById(R.id.tv_promo_detail)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_promo)
    }

    fun submitList(promotions: List<Promotion>) {
        items.clear()
        items.addAll(promotions)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PromotionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_promotion, parent, false)
        return PromotionViewHolder(view)
    }

    override fun onBindViewHolder(holder: PromotionViewHolder, position: Int) {
        val promo = items[position]
        holder.tvName.text = if (promo.active) promo.name else "${promo.name} (inactive)"
        holder.tvDetail.text = describe(promo)
        holder.itemView.setOnClickListener { onClick(promo) }
        holder.btnDelete.setOnClickListener { onDelete(promo) }
    }

    private fun describe(promo: Promotion): String = when (promo.type) {
        "PERCENT" -> "${promo.percentOff}% off"
        "AMOUNT" -> "${CurrencyFormatter.format(promo.amountOff)} off"
        "BOGO" -> "Buy ${promo.buyQty} get ${promo.getQty} free"
        "QUANTITY" -> "${promo.bundleQty} for ${CurrencyFormatter.format(promo.bundlePrice)}"
        else -> promo.type
    }

    override fun getItemCount() = items.size
}
