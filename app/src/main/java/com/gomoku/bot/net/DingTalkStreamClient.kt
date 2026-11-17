package com.gomoku.bot.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 钉钉 Stream 模式客户端：
 * 1. 调用 /v1.0/gateway/connections/open 换取 endpoint + ticket；
 * 2. 建立 WebSocket，订阅机器人消息回调；
 * 3. 处理 SYSTEM ping/disconnect 与 CALLBACK 消息，并按协议回 ACK；
 * 4. 断线后自动重连。
 */
class DingTalkStreamClient(
    private val config: DingTalkConfig,
    private val onBotMessage: (JSONObject) -> Unit,
    private val onLog: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onReady: () -> Unit
) {

    private val running = AtomicBoolean(false)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var retry = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread { connect() }.start()
    }

    fun stop() {
        running.set(false)
        val current = socket
        socket = null
        try {
            current?.close(1000, "bye")
        } catch (_: Exception) {
        }
        try {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        } catch (_: Exception) {
        }
    }

    private fun connect() {
        if (!running.get()) return
        try {
            val open = DingTalkApi.openConnection(config)
            val endpoint = open.optString("endpoint")
            val ticket = open.optString("ticket")
            if (endpoint.isBlank() || ticket.isBlank()) {
                throw IllegalStateException("Stream 握手失败：${open.toString().take(160)}")
            }
            val url = if (endpoint.contains("?")) "$endpoint&ticket=$ticket" else "$endpoint?ticket=$ticket"
            socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
        } catch (e: Exception) {
            onError("连接钉钉失败：${e.message ?: e.javaClass.simpleName}")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!running.get()) return
        retry += 1
        val delayMs = retry.coerceAtMost(6) * 3000L
        onLog("${delayMs / 1000} 秒后重连钉钉…")
        Thread {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
            }
            connect()
        }.start()
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            retry = 0
            onLog("钉钉长连接已建立")
            onReady()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handle(webSocket, text)
            } catch (e: Exception) {
                onError("处理钉钉消息失败：${e.message ?: e.javaClass.simpleName}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!running.get()) return
            onError("钉钉长连接断开：${t.message ?: "网络异常"}")
            scheduleRetry()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (running.get()) scheduleRetry()
        }
    }

    private fun handle(webSocket: WebSocket, raw: String) {
        val root = JSONObject(raw)
        val headers = root.optJSONObject("headers") ?: JSONObject()
        val messageId = headers.optString("messageId")
        val topic = headers.optString("topic")
        val type = root.optString("type")
        val data = root.optString("data")

        if (type == "SYSTEM") {
            if (topic == "ping") {
                val opaque = try {
                    JSONObject(data).optString("opaque")
                } catch (_: Exception) {
                    ""
                }
                ack(webSocket, messageId, JSONObject().put("opaque", opaque).toString())
            }
            // disconnect：服务端会主动断开，交给 onClosed/onFailure 触发重连
            return
        }

        if (type == "CALLBACK" && topic == "/v1.0/im/bot/messages/get") {
            val payload = try {
                JSONObject(data)
            } catch (_: Exception) {
                null
            }
            if (payload != null) onBotMessage(payload)
            ack(webSocket, messageId, "{\"response\":null}")
            return
        }

        ack(webSocket, messageId, "{\"status\":\"SUCCESS\"}")
    }

    private fun ack(webSocket: WebSocket, messageId: String, data: String) {
        val ack = JSONObject()
            .put("code", 200)
            .put("message", "OK")
            .put("headers", JSONObject().put("messageId", messageId).put("contentType", "application/json"))
            .put("data", data)
        try {
            webSocket.send(ack.toString())
        } catch (_: Exception) {
        }
    }
}
