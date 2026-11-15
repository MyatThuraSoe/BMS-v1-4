package com.example.lumipos.util

import com.example.lumipos.data.local.entity.Promotion
import java.util.Calendar

/**
 * Pure promotion calculator. Given the current cart lines and the shop's active promotions, it
 * returns the discount that should be applied, both in total and broken down per product.
 *
 * Only the single best promotion is applied to each product, which keeps stacking predictable and
 * guarantees a line can never be discounted below zero.
 */
object PromotionEngine {

    /** Minimal view of a cart line the engine needs; keeps it decoupled from the POS ViewModel. */
    data class Line(
        val productId: Long,
        val categoryId: Long,
        val unitPrice: Double,
        val quantity: Int
    )

    data class Result(
        val total: Double,
        val perProduct: Map<Long, Double>,
        val appliedNames: List<String>
    )

    fun evaluate(lines: List<Line>, promotions: List<Promotion>, now: Calendar = Calendar.getInstance()): Result {
        if (lines.isEmpty() || promotions.isEmpty()) return Result(0.0, emptyMap(), emptyList())

        val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val perProduct = mutableMapOf<Long, Double>()
        val applied = mutableListOf<String>()

        for (line in lines) {
            val gross = line.unitPrice * line.quantity
            val best = promotions
                .filter { it.active && matches(it, line) && withinWindow(it, minuteOfDay) }
                .map { it to discountFor(it, line) }
                .filter { it.second > 0.0 }
                .maxByOrNull { it.second }

            if (best != null) {
                val discount = best.second.coerceAtMost(gross)
                perProduct[line.productId] = discount
                applied.add(best.first.name)
            }
        }

        return Result(perProduct.values.sum(), perProduct, applied)
    }

    private fun matches(promo: Promotion, line: Line): Boolean = when (promo.scope) {
        "PRODUCT" -> promo.productId == line.productId
        "CATEGORY" -> promo.categoryId != null && promo.categoryId == line.categoryId
        else -> true
    }

    private fun withinWindow(promo: Promotion, minuteOfDay: Int): Boolean {
        if (promo.startMinute < 0 || promo.endMinute < 0) return true
        return if (promo.startMinute <= promo.endMinute) {
            minuteOfDay in promo.startMinute..promo.endMinute
        } else {
            // Window wraps past midnight (e.g. 22:00 - 02:00).
            minuteOfDay >= promo.startMinute || minuteOfDay <= promo.endMinute
        }
    }

    private fun discountFor(promo: Promotion, line: Line): Double {
        val qty = line.quantity
        val price = line.unitPrice
        return when (promo.type) {
            "PERCENT" -> price * qty * (promo.percentOff / 100.0)
            "AMOUNT" -> promo.amountOff * qty
            "BOGO" -> {
                val group = promo.buyQty + promo.getQty
                if (promo.buyQty <= 0 || promo.getQty <= 0 || group <= 0) 0.0
                else {
                    val freeGroups = qty / group
                    freeGroups * promo.getQty * price
                }
            }
            "QUANTITY" -> {
                if (promo.bundleQty <= 0) 0.0
                else {
                    val bundles = qty / promo.bundleQty
                    val normal = promo.bundleQty * price
                    val saving = (normal - promo.bundlePrice).coerceAtLeast(0.0)
                    bundles * saving
                }
            }
            else -> 0.0
        }
    }
}
