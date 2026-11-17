package com.gomoku.bot.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BotServerTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `server receives dingtalk message and replies accept`() {
        val port = freePort()
        val received = AtomicReference<BotMessage>()
        val latch = CountDownLatch(1)
        val server = DingTalkBotServer(port) { message ->
            received.set(message)
            latch.countDown()
            BotMessage(type = "accept", room = message.room, name = "房主", size = 15, win = 5)
        }
        assertTrue(server.start())
        try {
            val join = BotMessage(
                type = "join",
                room = "1234",
                seq = 1,
                from = "player-a",
                name = "小明",
                size = 15,
                win = 5,
                url = "http://127.0.0.1:$port/robot/send"
            )
            val response = BotHttp.post(
                "http://127.0.0.1:$port/robot/send",
                BotProtocol.encode(join)
            )
            assertTrue("机器人未收到消息", latch.await(3, TimeUnit.SECONDS))
            assertEquals("join", received.get().type)
            assertEquals("小明", received.get().name)
            assertEquals("1234", received.get().room)

            val reply = BotProtocol.decode(response)
            assertNotNull(reply)
            assertEquals("accept", reply!!.type)
            assertEquals("房主", reply.name)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `protocol round trips chinese text and moves`() {
        val message = BotMessage(
            type = "move",
            room = "8888",
            seq = 7,
            from = "player-b",
            name = "棋友甲",
            x = 6,
            y = 3,
            moves = "7,7;6,3",
            size = 15,
            win = 5,
            url = "http://192.168.1.8:18765/robot/send"
        )
        val decoded = BotProtocol.decode(BotProtocol.encode(message))
        assertNotNull(decoded)
        assertEquals("move", decoded!!.type)
        assertEquals("棋友甲", decoded.name)
        assertEquals(6, decoded.x)
        assertEquals(3, decoded.y)
        assertEquals(listOf(7 to 7, 6 to 3), BotProtocol.parseMoves(decoded.moves))
    }

    @Test
    fun `endpoint normalization`() {
        assertEquals(
            "http://192.168.1.8:18765/robot/send",
            BotHttp.normalize("192.168.1.8:18765")
        )
        assertEquals(
            "https://oapi.dingtalk.com/robot/send?access_token=abc",
            BotHttp.normalize("https://oapi.dingtalk.com/robot/send?access_token=abc")
        )
    }

    @Test
    fun `coordinate formatting`() {
        assertEquals("A1", BotProtocol.coordinate(0, 0))
        assertEquals("H8", BotProtocol.coordinate(7, 7))
        assertEquals("S19", BotProtocol.coordinate(18, 18))
    }
}
