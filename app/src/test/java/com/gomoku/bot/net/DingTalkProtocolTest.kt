package com.gomoku.bot.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DingTalkProtocolTest {

    @Test
    fun `parse coordinate tokens`() {
        assertEquals(7 to 7, DingTalkText.parseCoordinate("H8"))
        assertEquals(0 to 0, DingTalkText.parseCoordinate("a1"))
        assertEquals(2 to 14, DingTalkText.parseCoordinate("C,15"))
        assertNull(DingTalkText.parseCoordinate("99"))
        assertNull(DingTalkText.parseCoordinate("hello"))
    }

    @Test
    fun `parse move with room prefix`() {
        val message = DingTalkText.parse("#1234 H8", 15, "1234", false)
        assertNotNull(message)
        assertEquals("move", message!!.type)
        assertEquals(7, message.x)
        assertEquals(7, message.y)
    }

    @Test
    fun `ignore other room`() {
        val message = DingTalkText.parse("#9999 H8", 15, "1234", false)
        assertNull(message)
    }

    @Test
    fun `parse commands`() {
        assertEquals("restart_req", DingTalkText.parse("重开", 15, "1234", false)?.type)
        assertEquals("restart_ok", DingTalkText.parse("重开", 15, "1234", true)?.type)
        assertEquals("surrender", DingTalkText.parse("我认输了", 15, "1234", false)?.type)
        assertEquals("sync_req", DingTalkText.parse("同步棋局", 15, "1234", false)?.type)
    }

    @Test
    fun `reject out of board coordinate`() {
        assertNull(DingTalkText.parse("S19", 15, "1234", false))
        assertNotNull(DingTalkText.parse("S19", 19, "1234", false))
    }

    @Test
    fun `render board contains stones and turn hint`() {
        val text = DingTalkText.boardMessage(
            room = "1234",
            nickname = "小明",
            myColor = 2,
            size = 9,
            win = 5,
            moves = listOf(4 to 4, 5 to 5)
        )
        assertTrue(text.contains("第 2 手"))
        assertTrue(text.contains("●"))
        assertTrue(text.contains("○"))
        assertTrue(text.contains("轮到你落子"))
        assertTrue(text.contains("A B C D E F G H I"))
    }

    @Test
    fun `bot protocol round trips gomoku payload`() {
        val message = BotMessage(
            type = "move",
            room = "8888",
            seq = 3,
            name = "棋友",
            x = 6,
            y = 3,
            moves = "7,7;6,3",
            size = 15,
            win = 5
        )
        val decoded = BotProtocol.decode(BotProtocol.encode(message))
        assertNotNull(decoded)
        assertEquals("move", decoded!!.type)
        assertEquals(6, decoded.x)
        assertEquals(3, decoded.y)
        assertEquals(listOf(7 to 7, 6 to 3), BotProtocol.parseMoves(decoded.moves))
    }
}
