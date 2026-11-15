package com.example.lumipos.util

import com.example.lumipos.data.local.entity.ReceiptCustomization
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.data.local.entity.SaleItem
import com.example.lumipos.data.local.entity.ShopInfo

object ReceiptGenerator {

    fun buildReceiptText(
        shop: ShopInfo,
        custom: ReceiptCustomization,
        sale: Sale,
        items: List<SaleItem>
    ): String {
        val sb = StringBuilder()
        val divider = when (custom.dividerStyle) {
            "solid" -> "--------------------------------"
            "dotted" -> "- - - - - - - - - - - - - - - - -"
            else -> "- - - - - - - - - - - - - - - - - -"
        }
        val header = if (custom.headerText.isBlank()) "LumiPOS" else custom.headerText

        if (custom.showShopName) sb.appendLine(shop.shopName)
        if (custom.showAddress && shop.address.isNotBlank()) sb.appendLine(shop.address)
        if (custom.showPhone && shop.phone.isNotBlank()) sb.appendLine("Tel: ${shop.phone}")
        sb.appendLine(header)
        sb.appendLine(divider)
        sb.appendLine("Cashier: ${sale.cashierName.ifBlank { "Cashier" }}")
        sb.appendLine("Invoice: ${sale.invoiceNumber}")
        sb.appendLine("Customer: ${sale.customerName}")
        sb.appendLine("Date: ${DateFormatter.formatDateTime(sale.saleDate)}")
        sb.appendLine(divider)

        for (item in items) {
            val qty = item.quantity - item.quantityRefunded
            if (qty <= 0) continue
            sb.appendLine(item.productName)
            sb.appendLine("  ${qty} x ${CurrencyFormatter.format(item.unitPrice)}")
            sb.appendLine("     ${CurrencyFormatter.format(item.totalPrice)}")
        }
        sb.appendLine(divider)
        if (custom.showDiscount && sale.discountAmount > 0) {
            sb.appendLine("Subtotal:    ${CurrencyFormatter.format(sale.subtotal)}")
            sb.appendLine("Discount:    ${CurrencyFormatter.format(sale.discountAmount)}")
        }
        if (custom.showTax && sale.taxAmount > 0) {
            sb.appendLine("Tax:         ${CurrencyFormatter.format(sale.taxAmount)}")
        }
        sb.appendLine("TOTAL:       ${CurrencyFormatter.format(sale.totalAmount)}")
        if (custom.showCreditInfo) {
            sb.appendLine("Payment:     ${sale.paymentMethod}")
            if (sale.amountPaid > 0) sb.appendLine("Paid:        ${CurrencyFormatter.format(sale.amountPaid)}")
            if (sale.amountPaid - sale.totalAmount > 0) {
                sb.appendLine("Change:      ${CurrencyFormatter.format(sale.amountPaid - sale.totalAmount)}")
            }
            if (sale.paymentStatus != "PAID") {
                sb.appendLine("Status:      ${sale.paymentStatus}")
            }
        }
        sb.appendLine(divider)
        if (custom.mainMessage.isNotBlank()) sb.appendLine(custom.mainMessage)
        if (custom.footerText.isNotBlank()) sb.appendLine(custom.footerText)
        return sb.toString()
    }
}