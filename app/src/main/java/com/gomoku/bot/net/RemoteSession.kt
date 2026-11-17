package com.gomoku.bot.net

import android.os.Handler
import android.os.Looper
import com.gomoku.bot.game.Stone
import java.util.UUID

/**
 * 联机会话：每台设备都运行一个内置钉钉机器人（HTTP 服务端），
 * 通过钉钉格式的 JSON 消息互相投递落子、聊天、悔棋/重开等事件。
 * 房主执黑并决定棋盘大小，加入方执白。
 */
object RemoteSession {

    enum class Role { NONE, HOST, GUEST }

    sealed class Event {
        data class Waiting(val url: String) : Event()
        data class Joined(val peerName: String) : Event()
        data class Message(val msg: BotMessage) : Event()
        data class Log(val text: String) : Event()
        data class Error(val text: String) : Event()
        data class Closed(val text: String) : Event()
    }

    var role: Role = Role.NONE
        private set
    var room: String = ""
        private set
    var myName: String = ""
        private set
    var myId: String = ""
        private set
    var myColor: Stone = Stone.BLACK
        private set
    var boardSize: Int = 15
        private set
    var winLength: Int = 5
        private set
    var port: Int = 18765
        private set
    var peerName: String = ""
        private set
    var connected: Boolean = false
        private set
    var mirrorWebhook: String = ""
        private set

    val active: Boolean
        get() = role != Role.NONE

    @Volatile
    var onEvent: ((Event) -> Unit)? = null

    private var server: DingTalkBotServer? = null
    private var peerUrl = ""
    private var seq = 0
    private val handler = Handler(Looper.getMainLooper())

    fun myUrl(): String = "http://${NetUtils.localIpAddress() ?: "127.0.0.1"}:$port/robot/send"

    @Synchronized
    fun startHost(
        size: Int,
        win: Int,
        roomCode: String,
        name: String,
        listenPort: Int,
        webhook: String
    ): String? {
        closeInternal()
        if (size !in 6..25) return "棋盘大小不合法"
        if (roomCode.isBlank()) return "请填写房间号"

        role = Role.HOST
        myColor = Stone.BLACK
        boardSize = size
        winLength = win.coerceIn(3, size)
        room = roomCode.trim()
        myName = name.ifBlank { "房主" }
        port = listenPort
        mirrorWebhook = webhook.trim()
        myId = UUID.randomUUID().toString().take(8)

        val bot = DingTalkBotServer(listenPort) { message -> process(message) }
        if (!bot.start()) {
            role = Role.NONE
            return "端口 $listenPort 无法监听，请更换端口或检查网络"
        }
        server = bot
        emit(Event.Waiting(myUrl()))
        emit(Event.Log("机器人已启动：${myUrl()}"))
        emit(Event.Log("房间 $room · 我执黑棋 · ${boardSize}×$boardSize · $winLength 连获胜"))
        emit(Event.Log("把上面的地址告诉对方，等待加入…"))
        return null
    }

    @Synchronized
    fun join(
        size: Int,
        win: Int,
        roomCode: String,
        name: String,
        listenPort: Int,
        peerAddress: String,
        webhook: String
    ): String? {
        closeInternal()
        if (roomCode.isBlank()) return "请填写房间号"
        val hostUrl = BotHttp.normalize(peerAddress) ?: return "对方机器人地址无效"

        role = Role.GUEST
        myColor = Stone.WHITE
        boardSize = size
        winLength = win
        room = roomCode.trim()
        myName = name.ifBlank { "玩家" }
        port = listenPort
        mirrorWebhook = webhook.trim()
        myId = UUID.randomUUID().toString().take(8)

        val bot = DingTalkBotServer(listenPort) { message -> process(message) }
        if (!bot.start()) {
            role = Role.NONE
            return "本机端口 $listenPort 无法监听，请更换端口"
        }
        server = bot
        peerUrl = hostUrl

        emit(Event.Log("本机机器人：${myUrl()}"))
        emit(Event.Log("正在连接房主：$hostUrl …"))
        Thread {
            try {
                val request = build("join", url = myUrl(), size = size, win = win)
                val response = BotHttp.post(hostUrl, BotProtocol.encode(request))
                BotProtocol.decode(response)?.let { reply ->
                    if (reply.type == "accept") process(reply)
                }
            } catch (e: Exception) {
                emit(Event.Error("连接失败：${e.message ?: "网络不可达"}"))
            }
        }.start()

        handler.postDelayed({
            if (role == Role.GUEST && !connected) {
                emit(Event.Error("等待房主响应超时，请确认对方已创建房间且处于同一局域网"))
            }
        }, 8000)
        return null
    }

    fun sendMove(x: Int, y: Int, moves: String) {
        dispatch(build("move", x = x, y = y, moves = moves))
    }

    fun sendChat(text: String) {
        dispatch(build("chat", text = text))
    }

    fun requestRestart() {
        dispatch(build("restart_req"))
    }

    fun replyRestart(accept: Boolean) {
        dispatch(build(if (accept) "restart_ok" else "restart_no"))
    }

    fun sendSurrender() {
        dispatch(build("surrender"))
    }

    fun sendLeave() {
        dispatch(build("leave"))
    }

    fun sendSync(moves: String) {
        dispatch(build("sync", moves = moves))
    }

    @Synchronized
    fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        role = Role.NONE
        connected = false
        peerUrl = ""
        peerName = ""
        seq = 0
    }

    @Synchronized
    private fun process(message: BotMessage): BotMessage? {
        if (message.room.isNotEmpty() && room.isNotEmpty() && message.room != room) {
            return null
        }
        return when (message.type) {
            "join" -> {
                if (role != Role.HOST || connected) {
                    null
                } else {
                    val guestUrl = BotHttp.normalize(message.url)
                    if (guestUrl == null) {
                        emit(Event.Error("收到无效的加入请求"))
                        null
                    } else {
                        peerUrl = guestUrl
                        peerName = message.name.ifBlank { "对手" }
                        connected = true
                        emit(Event.Log("$peerName 加入了房间"))
                        emit(Event.Joined(peerName))
                        build(
                            "accept",
                            name = myName,
                            url = myUrl(),
                            size = boardSize,
                            win = winLength
                        )
                    }
                }
            }

            "accept" -> {
                if (role == Role.GUEST && !connected) {
                    connected = true
                    peerName = message.name.ifBlank { "房主" }
                    if (message.size in 6..25) boardSize = message.size
                    if (message.win in 3..10) winLength = message.win.coerceAtMost(boardSize)
                    emit(Event.Log("已加入 $peerName 的房间 $room"))
                    emit(Event.Joined(peerName))
                }
                null
            }

            "leave" -> {
                emit(Event.Closed("${message.name.ifBlank { "对方" }} 离开了房间"))
                null
            }

            else -> {
                if (connected) emit(Event.Message(message))
                null
            }
        }
    }

    private fun build(
        type: String,
        x: Int = -1,
        y: Int = -1,
        text: String = "",
        moves: String = "",
        size: Int = boardSize,
        win: Int = winLength,
        color: Int = myColor.value,
        name: String = myName,
        url: String = ""
    ): BotMessage = BotMessage(
        type = type,
        room = room,
        seq = ++seq,
        from = myId,
        name = name,
        color = color,
        x = x,
        y = y,
        text = text,
        size = size,
        win = win,
        moves = moves,
        url = url
    )

    private fun dispatch(message: BotMessage) {
        val target = peerUrl
        if (!connected || target.isBlank()) return
        val payload = BotProtocol.encode(message)
        Thread {
            try {
                BotHttp.post(target, payload)
            } catch (e: Exception) {
                try {
                    Thread.sleep(500)
                    BotHttp.post(target, payload)
                } catch (second: Exception) {
                    emit(Event.Error("消息发送失败：${second.message ?: "网络异常"}"))
                }
            }
            mirror(message, payload)
        }.start()
    }

    /** 可选：把对局动态同步到真实的钉钉群机器人，便于围观。 */
    private fun mirror(message: BotMessage, payload: String) {
        val hook = mirrorWebhook
        if (hook.isBlank()) return
        if (message.type != "move" && message.type != "chat" && message.type != "accept") return
        try {
            val url = BotHttp.normalize(hook) ?: return
            BotHttp.post(url, payload, 4000)
        } catch (_: Exception) {
        }
    }

    private fun emit(event: Event) {
        handler.post { onEvent?.invoke(event) }
    }
}
