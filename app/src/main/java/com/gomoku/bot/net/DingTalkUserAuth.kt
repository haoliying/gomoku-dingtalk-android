package com.gomoku.bot.net

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 设备授权码（RFC 8628）。 */
data class DeviceAuth(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val interval: Int,
    val expiresIn: Int
)

/** 用户级 accessToken。 */
data class UserToken(
    val accessToken: String,
    val refreshToken: String,
    val expireIn: Long
)

/**
 * 钉钉用户身份登录（设备码模式）。
 *
 * 企业内部应用的 App 凭据只能拿到应用 token，读不到群消息；
 * 群消息必须用「用户身份」订阅，因此需要用户登录授权一次。
 *
 * 流程：
 * 1. POST /oauth2/device/code.json 取 device_code + user_code
 * 2. 用户在钉钉里确认
 * 3. POST /oauth2/device/token.json 轮询拿到 authCode
 * 4. POST /v1.0/oauth2/userAccessToken 用 authCode + 应用密钥换用户 token
 */
object DingTalkUserAuth {

    private const val LOGIN = "https://login.dingtalk.com"
    private const val API = "https://api.dingtalk.com"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun requestDeviceCode(appKey: String): DeviceAuth {
        val body = FormBody.Builder()
            .add("client_id", appKey)
            .add("scope", "openid corpid")
            .build()
        val root = postForm("$LOGIN/oauth2/device/code.json", body)
        if (!root.optBoolean("success", true)) {
            throw IllegalStateException(describe(root, "申请授权码失败"))
        }
        val r = unwrap(root)
        val deviceCode = r.optString("deviceCode")
        val userCode = r.optString("userCode")
        if (deviceCode.isBlank() || userCode.isBlank()) {
            throw IllegalStateException("授权服务未返回 device_code/user_code：${root.toString().take(200)}")
        }
        return DeviceAuth(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = r.optString("verificationUri", "https://login.dingtalk.com/oauth2/device/verify"),
            verificationUriComplete = r.optString("verificationUriComplete"),
            interval = r.optInt("interval", 3).coerceIn(2, 15),
            expiresIn = r.optInt("expiresIn", 900).coerceIn(60, 1800)
        )
    }

    /**
     * 轮询一次授权状态。
     * @return authCode；null 表示仍在等待用户确认。
     */
    fun pollAuthCode(appKey: String, deviceCode: String): String? {
        val body = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("device_code", deviceCode)
            .add("client_id", appKey)
            .build()
        val root = postForm("$LOGIN/oauth2/device/token.json", body)
        val ok = root.optBoolean("success", true)
        val r = unwrap(root)
        val code = r.optString("authCode")
        if (ok && code.isNotBlank()) return code
        val err = root.optString("errorCode").ifBlank {
            root.optString("error").ifBlank { r.optString("error") }
        }
        val msg = describe(root, "")
        if (!ok) {
            val pending = err.contains("pending", true) ||
                err.contains("slow_down", true) ||
                msg.contains("等待", true) ||
                msg.contains("pending", true)
            if (pending) return null
            throw IllegalStateException(msg)
        }
        return null
    }

    /** 用 authCode 换取用户 accessToken。 */
    fun exchange(appKey: String, appSecret: String, authCode: String): UserToken {
        val body = JSONObject()
            .put("clientId", appKey)
            .put("clientSecret", appSecret)
            .put("code", authCode)
            .put("grantType", "authorization_code")
        val root = postJson("$API/v1.0/oauth2/userAccessToken", body.toString())
        val token = root.optString("accessToken")
        if (token.isBlank()) {
            throw IllegalStateException(describe(root, "换取用户 token 失败"))
        }
        return UserToken(
            accessToken = token,
            refreshToken = root.optString("refreshToken"),
            expireIn = root.optLong("expireIn", 7200L)
        )
    }

    private fun unwrap(root: JSONObject): JSONObject =
        root.optJSONObject("result")
            ?: root.optJSONObject("data")
            ?: root

    private fun describe(root: JSONObject, fallback: String): String {
        val code = root.optString("errorCode").ifBlank { root.optString("code") }
        val msg = root.optString("errorMsg").ifBlank { root.optString("message") }
        return when {
            code.isNotBlank() && msg.isNotBlank() -> "$code · $msg"
            msg.isNotBlank() -> msg
            code.isNotBlank() -> code
            else -> fallback.ifBlank { root.toString().take(200) }
        }
    }

    private fun postForm(url: String, body: FormBody): JSONObject = execute(
        Request.Builder().url(url).post(body).build()
    )

    private fun postJson(url: String, payload: String): JSONObject = execute(
        Request.Builder().url(url)
            .post(payload.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()
    )

    private fun execute(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val body = runCatching {
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }.getOrDefault(JSONObject())
            if (!response.isSuccessful) {
                val detail = describe(body, "HTTP ${response.code}")
                throw DingTalkApiException(
                    httpCode = response.code,
                    code = body.optString("errorCode").ifBlank { body.optString("code") },
                    detail = detail,
                    requestId = body.optString("requestId")
                )
            }
            return body
        }
    }
}
