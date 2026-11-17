package com.gomoku.bot.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 机器人消息发送器：向局域网内的另一台设备（内置机器人）或钉钉群 Webhook 投递消息。
 */
object BotHttp {

    fun normalize(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
            value = "http://$value"
        }
        return try {
            val url = URL(value)
            if (url.host.isNullOrBlank()) return null
            val path = url.path.orEmpty()
            if (path.isBlank() || path == "/") {
                URL(url.protocol, url.host, url.port, "/robot/send").toString()
            } else {
                value
            }
        } catch (e: Exception) {
            null
        }
    }

    fun post(url: String, body: String, timeoutMs: Int = 5000): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("User-Agent", "GomokuDingTalkBot/1.0")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code ${text.take(120)}")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }
}
