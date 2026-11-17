package com.gomoku.bot.net

import org.json.JSONObject

/**
 * 内置钉钉机器人的消息体。
 * 线上格式与钉钉自定义机器人保持一致（msgtype/text），
 * 额外携带 gomoku 字段用于棋局同步。
 */
data class BotMessage(
    val type: String = "",
    val room: String = "",
    val seq: Int = 0,
    val from: String = "",
    val name: String = "",
    val color: Int = 0,
    val x: Int = -1,
    val y: Int = -1,
    val text: String = "",
    val size: Int = 0,
    val win: Int = 0,
    val moves: String = "",
    val url: String = ""
)

object BotProtocol {

    const val VERSION = 1

    fun encode(message: BotMessage): String {
        val root = JSONObject()
        root.put("msgtype", "text")
        root.put("text", JSONObject().put("content", toContent(message)))

        val gomoku = JSONObject()
        gomoku.put("v", VERSION)
        gomoku.put("type", message.type)
        if (message.room.isNotEmpty()) gomoku.put("room", message.room)
        gomoku.put("seq", message.seq)
        if (message.from.isNotEmpty()) gomoku.put("from", message.from)
        if (message.name.isNotEmpty()) gomoku.put("name", message.name)
        if (message.color != 0) gomoku.put("color", message.color)
        if (message.x >= 0) gomoku.put("x", message.x)
        if (message.y >= 0) gomoku.put("y", message.y)
        if (message.text.isNotEmpty()) gomoku.put("text", message.text)
        if (message.size > 0) gomoku.put("size", message.size)
        if (message.win > 0) gomoku.put("win", message.win)
        if (message.moves.isNotEmpty()) gomoku.put("moves", message.moves)
        if (message.url.isNotEmpty()) gomoku.put("url", message.url)
        root.put("gomoku", gomoku)
        return root.toString()
    }

    fun decode(raw: String): BotMessage? {
        return try {
            val root = JSONObject(raw)
            val gomoku = root.optJSONObject("gomoku") ?: return null
            BotMessage(
                type = gomoku.optString("type"),
                room = gomoku.optString("room"),
                seq = gomoku.optInt("seq", 0),
                from = gomoku.optString("from"),
                name = gomoku.optString("name"),
                color = gomoku.optInt("color", 0),
                x = gomoku.optInt("x", -1),
                y = gomoku.optInt("y", -1),
                text = gomoku.optString("text"),
                size = gomoku.optInt("size", 0),
                win = gomoku.optInt("win", 0),
                moves = gomoku.optString("moves"),
                url = gomoku.optString("url")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun parseMoves(raw: String): List<Pair<Int, Int>> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { part ->
            val pieces = part.split(',')
            val x = pieces.getOrNull(0)?.trim()?.toIntOrNull()
            val y = pieces.getOrNull(1)?.trim()?.toIntOrNull()
            if (x == null || y == null) null else x to y
        }
    }

    fun coordinate(x: Int, y: Int): String = "${('A' + x)}${y + 1}"

    fun errorMessage(raw: String): String {
        return try {
            val json = JSONObject(raw)
            val code = json.optInt("errcode", 0)
            val msg = json.optString("errmsg")
            when {
                code != 0 && msg.isNotEmpty() -> "errcode=$code $msg"
                msg.isNotEmpty() -> msg
                else -> "对方未响应"
            }
        } catch (e: Exception) {
            if (raw.isBlank()) "对方未响应" else raw.take(80)
        }
    }

    private fun toContent(message: BotMessage): String {
        val who = message.name.ifBlank { "对手" }
        return when (message.type) {
            "join" -> "【五子棋】$who 申请加入房间 ${message.room}"
            "accept" -> "【五子棋】$who 已加入房间 ${message.room}，对局开始（${message.size}×${message.size}，${message.win} 连获胜）"
            "move" -> {
                val count = if (message.moves.isEmpty()) 0 else message.moves.split(';').size
                "【五子棋】$who 落子 ${coordinate(message.x, message.y)}，第 $count 手"
            }
            "chat" -> "【五子棋】$who：${message.text}"
            "restart_req" -> "【五子棋】$who 请求重新开始"
            "restart_ok" -> "【五子棋】$who 同意重新开始"
            "restart_no" -> "【五子棋】$who 拒绝了重新开始"
            "surrender" -> "【五子棋】$who 认输"
            "leave" -> "【五子棋】$who 离开了房间"
            "sync" -> "【五子棋】$who 同步了棋局"
            else -> "【五子棋】$who：${message.type}"
        }
    }
}
