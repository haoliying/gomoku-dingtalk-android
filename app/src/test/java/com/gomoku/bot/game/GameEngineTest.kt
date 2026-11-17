package com.gomoku.bot.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEngineTest {

    @Test
    fun `horizontal five wins on 15 board`() {
        val engine = GameEngine(15, 5)
        for (i in 0 until 4) {
            assertTrue(engine.place(3 + i, 7)) // black
            assertTrue(engine.place(3 + i, 9)) // white
        }
        assertTrue(engine.place(7, 7))
        assertEquals(Stone.BLACK, engine.winner)
        assertEquals(5, engine.winningLine.size)
        assertFalse(engine.place(8, 8))
    }

    @Test
    fun `configured board size and win length`() {
        val engine = GameEngine(9, 4)
        for (i in 0 until 3) {
            assertTrue(engine.place(2 + i, 4))
            assertTrue(engine.place(2 + i, 6))
        }
        assertTrue(engine.place(5, 4))
        assertEquals(Stone.BLACK, engine.winner)
        assertEquals(4, engine.winLength)
        assertEquals(9, engine.size)
    }

    @Test
    fun `diagonal win is detected`() {
        val engine = GameEngine(15, 5)
        for (i in 0 until 4) {
            engine.place(2 + i, 2 + i)
            engine.place(2 + i, 10)
        }
        engine.place(6, 6)
        assertEquals(Stone.BLACK, engine.winner)
        assertEquals(5, engine.winningLine.size)
    }

    @Test
    fun `gap does not win`() {
        val engine = GameEngine(15, 5)
        val blackMoves = listOf(0 to 0, 1 to 0, 2 to 0, 4 to 0, 5 to 0)
        for ((index, move) in blackMoves.withIndex()) {
            engine.place(move.first, move.second)
            if (index < blackMoves.size - 1) engine.place(0, 2 + index)
        }
        assertNull(engine.winner)
    }

    @Test
    fun `undo restores turn and clears winner`() {
        val engine = GameEngine(15, 5)
        for (i in 0 until 4) {
            engine.place(3 + i, 7)
            engine.place(3 + i, 9)
        }
        engine.place(7, 7)
        assertEquals(Stone.BLACK, engine.winner)
        engine.undo()
        assertNull(engine.winner)
        assertEquals(Stone.BLACK, engine.current)
        assertTrue(engine.isEmpty(7, 7))
    }

    @Test
    fun `snapshot rebuild keeps board identical`() {
        val engine = GameEngine(15, 5)
        engine.place(7, 7)
        engine.place(8, 8)
        engine.place(6, 6)
        engine.place(9, 9)

        val rebuilt = GameEngine(15, 5)
        val moves = engine.snapshot().split(';').map {
            val xy = it.split(',')
            xy[0].toInt() to xy[1].toInt()
        }
        rebuilt.rebuild(moves)

        for (y in 0 until 15) {
            for (x in 0 until 15) {
                assertEquals(engine.stoneAt(x, y), rebuilt.stoneAt(x, y))
            }
        }
        assertEquals(engine.current, rebuilt.current)
        assertEquals(engine.moveCount, rebuilt.moveCount)
    }

    @Test
    fun `out of bounds and occupied cells rejected`() {
        val engine = GameEngine(9, 5)
        assertFalse(engine.place(-1, 0))
        assertFalse(engine.place(9, 0))
        assertTrue(engine.place(4, 4))
        assertFalse(engine.place(4, 4))
        assertNotNull(engine.lastMove)
    }
}
