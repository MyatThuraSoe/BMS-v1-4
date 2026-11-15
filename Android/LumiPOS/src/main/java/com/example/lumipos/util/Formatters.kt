package com.example.lumipos.util

import java.text.SimpleDateFormat
import java.util.*

object CurrencyFormatter {
    private var currencySymbol = "Ks"

    fun setCurrency(symbol: String) {
        // A blank shop currency would blank out every amount in the app.
        if (symbol.isNotBlank()) currencySymbol = symbol.trim()
    }

    /** The active symbol, for TextInputLayout prefixes that cannot go through [format]. */
    fun symbol(): String = currencySymbol

    fun format(amount: Double): String {
        return "${String.format("%,.2f", amount)} $currencySymbol"
    }

    fun formatPlain(amount: Double): String {
        return String.format("%,.2f", amount)
    }

    fun formatWithoutDecimals(amount: Double): String {
        return "${String.format("%,.0f", amount)} $currencySymbol"
    }
}

object DateFormatter {
    fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formatDateTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formatDateShort(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun toStartOfDay(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month, day, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun toEndOfDay(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month, day, 23, 59, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    fun getTodayStart(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun getTodayEnd(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    fun getWeekStart(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun getMonthStart(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun getYearStart(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun generateInvoiceNumber(): String {
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val random = (1000..9999).random()
        return "INV-$timestamp-$random"
    }

    fun generateOrderNumber(): String {
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val random = (1000..9999).random()
        return "ORD-$timestamp-$random"
    }

    fun generatePurchaseNumber(): String {
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val random = (1000..9999).random()
        return "PUR-$timestamp-$random"
    }

    fun generateReturnNumber(): String {
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val random = (1000..9999).random()
        return "RTN-$timestamp-$random"
    }

    fun generateCountNumber(): String {
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val random = (1000..9999).random()
        return "CNT-$timestamp-$random"
    }

    fun generateTransferNumber(): String {
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val random = (1000..9999).random()
        return "TRF-$timestamp-$random"
    }
}
