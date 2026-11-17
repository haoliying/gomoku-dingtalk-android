package com.gomoku.bot.game

/**
 * 棋子颜色。棋盘内部用 0/1/2 存储，便于 AI 使用。
 */
enum class Stone(val value: Int) {
    EMPTY(0),
    BLACK(1),
    WHITE(2);

    val opponent: Stone
        get() = if (this == BLACK) WHITE else BLACK

    companion object {
        fun of(value: Int): Stone = when (value) {
            1 -> BLACK
            2 -> WHITE
            else -> EMPTY
        }

        fun of(value: String?): Stone = when (value) {
            "black" -> BLACK
            "white" -> WHITE
            else -> EMPTY
        }

        fun nameOf(stone: Stone): String = when (stone) {
            BLACK -> "black"
            WHITE -> "white"
            EMPTY -> "empty"
        }
    }
}

data class Move(val x: Int, val y: Int, val stone: Stone)

/**
 * 五子棋规则引擎：支持任意棋盘尺寸与任意连珠长度。
 */
class GameEngine(val size: Int, val winLength: Int) {

    private val cells = IntArray(size * size)
    private val history = ArrayList<Move>()

    var current: Stone = Stone.BLACK
        private set

    var winner: Stone? = null
        private set

    var winningLine: List<Move> = emptyList()
        private set

    var isDraw: Boolean = false
        private set

    val moves: List<Move>
        get() = history

    val moveCount: Int
        get() = history.size

    val lastMove: Move?
        get() = history.lastOrNull()

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size

    fun stoneAt(x: Int, y: Int): Stone =
        if (inBounds(x, y)) Stone.of(cells[y * size + x]) else Stone.EMPTY

    fun isEmpty(x: Int, y: Int): Boolean = inBounds(x, y) && cells[y * size + x] == 0

    fun cellsCopy(): IntArray = cells.copyOf()

    fun place(x: Int, y: Int): Boolean {
        if (winner != null || isDraw) return false
        if (!inBounds(x, y) || cells[y * size + x] != 0) return false

        cells[y * size + x] = current.value
        history.add(Move(x, y, current))

        val line = findWinningLine(x, y, current)
        if (line != null) {
            winner = current
            winningLine = line
        } else if (history.size == size * size) {
            isDraw = true
        } else {
            current = current.opponent
        }
        return true
    }

    fun undo(): Move? {
        if (history.isEmpty()) return null
        val last = history.removeAt(history.size - 1)
        cells[last.y * size + last.x] = 0
        winner = null
        winningLine = emptyList()
        isDraw = false
        current = last.stone
        return last
    }

    fun reset() {
        cells.fill(0)
        history.clear()
        current = Stone.BLACK
        winner = null
        winningLine = emptyList()
        isDraw = false
    }

    /**
     * 根据完整的落子顺序重建棋局，用于联机同步与断线恢复。
     */
    fun rebuild(moves: List<Pair<Int, Int>>) {
        reset()
        for ((x, y) in moves) {
            if (!place(x, y)) break
            if (winner != null) break
        }
    }

    /** 序列化为 "x,y;x,y" 形式，用于网络传输。 */
    fun snapshot(): String = history.joinToString(";") { "${it.x},${it.y}" }

    private fun findWinningLine(x: Int, y: Int, stone: Stone): List<Move>? {
        for (d in 0 until 4) {
            val dx = DX[d]
            val dy = DY[d]
            val line = ArrayList<Move>(winLength + 2)
            line.add(Move(x, y, stone))
            for (sign in intArrayOf(-1, 1)) {
                var cx = x + dx * sign
                var cy = y + dy * sign
                while (inBounds(cx, cy) && cells[cy * size + cx] == stone.value) {
                    line.add(Move(cx, cy, stone))
                    cx += dx * sign
                    cy += dy * sign
                }
            }
            if (line.size >= winLength) {
                return line.sortedWith(compareBy({ it.y }, { it.x }))
            }
        }
        return null
    }

    companion object {
        val DX = intArrayOf(1, 0, 1, 1)
        val DY = intArrayOf(0, 1, 1, -1)
    }
}
