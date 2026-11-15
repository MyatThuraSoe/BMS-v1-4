package com.example.lumipos.sync

import com.google.gson.Gson
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText

/**
 * Embedded WebSocket server that turns this tablet into the store's "master" terminal.
 *
 * Clients connect to `ws://<this-device-ip>:<port>/sync`, authenticate with the shared token and
 * push SALE messages; each is written to the local Room database before it is ACKed, so a client
 * can safely drop its queued copy.
 */
class MasterSyncServer(
    private val port: Int,
    private val token: String,
    private val onSale: suspend (SyncMessage) -> Long
) {

    private var server: ApplicationEngine? = null
    private val gson = Gson()

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket(SyncSettings.endpointPath()) {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val message = runCatching {
                            gson.fromJson(frame.readText(), SyncMessage::class.java)
                        }.getOrNull() ?: continue

                        when (message.type) {
                            SyncMessage.TYPE_PING -> {
                                sendText(SyncMessage(type = SyncMessage.TYPE_PONG))
                            }
                            SyncMessage.TYPE_SALE -> {
                                if (message.token != token) {
                                    sendText(
                                        SyncMessage(
                                            type = SyncMessage.TYPE_ERROR,
                                            message = "Invalid sync token"
                                        )
                                    )
                                    continue
                                }
                                val response = runCatching { onSale(message) }
                                response
                                    .onSuccess {
                                        sendText(
                                            SyncMessage(
                                                type = SyncMessage.TYPE_ACK,
                                                message = message.sale?.invoiceNumber
                                            )
                                        )
                                    }
                                    .onFailure { error ->
                                        sendText(
                                            SyncMessage(
                                                type = SyncMessage.TYPE_ERROR,
                                                message = error.message ?: "Failed to store sale"
                                            )
                                        )
                                    }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(500, 1000)
        server = null
    }

    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.sendText(message: SyncMessage) {
        send(Frame.Text(gson.toJson(message)))
    }

    /** The IPv4 address clients should use, or null when no LAN interface is up. */
    fun localAddress(): String? = NetworkUtils.localIpv4()
}
