package com.gomoku.bot.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 内置在 App 里的“钉钉机器人” HTTP 服务端。
 * 兼容钉钉自定义机器人消息格式：POST /robot/send，Content-Type: application/json。
 */
class DingTalkBotServer(
    private val port: Int,
    private val handler: (BotMessage) -> BotMessage?
) {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = Executors.newCachedThreadPool()

    @Volatile
    private var running = false

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            serverSocket = socket
            running = true
            acceptThread = Thread({ acceptLoop() }, "gomoku-bot-$port").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "无法在端口 $port 启动机器人", e)
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread = null
        workers.shutdownNow()
    }

    val isRunning: Boolean
        get() = running

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket?.accept()
            } catch (e: IOException) {
                null
            } ?: break
            try {
                workers.execute { handle(socket) }
            } catch (e: Exception) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 6000
                val input = socket.getInputStream()
                val headerBytes = ByteArrayOutputStream()
                var b3 = 0
                var b2 = 0
                var b1 = 0
                var current = 0
                while (true) {
                    current = input.read()
                    if (current == -1) break
                    headerBytes.write(current)
                    if (b3 == '\r'.code && b2 == '\n'.code && b1 == '\r'.code && current == '\n'.code) break
                    b3 = b2
                    b2 = b1
                    b1 = current
                    if (headerBytes.size() > 32 * 1024) break
                }
                val header = headerBytes.toByteArray().toString(Charsets.ISO_8859_1)
                val lines = header.split("\r\n")
                if (lines.isEmpty() || lines[0].isBlank()) return

                val requestParts = lines[0].split(' ')
                val method = requestParts.getOrElse(0) { "GET" }
                val target = requestParts.getOrElse(1) { "/" }

                var contentLength = 0
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val bodyBytes = if (contentLength > 0) {
                    val buffer = ByteArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val read = input.read(buffer, offset, contentLength - offset)
                        if (read <= 0) break
                        offset += read
                    }
                    buffer.copyOf(offset)
                } else {
                    ByteArray(0)
                }
                val body = bodyBytes.toString(Charsets.UTF_8)
                val path = target.substringBefore('?')

                val (status, response) = when {
                    path.endsWith("/robot/send") -> {
                        val message = BotProtocol.decode(body)
                        if (message == null) {
                            HTTP_OK to errorJson(400, "无效的消息格式")
                        } else {
                            val reply = try {
                                handler(message)
                            } catch (e: Exception) {
                                null
                            }
                            HTTP_OK to (reply?.let { BotProtocol.encode(it) } ?: okJson())
                        }
                    }
                    path == "/" || path == "/status" -> HTTP_OK to statusJson()
                    path == "/ping" -> HTTP_OK to okJson()
                    method == "OPTIONS" -> HTTP_OK to okJson()
                    else -> HTTP_NOT_FOUND to errorJson(404, "not found")
                }

                val bytes = response.toByteArray(Charsets.UTF_8)
                val output = socket.getOutputStream()
                val head = buildString {
                    append("HTTP/1.1 $status\r\n")
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(head.toByteArray(Charsets.US_ASCII))
                output.write(bytes)
                output.flush()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "处理机器人请求失败", e)
            }
        }
    }

    private fun statusJson(): String =
        "{\"errcode\":0,\"errmsg\":\"ok\",\"app\":\"GomokuDingTalkBot\",\"v\":${BotProtocol.VERSION},\"port\":$port}"

    private fun okJson(): String = "{\"errcode\":0,\"errmsg\":\"ok\"}"

    private fun errorJson(code: Int, message: String): String =
        "{\"errcode\":$code,\"errmsg\":\"$message\"}"

    companion object {
        private const val TAG = "GomokuBot"
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404
    }
}
