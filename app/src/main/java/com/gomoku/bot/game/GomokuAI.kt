package com.gomoku.bot.game

import kotlin.math.abs
import kotlin.random.Random

/**
 * 五子棋 AI：启发式评估 + Alpha-Beta 搜索。
 * 三个难度分别对应贪心、两层搜索、四层搜索。
 */
class GomokuAI(private val size: Int, private val win: Int) {

    enum class Level(
        val label: String,
        val desc: String,
        val depth: Int,
        val candidates: Int,
        val timeMs: Long
    ) {
        EASY("简单", "随手落子，适合热手", 0, 10, 250L),
        MEDIUM("中等", "会进攻也会防守", 2, 12, 700L),
        HARD("困难", "四层搜索，步步紧逼", 4, 10, 1600L);

        companion object {
            fun fromIndex(index: Int): Level = entries.getOrElse(index) { MEDIUM }
        }
    }

    private var board = IntArray(0)
    private var me = 1
    private var op = 2
    private var deadline = 0L
    private var timedOut = false
    private var maxDepth = 0

    fun findBestMove(source: IntArray, aiStone: Stone, level: Level): Move? {
        board = source.copyOf()
        me = aiStone.value
        op = aiStone.opponent.value
        maxDepth = level.depth
        timedOut = false
        deadline = System.currentTimeMillis() + level.timeMs

        var stones = 0
        for (v in board) if (v != 0) stones++
        if (stones == 0) return Move(size / 2, size / 2, aiStone)

        val candidates = collectCandidates()
        if (candidates.isEmpty()) return null

        // 自己能赢，立刻赢
        for (idx in candidates) {
            board[idx] = me
            val won = isWinAt(idx, me)
            board[idx] = 0
            if (won) return idxToMove(idx, aiStone)
        }

        // 对方下一步能赢，必须堵
        val blocks = ArrayList<Int>()
        for (idx in candidates) {
            board[idx] = op
            val opponentWins = isWinAt(idx, op)
            board[idx] = 0
            if (opponentWins) blocks.add(idx)
        }
        if (blocks.isNotEmpty()) {
            val best = blocks.maxByOrNull { scoreCell(it, me) + scoreCell(it, op) }!!
            return idxToMove(best, aiStone)
        }

        val ordered = candidates
            .map { it to (scoreCell(it, me) + scoreCell(it, op) * 9 / 10) }
            .sortedByDescending { it.second }
            .map { it.first }

        if (level == Level.EASY) {
            val pool = ordered.take(minOf(4, ordered.size))
            val pick = pool[Random.nextInt(pool.size)]
            return idxToMove(pick, aiStone)
        }

        var bestIdx = ordered[0]
        var bestValue = -INF
        val rootLimit = minOf(level.candidates, ordered.size)
        for (i in 0 until rootLimit) {
            val idx = ordered[i]
            board[idx] = me
            val value = minimax(level.depth - 1, -INF, INF, false)
            board[idx] = 0
            if (value > bestValue) {
                bestValue = value
                bestIdx = idx
            }
            if (timedOut) break
        }
        return idxToMove(bestIdx, aiStone)
    }

    private fun minimax(depthLeft: Int, alphaIn: Int, betaIn: Int, maximizing: Boolean): Int {
        if (timedOut) return evaluate()
        if (System.currentTimeMillis() > deadline) {
            timedOut = true
            return evaluate()
        }
        if (depthLeft <= 0) return evaluate()

        val stone = if (maximizing) me else op
        val candidates = orderedCandidatesFor(stone, 8)
        if (candidates.isEmpty()) return evaluate()

        var alpha = alphaIn
        var beta = betaIn
        var best = if (maximizing) -INF else INF
        val ply = maxDepth - depthLeft

        for (idx in candidates) {
            board[idx] = stone
            val won = isWinAt(idx, stone)
            val value = if (won) {
                if (maximizing) WIN_SCORE - ply else -WIN_SCORE + ply
            } else {
                minimax(depthLeft - 1, alpha, beta, !maximizing)
            }
            board[idx] = 0

            if (maximizing) {
                if (value > best) best = value
                if (best > alpha) alpha = best
            } else {
                if (value < best) best = value
                if (best < beta) beta = best
            }
            if (alpha >= beta) break
            if (timedOut) break
        }
        return best
    }

    private fun orderedCandidatesFor(stone: Int, limit: Int): List<Int> {
        val opponent = if (stone == me) op else me
        return collectCandidates()
            .map { it to (scoreCell(it, stone) * 2 + scoreCell(it, opponent)) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun collectCandidates(): List<Int> {
        val result = ArrayList<Int>(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val idx = y * size + x
                if (board[idx] != 0) continue
                if (hasNeighbor(x, y, 2)) result.add(idx)
            }
        }
        return result
    }

    private fun hasNeighbor(x: Int, y: Int, radius: Int): Boolean {
        val x0 = maxOf(0, x - radius)
        val x1 = minOf(size - 1, x + radius)
        val y0 = maxOf(0, y - radius)
        val y1 = minOf(size - 1, y + radius)
        for (cy in y0..y1) {
            for (cx in x0..x1) {
                if (board[cy * size + cx] != 0) return true
            }
        }
        return false
    }

    private fun isWinAt(index: Int, stone: Int): Boolean {
        val x = index % size
        val y = index / size
        for (d in 0 until 4) {
            var count = 1
            var cx = x + DX[d]
            var cy = y + DY[d]
            while (cx in 0 until size && cy in 0 until size && board[cy * size + cx] == stone) {
                count++
                cx += DX[d]
                cy += DY[d]
            }
            cx = x - DX[d]
            cy = y - DY[d]
            while (cx in 0 until size && cy in 0 until size && board[cy * size + cx] == stone) {
                count++
                cx -= DX[d]
                cy -= DY[d]
            }
            if (count >= win) return true
        }
        return false
    }

    /**
     * 假设在 index 处落子后，四个方向形成的棋型分之和，用于候选点排序。
     */
    private fun scoreCell(index: Int, stone: Int): Int {
        val x = index % size
        val y = index / size
        var total = 0
        for (d in 0 until 4) {
            val dx = DX[d]
            val dy = DY[d]
            var count = 1
            var open = 0

            var cx = x + dx
            var cy = y + dy
            var steps = 1
            while (cx in 0 until size && cy in 0 until size &&
                board[cy * size + cx] == stone && steps < win
            ) {
                count++
                cx += dx
                cy += dy
                steps++
            }
            if (cx in 0 until size && cy in 0 until size && board[cy * size + cx] == 0) open++

            cx = x - dx
            cy = y - dy
            steps = 1
            while (cx in 0 until size && cy in 0 until size &&
                board[cy * size + cx] == stone && steps < win
            ) {
                count++
                cx -= dx
                cy -= dy
                steps++
            }
            if (cx in 0 until size && cy in 0 until size && board[cy * size + cx] == 0) open++

            total += patternScore(count, open)
        }
        // 略微偏向中心，避免同分时的随机边角
        val center = size / 2
        total += (size - abs(x - center) - abs(y - center)) / 2
        return total
    }

    private fun patternScore(count: Int, openEnds: Int): Int = when {
        count >= win -> 5_000_000
        count == win - 1 -> when (openEnds) {
            2 -> 400_000
            1 -> 60_000
            else -> 0
        }
        count == win - 2 -> when (openEnds) {
            2 -> 40_000
            1 -> 4_000
            else -> 0
        }
        count == win - 3 -> when (openEnds) {
            2 -> 2_000
            1 -> 300
            else -> 0
        }
        count == win - 4 -> when (openEnds) {
            2 -> 150
            1 -> 30
            else -> 0
        }
        else -> 0
    }

    private fun evaluate(): Int = scoreFor(me) - scoreFor(op) * 11 / 10

    private fun scoreFor(stone: Int): Int {
        var total = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (board[y * size + x] != stone) continue
                for (d in 0 until 4) {
                    val dx = DX[d]
                    val dy = DY[d]
                    val px = x - dx
                    val py = y - dy
                    // 只从棋型起点统计，避免重复
                    if (px in 0 until size && py in 0 until size && board[py * size + px] == stone) continue

                    var count = 0
                    var cx = x
                    var cy = y
                    while (cx in 0 until size && cy in 0 until size && board[cy * size + cx] == stone) {
                        count++
                        cx += dx
                        cy += dy
                    }
                    var open = 0
                    if (cx in 0 until size && cy in 0 until size && board[cy * size + cx] == 0) open++
                    if (px in 0 until size && py in 0 until size && board[py * size + px] == 0) open++
                    total += patternScore(count, open)
                }
            }
        }
        return total
    }

    private fun idxToMove(index: Int, stone: Stone) = Move(index % size, index / size, stone)

    companion object {
        private const val INF = 1_000_000_000
        private const val WIN_SCORE = 100_000_000
        private val DX = intArrayOf(1, 0, 1, 1)
        private val DY = intArrayOf(0, 1, 1, -1)
    }
}
