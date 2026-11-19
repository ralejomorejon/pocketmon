package com.pcstats.monitor.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface WsEvent {
    data object Open : WsEvent
    data class Text(val text: String) : WsEvent
    data class Closed(val code: Int, val reason: String) : WsEvent
    data class Failure(val error: Throwable) : WsEvent
}

/** Thin wrapper turning OkHttp's WebSocket callbacks into a channel of events. */
class WsSession private constructor(
    val host: String,
    private val socket: WebSocket,
    val events: ReceiveChannel<WsEvent>,
) {
    fun send(text: String): Boolean = socket.send(text)

    fun close() {
        if (!socket.close(1000, null)) socket.cancel()
    }

    fun cancel() = socket.cancel()

    companion object {
        fun open(client: OkHttpClient, host: String): WsSession {
            val channel = Channel<WsEvent>(Channel.UNLIMITED)
            val request = Request.Builder().url(Hosts.wsUrl(host)).build()
            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    channel.trySend(WsEvent.Open)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    channel.trySend(WsEvent.Text(text))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    channel.trySend(WsEvent.Closed(code, reason))
                    channel.close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    channel.trySend(WsEvent.Closed(code, reason))
                    channel.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    channel.trySend(WsEvent.Failure(t))
                    channel.close()
                }
            })
            return WsSession(host, ws, channel)
        }
    }
}
