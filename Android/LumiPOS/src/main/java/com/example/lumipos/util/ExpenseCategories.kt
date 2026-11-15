package com.example.lumipos.util

import android.content.Context
import androidx.annotation.StringRes
import com.example.lumipos.R

/**
 * Expense categories.
 *
 * These are exactly the constants of the web app's `Expense.ExpenseCategory` enum
 * (RENT, UTILITIES, TRAVEL, TAXES, SALARY, SUPPLIES, MAINTENANCE, MARKETING, OTHER), and
 * `Expense.category` stores the enum's name verbatim, so a value written on either platform
 * reads correctly on the other. Do not rename them without changing the backend too.
 */
object ExpenseCategories {

    /** Storage order matters only for display; it mirrors the enum's declaration order. */
    val ALL: List<String> = listOf(
        "RENT",
        "UTILITIES",
        "TRAVEL",
        "TAXES",
        "SALARY",
        "SUPPLIES",
        "MAINTENANCE",
        "MARKETING",
        "OTHER"
    )

    @StringRes
    private fun labelRes(category: String): Int = when (category.trim().uppercase()) {
        "RENT" -> R.string.expense_cat_rent
        "UTILITIES" -> R.string.expense_cat_utilities
        "TRAVEL" -> R.string.expense_cat_travel
        "TAXES" -> R.string.expense_cat_taxes
        "SALARY" -> R.string.expense_cat_salary
        "SUPPLIES" -> R.string.expense_cat_supplies
        "MAINTENANCE" -> R.string.expense_cat_maintenance
        "MARKETING" -> R.string.expense_cat_marketing
        // Blank or unrecognised values fall through to OTHER rather than showing a raw enum name.
        else -> R.string.expense_cat_other
    }

    fun label(context: Context, category: String): String = context.getString(labelRes(category))
}
