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
    val nickname: String = "",
    /** 对方机器人的 robotCode，用于在群消息总线中过滤掉自己发出的消息。 */
    val peerRobotCode: String = ""
)

/** 钉钉接口调用异常，保留 HTTP 状态、业务码、错误信息与 requestId 便于排查。 */
class DingTalkApiException(
    val httpCode: Int,
    val code: String,
    val detail: String,
    val requestId: String
) : Exception(detail) {
    fun format(step: String): String {
        val parts = mutableListOf(step, "HTTP $httpCode")
        if (code.isNotEmpty()) parts.add("code=$code")
        if (requestId.isNotEmpty()) parts.add("requestId=$requestId")
        parts.add(detail)
        return parts.joinToString(" · ")
    }
}

/** 单步连接测试结果。 */
data class ConnResult(val step: String, val ok: Boolean, val detail: String)

/**
 * 清理凭据：去除所有空白字符（含中间换行/制表符）与零宽字符。
 * 从钉钉开放平台复制粘贴时常带入这些不可见字符，导致鉴权失败。
 */
fun cleanCredential(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (c in raw) {
        if (c.isWhitespace()) continue
        when (c) {
            '​', '‌', '‍', '﻿', '⁠', '᠎' -> continue
        }
        sb.append(c)
    }
    return sb.toString()
}

/** 脱敏显示：保留前 4 后 2，便于与开放平台核对。 */
private fun mask(s: String): String = when {
    s.isEmpty() -> "(空)"
    s.length <= 6 -> s.take(1) + "…(" + s.length + "位)"
    else -> s.take(4) + "…" + s.takeLast(2) + "(" + s.length + "位)"
}

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

    /**
     * 注册 Stream 连接凭证，返回 endpoint 与一次性 ticket。
     *
     * 订阅两类推送：
     * - EVENT（topic="*"）：接收开发者后台已勾选的全部事件订阅，
     *   用于监听群里所有消息（含对方机器人发出的走法），实现全自动收发。
     * - CALLBACK（/v1.0/im/bot/messages/get）：机器人消息回调，
     *   兼容单聊/群内 @机器人 的传统联机方式。
     */
    fun openConnection(config: DingTalkConfig): JSONObject {
        val subscriptions = JSONArray()
            .put(JSONObject().put("topic", "*").put("type", "EVENT"))
            .put(JSONObject().put("topic", "/v1.0/im/bot/messages/get").put("type", "CALLBACK"))
        val body = JSONObject()
            .put("clientId", config.appKey)
            .put("clientSecret", config.appSecret)
            .put("subscriptions", subscriptions)
            .put("ua", "gomoku-android/1.1.2")
        return post("$BASE/v1.0/gateway/connections/open", body.toString(), null)
    }

    /**
     * 分两步测试连接：先获取 accessToken，再注册 Stream 握手。
     * 任一步失败都会把 code/message/requestId 带回来，方便定位鉴权问题。
     */
    fun testConnection(config: DingTalkConfig): List<ConnResult> {
        val results = ArrayList<ConnResult>()
        results.add(inspectCredentials(config))
        try {
            accessToken(config)
            results.add(ConnResult("获取 accessToken", true, "成功"))
        } catch (e: DingTalkApiException) {
            var detail = e.format("失败")
            if (e.code == "invalidClientIdOrSecret" || e.httpCode == 400) {
                detail += "\n请逐项核对：① AppKey 是否以 ding 开头（不是则复制错了来源）② AppKey 与 AppSecret 是否填反 ③ 是否「企业内部应用」的凭据（非小程序/第三方应用）④ 复制是否完整、无多余字符"
            }
            results.add(ConnResult("获取 accessToken", false, detail))
            return results
        } catch (e: Exception) {
            results.add(ConnResult("获取 accessToken", false, e.message ?: e.javaClass.simpleName))
            return results
        }
        try {
            val json = openConnection(config)
            val endpoint = json.optString("endpoint")
            if (endpoint.isBlank()) {
                results.add(ConnResult("Stream 握手", false, "未返回 endpoint"))
            } else {
                results.add(ConnResult("Stream 握手", true, "成功"))
            }
        } catch (e: DingTalkApiException) {
            var detail = e.format("失败")
            if (e.httpCode == 401 || e.code == "authFailed") {
                detail += "\n凭据已通过 accessToken 校验，握手失败通常是：① 开放平台「事件订阅」未开启 Stream 模式 ② 应用未发布 ③ 机器人未发布"
            }
            results.add(ConnResult("Stream 握手", false, detail))
        } catch (e: Exception) {
            results.add(ConnResult("Stream 握手", false, e.message ?: e.javaClass.simpleName))
        }
        return results
    }

    /**
     * 凭据特征诊断：显示长度与脱敏前后缀，并检测常见填写错误。
     * 信息性步骤（ok 恒为 true，不阻断流程），警告写入 detail。
     */
    private fun inspectCredentials(config: DingTalkConfig): ConnResult {
        val key = config.appKey
        val secret = config.appSecret
        val issues = ArrayList<String>()
        val looksUuid = key.length == 36 && key.count { it == '-' } >= 3
        if (!key.startsWith("ding")) {
            if (looksUuid) {
                issues.add(
                    "AppKey 是 36 位 UUID 格式，不是钉钉 AppKey（企业内部应用 AppKey 必须以 ding 开头，" +
                        "如 dinge9qpkv3xxxxxx）。你很可能复制了其他平台的 AppID / 应用 ID"
                )
            } else {
                issues.add(
                    "AppKey 必须以 ding 开头（如 dinge9qpkv3xxxxxx），当前格式不符合。" +
                        "请到 开放平台 → 应用开发 → 企业内部应用 → 应用信息 → 凭证与基础信息 重新复制"
                )
            }
        }
        if (secret.startsWith("ding")) issues.add("AppSecret 以 ding 开头，很可能与 AppKey 填反了")
        if (key.isNotEmpty() && key.length < 12) issues.add("AppKey 长度偏短，可能未复制完整")
        if (secret.isNotEmpty() && secret.length < 12) issues.add("AppSecret 长度偏短，可能未复制完整")
        if (key == secret && key.isNotEmpty()) issues.add("AppKey 与 AppSecret 内容相同，通常不应一致")
        val detail = buildString {
            append("AppKey ").append(mask(key))
            append("；AppSecret ").append(mask(secret))
            if (issues.isNotEmpty()) {
                append("\n⚠ ").append(issues.joinToString("\n⚠ "))
            }
        }
        return ConnResult("凭据检查", true, detail)
    }

    private fun post(url: String, payload: String, token: String?): JSONObject {
        val builder = Request.Builder().url(url).post(payload.toRequestBody(JSON))
        if (!token.isNullOrBlank()) builder.header("x-acs-dingtalk-access-token", token)
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val body = runCatching { if (text.isBlank()) JSONObject() else JSONObject(text) }.getOrDefault(JSONObject())
            val code = body.optString("code")
            val message = body.optString("message")
            val requestId = body.optString("requestId")
            if (!response.isSuccessful || (code.isNotEmpty() && message.isNotEmpty() && code != "0")) {
                throw DingTalkApiException(
                    httpCode = response.code,
                    code = code,
                    detail = if (message.isNotEmpty()) message else text.take(200),
                    requestId = requestId
                )
            }
            return body
        }
    }
}
