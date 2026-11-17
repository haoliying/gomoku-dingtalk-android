package com.gomoku.bot.net

/**
 * 钉钉文本消息的生成与解析。
 * 棋盘侧（App）把棋局渲染成适合在钉钉里阅读的文本；
 * 钉钉侧玩家回复坐标（如 H8）或指令（重开/认输/同步）即可继续对局。
 */
object DingTalkText {

    private val coordinateRegex = Regex("^([A-Za-z])\\s*[,，:：]?\\s*(\\d{1,2})$")
    private val roomMarkerRegex = Regex("^[#＃【\\[]\\s*(\\d{2,12})\\s*[】\\]]?\\s*[:：,，]?\\s*")

    fun coordinate(x: Int, y: Int): String = "${('A' + x)}${y + 1}"

    fun parseCoordinate(token: String): Pair<Int, Int>? {
        val match = coordinateRegex.matchEntire(token.trim()) ?: return null
        val x = match.groupValues[1].uppercase()[0] - 'A'
        val y = (match.groupValues[2].toIntOrNull() ?: return null) - 1
        if (x < 0 || y < 0) return null
        return x to y
    }

    /** 渲染纯文本棋盘，顶部为最大行号。 */
    fun board(size: Int, moves: List<Pair<Int, Int>>): String {
        val cells = CharArray(size * size) { '·' }
        moves.forEachIndexed { index, (x, y) ->
            if (x in 0 until size && y in 0 until size) {
                cells[y * size + x] = if (index % 2 == 0) '●' else '○'
            }
        }
        val builder = StringBuilder()
        builder.append("   ")
        for (x in 0 until size) builder.append(('A' + x)).append(' ')
        builder.append('\n')
        for (y in size - 1 downTo 0) {
            builder.append((y + 1).toString().padStart(2, ' ')).append(' ')
            for (x in 0 until size) builder.append(cells[y * size + x]).append(' ')
            builder.append('\n')
        }
        return builder.toString().trimEnd()
    }

    /**
     * 生成发给对手的棋局文本。
     * myColor 为 App 侧玩家颜色；对手看到的是「轮到你」提示。
     */
    fun boardMessage(
        room: String,
        nickname: String,
        myColor: Int,
        size: Int,
        win: Int,
        moves: List<Pair<Int, Int>>,
        extra: String = ""
    ): String {
        val blackTurn = moves.size % 2 == 0
        val turnColor = if (blackTurn) 1 else 2
        val last = moves.lastOrNull()
        val builder = StringBuilder()
        builder.append("【五子棋 · 房间 ").append(room).append("】")
        if (moves.isEmpty()) {
            builder.append("开局")
        } else {
            builder.append("第 ").append(moves.size).append(" 手")
            if (last != null) {
                builder.append("：").append(if (blackTurn) "白" else "黑").append(' ')
                    .append(coordinate(last.first, last.second))
            }
        }
        builder.append('\n')
        builder.append(size).append('×').append(size).append(" · ").append(win).append(" 连获胜")
        if (nickname.isNotBlank()) builder.append(" · ").append(nickname)
        builder.append('\n')
        if (turnColor == myColor) {
            builder.append("等待对方落子。\n")
        } else {
            builder.append("轮到你落子：直接回复坐标（如 ").append(sampleCoordinate(size)).append("）即可。\n")
        }
        if (extra.isNotBlank()) builder.append(extra).append('\n')
        builder.append('\n').append(board(size, moves))
        return builder.toString()
    }

    /**
     * 解析钉钉侧玩家发来的文本。
     * pendingRestart 为真时，回复「重开」被视为同意。
     */
    fun parse(raw: String, size: Int, room: String, pendingRestart: Boolean): BotMessage? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        if (text.startsWith("{")) {
            BotProtocol.decode(text)?.let { return it }
        }

        var body = text
        roomMarkerRegex.find(body)?.let { match ->
            val markerRoom = match.groupValues[1]
            if (room.isNotBlank() && markerRoom != room) return null
            body = body.removeRange(match.range).trim()
        }
        if (body.isEmpty()) return null

        if (body.contains("认输")) {
            return BotMessage(type = "surrender", room = room, text = body)
        }
        if (body.contains("重开") || body.contains("重来") || body.contains("再来")) {
            return BotMessage(type = if (pendingRestart) "restart_ok" else "restart_req", room = room, text = body)
        }
        if (body.contains("同步") || body.contains("棋盘")) {
            return BotMessage(type = "sync_req", room = room, text = body)
        }
        if (body.startsWith("/")) {
            return null
        }

        val cleaned = body.replace(Regex("[\\s,，。.!！]"), "")
        val coordinate = parseCoordinate(cleaned) ?: parseCoordinate(body) ?: return null
        val (x, y) = coordinate
        if (x !in 0 until size || y !in 0 until size) return null
        return BotMessage(type = "move", room = room, x = x, y = y, text = body)
    }

    private fun sampleCoordinate(size: Int): String {
        val x = (size - 1) / 2
        val y = (size - 1) / 2
        return coordinate(x, y)
    }
}
