package com.gomoku.bot.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 从群消息事件中提取出的文本消息。 */
data class GroupEvent(
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val rawPreview: String
)

/**
 * 钉钉「用户级」事件订阅（未公开接口，按官方 dws CLI 的实现对齐）。
 *
 * - 订阅：POST https://mcp.dingtalk.com/dws/subscription/user
 * - 取流票据：POST https://mcp.dingtalk.com/stream/connections/ticket
 * - 事件通过独立的 personal Stream（WebSocket）推送
 *
 * 使用「全部群消息」事件 user_im_message_receive_group_all，
 * 避免要求用户手工填写 openConversationId。
 */
object DingTalkPersonalEvent {

    private const val MCP = "https://mcp.dingtalk.com"
    private const val SOURCE_ID = "open"
    const val EVENT_ALL_GROUP = "user_im_message_receive_group_all"

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 创建订阅，返回 subscribeId。 */
    fun subscribe(userToken: String, appKey: String): String {
        val body = JSONObject()
            .put("clientId", appKey)
            .put("sourceId", SOURCE_ID)
            .put("eventKey", EVENT_ALL_GROUP)
            .put("filterRule", "")
            .put("deliveryPref", "realtime")
            .put("ext", JSONObject().put("ruleType", "all").put("name", "gomoku-android"))
        val root = post(
            "$MCP/dws/subscription/user",
            body.toString(),
            userToken,
            appKey
        )
        val r = unwrap(root)
        return r.optString("subscribe_id")
            .ifBlank { r.optString("subId") }
            .ifBlank { r.optString("subscribeId") }
    }

    /** 取消订阅（失败不抛出）。 */
    fun cancel(userToken: String, appKey: String, subscribeId: String) {
        if (subscribeId.isBlank()) return
        runCatching {
            val body = JSONObject().put("subId", subscribeId)
            post("$MCP/dws/subscription/cancel", body.toString(), userToken, appKey)
        }
    }

    /** 取 personal Stream 连接票据：返回 endpoint 与 ticket。 */
    fun ticket(userToken: String, appKey: String): Pair<String, String> {
        val body = JSONObject()
            .put("sourceId", SOURCE_ID)
            .put("mode", "normal")
        val root = post(
            "$MCP/stream/connections/ticket",
            body.toString(),
            userToken,
            appKey
        )
        val r = unwrap(root)
        val endpoint = r.optString("endpoint")
        val ticket = r.optString("ticket")
        if (endpoint.isBlank() || ticket.isBlank()) {
            throw IllegalStateException("取流票据失败：${root.toString().take(200)}")
        }
        return endpoint to ticket
    }

    /**
     * 解析群消息事件的 data。
     * 兼容 {"eventType":..,"data":{...}} 与直接消息体两种结构。
     */
    fun parseEvent(data: JSONObject): GroupEvent? {
        val event = data.optJSONObject("data") ?: data
        val conversationId = firstNonBlank(
            event, "conversationId", "openConversationId", "conversation_id", "cid"
        )
        val senderId = firstNonBlank(
            event, "senderId", "senderStaffId", "sender_staff_id", "sender_id", "from"
        )
        val senderName = firstNonBlank(
            event, "senderNick", "senderName", "sender_nick", "name"
        )
        val text = extractText(event)
        if (text.isBlank()) return null
        return GroupEvent(
            conversationId = conversationId,
            senderId = senderId,
            senderName = senderName,
            text = text,
            rawPreview = data.toString().take(400)
        )
    }

    private fun extractText(event: JSONObject): String {
        event.optJSONObject("text")?.let { t ->
            val c = t.optString("content")
            if (c.isNotBlank()) return c
        }
        val content = event.optString("content")
        if (content.isNotBlank()) {
            val trimmed = content.trim()
            if (trimmed.startsWith("{")) {
                runCatching {
                    val j = JSONObject(trimmed)
                    j.optJSONObject("text")?.optString("content")?.let { if (it.isNotBlank()) return it }
                    j.optString("content").let { if (it.isNotBlank()) return it }
                }
            }
            return content
        }
        return ""
    }

    private fun firstNonBlank(obj: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = obj.optString(k)
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun unwrap(root: JSONObject): JSONObject =
        root.optJSONObject("result")
            ?: root.optJSONObject("data")
            ?: root

    private fun post(url: String, payload: String, token: String, appKey: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON))
            .header("Authorization", "Bearer $token")
            .header("x-user-access-token", token)
            .header("X-DWS-Client-Id", appKey)
            .header("X-DWS-Source-Id", SOURCE_ID)
            .header("X-Cli-Version", "gomoku-android/1.2.0")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val body = runCatching {
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }.getOrDefault(JSONObject())
            if (!response.isSuccessful) {
                val code = body.optString("code").ifBlank { body.optString("errorCode") }
                val msg = body.optString("message").ifBlank { body.optString("errorMsg") }
                throw DingTalkApiException(
                    httpCode = response.code,
                    code = code,
                    detail = msg.ifBlank { text.take(200) },
                    requestId = body.optString("requestId")
                )
            }
            if (!body.optBoolean("success", true)) {
                val code = body.optString("code").ifBlank { body.optString("errorCode") }
                val msg = body.optString("message").ifBlank { body.optString("errorMsg") }
                throw DingTalkApiException(
                    httpCode = response.code,
                    code = code,
                    detail = msg.ifBlank { body.toString().take(200) },
                    requestId = body.optString("requestId")
                )
            }
            return body
        }
    }
}

/**
 * personal Stream 客户端：接收群消息事件。
 * 帧格式与 App Stream 一致（SYSTEM/EVENT + ACK），只是票据来源不同。
 */
class DingTalkGroupStreamClient(
    private val userToken: String,
    private val appKey: String,
    private val onEvent: (GroupEvent) -> Unit,
    private val onRawEvent: (String) -> Unit,
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
        runCatching { current?.close(1000, "bye") }
        runCatching {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun connect() {
        if (!running.get()) return
        try {
            val (endpoint, ticket) = DingTalkPersonalEvent.ticket(userToken, appKey)
            val encoded = URLEncoder.encode(ticket, "UTF-8")
            val url = if (endpoint.contains("?")) "$endpoint&ticket=$encoded" else "$endpoint?ticket=$encoded"
            socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
        } catch (e: DingTalkApiException) {
            onError(e.format("群消息通道握手失败"))
            scheduleRetry()
        } catch (e: Exception) {
            onError("群消息通道连接失败：${e.message ?: e.javaClass.simpleName}")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!running.get()) return
        retry += 1
        val delayMs = retry.coerceAtMost(6) * 3000L
        onLog("${delayMs / 1000} 秒后重连群消息通道…")
        Thread {
            runCatching { Thread.sleep(delayMs) }
            connect()
        }.start()
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            retry = 0
            onLog("群消息通道已建立，正在监听群消息")
            onReady()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handle(webSocket, text) }
                .onFailure { onError("处理群消息失败：${it.message ?: it.javaClass.simpleName}") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!running.get()) return
            onError("群消息通道断开：${t.message ?: "网络异常"}")
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
                val opaque = runCatching { JSONObject(data).optString("opaque") }.getOrDefault("")
                ack(webSocket, messageId, JSONObject().put("opaque", opaque).toString())
            }
            return
        }

        onRawEvent(raw.take(600))

        if (data.isNotBlank()) {
            val payload = runCatching { JSONObject(data) }.getOrNull()
            if (payload != null) {
                DingTalkPersonalEvent.parseEvent(payload)?.let(onEvent)
            }
        }
        ack(webSocket, messageId, "{\"status\":\"SUCCESS\"}")
    }

    private fun ack(webSocket: WebSocket, messageId: String, data: String) {
        val ack = JSONObject()
            .put("code", 200)
            .put("message", "OK")
            .put("headers", JSONObject().put("messageId", messageId).put("contentType", "application/json"))
            .put("data", data)
        runCatching { webSocket.send(ack.toString()) }
    }
}
