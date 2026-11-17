package com.gomoku.bot.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GomokuAITest {

    private fun boardOf(vararg stones: Triple<Int, Int, Stone>, size: Int = 15): IntArray {
        val board = IntArray(size * size)
        for ((x, y, stone) in stones) board[y * size + x] = stone.value
        return board
    }

    @Test
    fun `empty board opens at center`() {
        val ai = GomokuAI(15, 5)
        val move = ai.findBestMove(IntArray(225), Stone.BLACK, GomokuAI.Level.MEDIUM)
        assertNotNull(move)
        assertEquals(7, move!!.x)
        assertEquals(7, move.y)
    }

    @Test
    fun `ai completes its own five`() {
        val board = boardOf(
            Triple(4, 7, Stone.WHITE),
            Triple(5, 7, Stone.WHITE),
            Triple(6, 7, Stone.WHITE),
            Triple(7, 7, Stone.WHITE),
            Triple(0, 0, Stone.BLACK)
        )
        val ai = GomokuAI(15, 5)
        val move = ai.findBestMove(board, Stone.WHITE, GomokuAI.Level.HARD)
        assertNotNull(move)
        assertTrue((move!!.x == 3 || move.x == 8) && move.y == 7)
    }

    @Test
    fun `ai blocks opponent four`() {
        val board = boardOf(
            Triple(4, 7, Stone.BLACK),
            Triple(5, 7, Stone.BLACK),
            Triple(6, 7, Stone.BLACK),
            Triple(7, 7, Stone.BLACK),
            Triple(0, 0, Stone.WHITE)
        )
        val ai = GomokuAI(15, 5)
        val move = ai.findBestMove(board, Stone.WHITE, GomokuAI.Level.HARD)
        assertNotNull(move)
        assertTrue((move!!.x == 3 || move.x == 8) && move.y == 7)
    }

    @Test
    fun `ai blocks diagonal four`() {
        val board = boardOf(
            Triple(2, 2, Stone.BLACK),
            Triple(3, 3, Stone.BLACK),
            Triple(4, 4, Stone.BLACK),
            Triple(5, 5, Stone.BLACK),
            Triple(0, 10, Stone.WHITE)
        )
        val ai = GomokuAI(15, 5)
        val move = ai.findBestMove(board, Stone.WHITE, GomokuAI.Level.MEDIUM)
        assertNotNull(move)
        assertTrue((move!!.x == 1 && move.y == 1) || (move.x == 6 && move.y == 6))
    }

    @Test
    fun `ai move is empty and in bounds for every level`() {
        val board = boardOf(
            Triple(7, 7, Stone.BLACK),
            Triple(7, 8, Stone.WHITE),
            Triple(8, 8, Stone.BLACK)
        )
        for (level in GomokuAI.Level.entries) {
            val move = GomokuAI(15, 5).findBestMove(board, Stone.WHITE, level)
            assertNotNull(move)
            assertTrue(move!!.x in 0 until 15 && move.y in 0 until 15)
            assertTrue(board[move.y * 15 + move.x] == 0)
        }
    }

    @Test
    fun `hard level responds within time budget`() {
        val board = boardOf(
            Triple(7, 7, Stone.BLACK),
            Triple(8, 8, Stone.BLACK),
            Triple(6, 6, Stone.WHITE),
            Triple(9, 9, Stone.WHITE),
            Triple(7, 8, Stone.BLACK)
        )
        val start = System.currentTimeMillis()
        val move = GomokuAI(15, 5).findBestMove(board, Stone.WHITE, GomokuAI.Level.HARD)
        val elapsed = System.currentTimeMillis() - start
        assertNotNull(move)
        assertTrue("耗时 $elapsed ms", elapsed < 4000)
    }
}
