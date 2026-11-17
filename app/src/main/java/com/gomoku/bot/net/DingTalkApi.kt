package com.gomoku.bot.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 钉钉企业内部应用机器人配置。 */
data class DingTalkConfig(
    val appKey: String,
    val appSecret: String,
    val robotCode: String,
    val peerUserId: String,
    val openConversationId: String = "",
    val room: String = "",
    val nickname: String = ""
)

/**
 * 钉钉开放平台调用：获取 token、机器人单聊/群聊发消息、注册 Stream 长连接。
 */
object DingTalkApi {

    private const val BASE = "https://api.dingtalk.com"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private var tokenKey = ""
    private var tokenValue = ""
    private var tokenExpireAt = 0L

    @Synchronized
    fun accessToken(config: DingTalkConfig): String {
        val now = System.currentTimeMillis()
        if (tokenValue.isNotEmpty() && tokenKey == config.appKey && now < tokenExpireAt) {
            return tokenValue
        }
        val body = JSONObject()
            .put("appKey", config.appKey)
            .put("appSecret", config.appSecret)
        val json = post("$BASE/v1.0/oauth2/accessToken", body.toString(), null)
        val token = json.optString("accessToken")
        if (token.isEmpty()) {
            throw IllegalStateException("获取 accessToken 失败：${json.optString("message", json.toString())}")
        }
        tokenKey = config.appKey
        tokenValue = token
        tokenExpireAt = now + ((json.optLong("expireIn", 7200L) - 300L).coerceAtLeast(60L)) * 1000L
        return token
    }

    /** 机器人给指定钉钉用户发单聊消息。 */
    fun sendOto(config: DingTalkConfig, content: String) {
        if (config.peerUserId.isBlank()) throw IllegalArgumentException("未配置对方钉钉 userId")
        val body = JSONObject()
            .put("robotCode", config.robotCode)
            .put("userIds", JSONArray().put(config.peerUserId))
            .put("msgKey", "sampleText")
            .put("msgParam", JSONObject().put("content", content).toString())
        post("$BASE/v1.0/robot/oToMessages/batchSend", body.toString(), accessToken(config))
    }

    /** 机器人向群会话发消息，用于对局播报/留存。 */
    fun sendGroup(config: DingTalkConfig, content: String) {
        if (config.openConversationId.isBlank()) return
        val body = JSONObject()
            .put("robotCode", config.robotCode)
            .put("openConversationId", config.openConversationId)
            .put("msgKey", "sampleText")
            .put("msgParam", JSONObject().put("content", content).toString())
        post("$BASE/v1.0/robot/groupMessages/send", body.toString(), accessToken(config))
    }

    /** 注册 Stream 连接凭证，返回 endpoint 与一次性 ticket。 */
    fun openConnection(config: DingTalkConfig): JSONObject {
        val subscriptions = JSONArray().put(
            JSONObject()
                .put("topic", "/v1.0/im/bot/messages/get")
                .put("type", "CALLBACK")
        )
        val body = JSONObject()
            .put("clientId", config.appKey)
            .put("clientSecret", config.appSecret)
            .put("subscriptions", subscriptions)
            .put("ua", "gomoku-android/1.1.0")
        return post("$BASE/v1.0/gateway/connections/open", body.toString(), null)
    }

    private fun post(url: String, payload: String, token: String?): JSONObject {
        val builder = Request.Builder().url(url).post(payload.toRequestBody(JSON))
        if (!token.isNullOrBlank()) builder.header("x-acs-dingtalk-access-token", token)
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("钉钉接口 ${response.code}：${text.take(200)}")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
}
