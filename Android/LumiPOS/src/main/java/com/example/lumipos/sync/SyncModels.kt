package com.example.lumipos.sync

/**
 * Wire format for the LAN sync protocol. Messages travel as Gson-serialized JSON text frames
 * over a single WebSocket endpoint (`/sync`) on the master tablet.
 *
 * Types:
 *  - PING  -> PONG: connectivity check from the settings screen
 *  - SALE  -> ACK: a completed sale pushed by a client, acknowledged once written to the master DB
 *  - ERROR: the master rejected the message (bad token, malformed payload, ...)
 */
data class SyncMessage(
    val type: String,
    val token: String = "",
    val deviceName: String = "",
    val sale: SyncSale? = null,
    val items: List<SyncSaleItem> = emptyList(),
    val message: String? = null
) {
    companion object {
        const val TYPE_PING = "PING"
        const val TYPE_PONG = "PONG"
        const val TYPE_SALE = "SALE"
        const val TYPE_ACK = "ACK"
        const val TYPE_ERROR = "ERROR"
    }
}

/** Sale header as sent by a client terminal. Local-only ids (shift, db id) are not synced. */
data class SyncSale(
    val invoiceNumber: String,
    val customerId: Long?,
    val customerName: String,
    val cashierName: String,
    val saleType: String,
    val paymentMethod: String,
    val paymentStatus: String,
    val saleDate: Long,
    val dueDate: Long?,
    val subtotal: Double,
    val taxAmount: Double,
    val discountAmount: Double,
    val totalAmount: Double,
    val amountPaid: Double,
    val notes: String
)

data class SyncSaleItem(
    val productId: Long,
    val productName: String,
    val sku: String,
    val quantity: Int,
    val unitPrice: Double,
    val costPrice: Double,
    val taxAmount: Double,
    val totalPrice: Double,
    val discountAmount: Double
)

/** Setting keys + defaults for the sync feature (stored in the settings table). */
object SyncSettings {
    const val KEY_MODE = "sync_mode"
    const val KEY_PORT = "sync_port"
    const val KEY_MASTER_IP = "sync_master_ip"
    const val KEY_TOKEN = "sync_token"

    const val MODE_OFF = "OFF"
    const val MODE_MASTER = "MASTER"
    const val MODE_CLIENT = "CLIENT"

    const val DEFAULT_PORT = "8989"
    const val DEFAULT_TOKEN = "lumipos"

    fun endpointPath() = "/sync"
}
