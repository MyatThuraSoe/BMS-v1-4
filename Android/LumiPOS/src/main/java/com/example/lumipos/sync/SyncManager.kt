package com.example.lumipos.sync

import android.os.Build
import com.example.lumipos.data.local.AppDatabase
import com.example.lumipos.data.local.entity.SyncOutbox
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Client-side half of the LAN sync: pushes completed sales to the master tablet.
 *
 * Delivery is best-effort - a sale that cannot be delivered (master off, Wi-Fi down) is parked in
 * the `sync_outbox` table and retried on app start, after the next sale, or via the Settings
 * "Sync now" action. Rows leave the queue only after the master ACKs them.
 */
object SyncManager {

    private val gson = Gson()

    private val client: HttpClient by lazy {
        HttpClient(CIO) { install(WebSockets) }
    }

    data class SyncConfig(
        val mode: String,
        val masterIp: String,
        val port: Int,
        val token: String
    )

    suspend fun config(db: AppDatabase): SyncConfig {
        val dao = db.settingDao()
        val mode = dao.getValue(SyncSettings.KEY_MODE) ?: SyncSettings.MODE_OFF
        val ip = dao.getValue(SyncSettings.KEY_MASTER_IP) ?: ""
        val port = (dao.getValue(SyncSettings.KEY_PORT) ?: SyncSettings.DEFAULT_PORT)
            .toIntOrNull() ?: SyncSettings.DEFAULT_PORT.toInt()
        val token = dao.getValue(SyncSettings.KEY_TOKEN) ?: SyncSettings.DEFAULT_TOKEN
        return SyncConfig(mode = mode, masterIp = ip.trim(), port = port, token = token)
    }

    /**
     * Called after a sale is committed locally (client mode only). Tries to deliver immediately;
     * on failure the serialized payload goes to the outbox.
     */
    suspend fun onSaleCompleted(db: AppDatabase, saleId: Long) {
        val config = config(db)
        if (config.mode != SyncSettings.MODE_CLIENT) return
        if (config.masterIp.isBlank()) return

        val message = buildSaleMessage(db, saleId) ?: return
        val json = gson.toJson(message)
        val delivered = runCatching { send(json, config) }.getOrDefault(false)
        if (!delivered) {
            db.syncOutboxDao().insert(SyncOutbox(payload = json))
        }
    }

    /** Retries every queued sale in order; stops early when the master stays unreachable. */
    suspend fun flushOutbox(db: AppDatabase): Int {
        val config = config(db)
        if (config.mode != SyncSettings.MODE_CLIENT || config.masterIp.isBlank()) return 0

        var delivered = 0
        for (queued in db.syncOutboxDao().peek(limit = 20)) {
            val sent = runCatching { send(queued.payload, config) }.getOrDefault(false)
            if (sent) {
                db.syncOutboxDao().delete(queued.id)
                delivered++
            } else {
                db.syncOutboxDao().bumpAttempts(queued.id)
                break
            }
        }
        return delivered
    }

    /** Settings "Test connection": PINGs the configured master. */
    suspend fun testConnection(db: AppDatabase): Result<String> {
        val config = config(db)
        if (config.masterIp.isBlank()) return Result.failure(IllegalStateException("No master IP set"))
        return runCatching {
            val reply = exchange(
                gson.toJson(SyncMessage(type = SyncMessage.TYPE_PING, token = config.token)),
                config
            ) ?: throw IllegalStateException("No response from master")
            if (reply.type == SyncMessage.TYPE_PONG) {
                "Connected to master"
            } else {
                throw IllegalStateException(reply.message ?: "Unexpected reply: ${reply.type}")
            }
        }
    }

    /** True when the master ACKed the sale (or ERRORs we should surface differently). */
    private suspend fun send(json: String, config: SyncConfig): Boolean {
        val reply = exchange(json, config) ?: return false
        return reply.type == SyncMessage.TYPE_ACK
    }

    private suspend fun exchange(json: String, config: SyncConfig): SyncMessage? {
        var response: SyncMessage? = null
        withTimeoutOrNull(10_000) {
            client.webSocket(
                host = config.masterIp,
                port = config.port,
                path = SyncSettings.endpointPath()
            ) {
                send(Frame.Text(json))
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        response = runCatching {
                            gson.fromJson(frame.readText(), SyncMessage::class.java)
                        }.getOrNull()
                        break
                    }
                }
            }
        }
        return response
    }

    private suspend fun buildSaleMessage(db: AppDatabase, saleId: Long): SyncMessage? {
        val sale = db.saleDao().getById(saleId) ?: return null
        val items = db.saleItemDao().getBySaleIdList(saleId)
        return SyncMessage(
            type = SyncMessage.TYPE_SALE,
            token = config(db).token,
            deviceName = deviceName(),
            sale = SyncSale(
                invoiceNumber = sale.invoiceNumber,
                customerId = sale.customerId,
                customerName = sale.customerName,
                cashierName = sale.cashierName,
                saleType = sale.saleType,
                paymentMethod = sale.paymentMethod,
                paymentStatus = sale.paymentStatus,
                saleDate = sale.saleDate,
                dueDate = sale.dueDate,
                subtotal = sale.subtotal,
                taxAmount = sale.taxAmount,
                discountAmount = sale.discountAmount,
                totalAmount = sale.totalAmount,
                amountPaid = sale.amountPaid,
                notes = sale.notes
            ),
            items = items.map { item ->
                SyncSaleItem(
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
    }

    private fun deviceName(): String {
        val model = Build.MODEL ?: "tablet"
        return model.ifBlank { "tablet" }
    }
}
