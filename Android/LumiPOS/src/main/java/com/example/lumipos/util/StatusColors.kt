package com.example.lumipos.util

import androidx.annotation.ColorRes
import com.example.lumipos.R

/**
 * Maps a status string to the colour a status chip should be tinted with.
 *
 * Why this exists: `res/drawable/bg_status_chip.xml` used to be a colour *selector* keyed on
 * `android:state_selected` / `android:state_checked`. A plain `TextView` never enters either state,
 * so the selector always fell through to its default item and **every** status chip in the app -
 * sales, orders, invoices, shifts, stock - rendered the same orange, whatever it said. The drawable
 * is now a neutral rounded shape and callers tint it from here.
 *
 * Keys are compared case-insensitively so "Open" and "OPEN" behave the same.
 */
object StatusColors {

    @ColorRes
    fun of(status: String): Int = when (status.trim().uppercase()) {
        // Settled / healthy.
        "PAID", "CONVERTED", "COMPLETED", "OPEN", "IN_STOCK", "RECEIVED" -> R.color.status_paid

        // In progress - money still owed, or stock running low / in transit.
        "PARTIAL" -> R.color.status_partial
        "PENDING", "LOW_STOCK", "SENT" -> R.color.status_pending

        // Needs attention.
        "OVERDUE", "VOID", "VOIDED", "UNPAID", "OUT_OF_STOCK" -> R.color.status_void

        // Finished or neutral outcomes. Deliberately not red: a closed shift or a cancelled draft
        // order is normal housekeeping, not a problem.
        "CANCELLED", "CANCELED", "CLOSED", "DRAFT" -> R.color.text_secondary

        else -> R.color.status_pending
    }
}
