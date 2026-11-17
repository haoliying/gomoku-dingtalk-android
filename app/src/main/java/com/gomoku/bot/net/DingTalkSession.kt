package com.gomoku.bot.net

import android.os.Handler
import android.os.Looper
import com.gomoku.bot.game.Stone
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 钉钉联机会话（App 侧）：
 * - 通过企业应用机器人的 Stream 长连接接收对手在钉钉里的回复；
 * - 通过机器人单聊/群聊把棋局文本发给对手；
 * - 对手无需安装 App，直接在钉钉里回复坐标即可落子。
 */
object DingTalkSession {

    sealed class Event {
        object Started : Event()
        data class Message(val msg: BotMessage) : Event()
        data class Log(val text: String) : Event()
        data class Error(val text: String) : Event()
        data class Closed(val text: String) : Event()
    }

    var active: Boolean = false
        private set
    var connected: Boolean = false
        private set
    var myName: String = ""
        private set
    var peerName: String = "对手"
    var myColor: Stone = Stone.BLACK
        private set
    var boardSize: Int = 15
        private set
    var winLength: Int = 5
        private set
    var room: String = ""
        private set
    var config: DingTalkConfig? = null
        private set

    @Volatile
    var onEvent: ((Event) -> Unit)? = null

    private var stream: DingTalkStreamClient? = null
    private var pendingRestart = false
    private val handler = Handler(Looper.getMainLooper())
    private val sender: ExecutorService = Executors.newSingleThreadExecutor()

    @Synchronized
    fun start(
        config: DingTalkConfig,
        size: Int,
        win: Int,
        color: Stone,
        name: String
    ): String? {
        close()
        if (config.appKey.isBlank()) return "请填写 AppKey"
        if (config.appSecret.isBlank()) return "请填写 AppSecret"
        if (config.robotCode.isBlank()) return "请填写 RobotCode"
        if (config.peerUserId.isBlank() && config.openConversationId.isBlank()) {
            return "请填写对方钉钉 userId（或群会话 ID）"
        }
        if (size !in 6..25) return "棋盘大小不合法"

        this.config = config
        this.room = config.room
        this.myName = name.ifBlank { "玩家" }
        this.peerName = "对手"
        this.myColor = color
        this.boardSize = size
        this.winLength = win.coerceIn(3, size)
        this.active = true
        this.connected = true
        this.pendingRestart = false

        val client = DingTalkStreamClient(
            config = config,
            onBotMessage = ::onBotPayload,
            onLog = { emit(Event.Log(it)) },
            onError = { emit(Event.Error(it)) },
            onReady = { emit(Event.Log("机器人已就绪，对手在钉钉里的回复会自动同步")) }
        )
        stream = client
        client.start()
        emit(Event.Started)
        emit(Event.Log("房间 $room · 我执${if (color == Stone.BLACK) "黑" else "白"} · ${size}×$size · $win 连获胜"))
        emit(Event.Log("正在连接钉钉机器人 ${config.robotCode}…"))
        return null
    }

    /** App 侧落子后，把最新棋局发给对手。 */
    fun sendMove(x: Int, y: Int, snapshot: String) {
        val cfg = config ?: return
        val moves = BotProtocol.parseMoves(snapshot)
        sendText(
            DingTalkText.boardMessage(
                room = cfg.room,
                nickname = myName,
                myColor = myColor.value,
                size = boardSize,
                win = winLength,
                moves = moves
            )
        )
    }

    /** 同步棋盘（对手落子后、请求同步、重开等场景）。 */
    fun sendBoard(snapshot: String, extra: String = "") {
        val cfg = config ?: return
        sendText(
            DingTalkText.boardMessage(
                room = cfg.room,
                nickname = myName,
                myColor = myColor.value,
                size = boardSize,
                win = winLength,
                moves = BotProtocol.parseMoves(snapshot),
                extra = extra
            )
        )
    }

    fun sendChat(text: String) {
        if (text.isBlank()) return
        sendText("【五子棋 · 房间 $room】$myName：$text")
    }

    fun requestRestart() {
        pendingRestart = true
        sendText("【五子棋 · 房间 $room】请求重新开始，回复「重开」同意。")
    }

    fun replyRestart(accept: Boolean) {
        pendingRestart = false
        sendText(if (accept) "【五子棋】同意重开，新的一局开始。" else "【五子棋】本局继续。")
    }

    fun sendSurrender() {
        sendText("【五子棋 · 房间 $room】$myName 认输。")
    }

    fun sendLeave() {
        sendText("【五子棋 · 房间 $room】对局结束。")
    }

    fun sendSync(snapshot: String) {
        sendBoard(snapshot, "已同步最新棋局。")
    }

    @Synchronized
    fun close() {
        try {
            stream?.stop()
        } catch (_: Exception) {
        }
        stream = null
        active = false
        connected = false
        pendingRestart = false
        config = null
        room = ""
    }

    private fun sendText(text: String) {
        val cfg = config ?: return
        sender.execute {
            try {
                var sent = false
                if (cfg.peerUserId.isNotBlank()) {
                    DingTalkApi.sendOto(cfg, text)
                    sent = true
                }
                if (cfg.openConversationId.isNotBlank()) {
                    try {
                        DingTalkApi.sendGroup(cfg, text)
                        sent = true
                    } catch (e: Exception) {
                        if (!sent) emit(Event.Error("群消息发送失败：${e.message ?: "网络异常"}"))
                    }
                }
                if (!sent) {
                    emit(Event.Error("没有可用的发送目标，请填写对方 userId 或群会话 ID"))
                }
            } catch (e: Exception) {
                emit(Event.Error("钉钉消息发送失败：${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    private fun onBotPayload(json: JSONObject) {
        val cfg = config ?: return
        val msgtype = json.optString("msgtype")
        if (msgtype.isNotEmpty() && msgtype != "text") return
        val content = json.optJSONObject("text")?.optString("content").orEmpty()
        if (content.isBlank()) return

        val senderId = json.optString("senderStaffId")
        if (cfg.peerUserId.isNotBlank() && senderId.isNotBlank() && senderId != cfg.peerUserId) {
            emit(Event.Log("已忽略非对手消息（$senderId）"))
            return
        }

        val message = DingTalkText.parse(content, boardSize, room, pendingRestart)
        if (message == null) {
            emit(Event.Log("未识别的指令：${content.trim().take(40)}"))
            return
        }
        if (message.type == "restart_ok") pendingRestart = false
        emit(Event.Message(message))
    }

    private fun emit(event: Event) {
        handler.post { onEvent?.invoke(event) }
    }
}
