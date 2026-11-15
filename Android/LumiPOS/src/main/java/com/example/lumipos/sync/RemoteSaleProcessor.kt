package com.example.lumipos.sync

import androidx.room.withTransaction
import com.example.lumipos.data.local.AppDatabase
import com.example.lumipos.data.local.entity.AuditLog
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.data.local.entity.SaleItem
import com.example.lumipos.data.local.entity.StockMovement

/**
 * Writes a sale received from a client terminal into the master's database, mirroring what
 * [com.example.lumipos.ui.pos.POSViewModel.completeSale] does locally: insert the sale + items,
 * deduct stock, record stock movements and write an audit entry.
 *
 * The client's local sale id is not reused - the master assigns its own id so ids from two
 * terminals can never collide. Invoice numbers are de-duplicated with a suffix when needed.
 */
class RemoteSaleProcessor(private val db: AppDatabase) {

    suspend fun process(message: SyncMessage): Long = db.withTransaction {
        val remote = message.sale ?: throw IllegalArgumentException("SALE without payload")

        val invoice = uniqueInvoiceNumber(remote.invoiceNumber)

        val sale = Sale(
            invoiceNumber = invoice,
            customerId = remote.customerId,
            customerName = remote.customerName.ifBlank { "Walk-in" },
            cashierName = remote.cashierName.ifBlank { message.deviceName },
            saleType = remote.saleType,
            paymentMethod = remote.paymentMethod,
            paymentStatus = remote.paymentStatus,
            saleDate = remote.saleDate,
            dueDate = remote.dueDate,
            subtotal = remote.subtotal,
            taxAmount = remote.taxAmount,
            discountAmount = remote.discountAmount,
            discountType = if (remote.discountAmount > 0.0) "FIXED" else "NONE",
            totalAmount = remote.totalAmount,
            amountPaid = remote.amountPaid,
            notes = remote.notes
        )
        val saleId = db.saleDao().insert(sale)

        db.saleItemDao().insertAll(
            message.items.map { item ->
                SaleItem(
                    saleId = saleId,
                    productId = item.productId,
                    productName = item.productName,
                    sku = item.sku,
                    quantity = item.quantity,
                    unitPrice = item.unitPrice,
                    costPrice = item.costPrice,
                    taxAmount = item.taxAmount,
                    totalPrice = item.totalPrice,
                    discountAmount = item.discountAmount
                )
            }
        )

        for (item in message.items) {
            db.productDao().updateStock(item.productId, -item.quantity)
            db.stockMovementDao().insert(
                StockMovement(
                    productId = item.productId,
                    productName = item.productName,
                    sku = item.sku,
                    movementType = "OUT",
                    quantity = item.quantity,
                    referenceType = "SALE",
                    referenceId = saleId,
                    createdByName = remote.cashierName.ifBlank { message.deviceName },
                    description = "Synced sale $invoice from ${message.deviceName}"
                )
            )
        }

        db.auditLogDao().insert(
            AuditLog(
                username = message.deviceName.ifBlank { "terminal" },
                action = "SALE_SYNCED",
                entityType = "SALE",
                entityId = saleId,
                description = "Sale $invoice received via LAN sync"
            )
        )

        saleId
    }

    /** Keeps the original number unless the master already used it (client + master both sell). */
    private suspend fun uniqueInvoiceNumber(invoice: String): String {
        val base = invoice.ifBlank { "SYNC-${System.currentTimeMillis()}" }
        if (db.saleDao().getByInvoiceNumber(base) == null) return base
        var attempt = 1
        while (attempt <= 99) {
            val candidate = "$base-S$attempt"
            if (db.saleDao().getByInvoiceNumber(candidate) == null) return candidate
            attempt++
        }
        return "$base-${System.currentTimeMillis()}"
    }
}
