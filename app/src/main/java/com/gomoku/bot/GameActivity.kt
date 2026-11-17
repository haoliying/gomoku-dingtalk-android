package com.gomoku.bot

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gomoku.bot.databinding.ActivityGameBinding
import com.gomoku.bot.game.GameEngine
import com.gomoku.bot.game.GomokuAI
import com.gomoku.bot.game.Stone
import com.gomoku.bot.net.BotProtocol
import com.gomoku.bot.net.RemoteSession
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameBinding
    private lateinit var engine: GameEngine
    private var mode = MODE_PVP
    private var aiLevel = GomokuAI.Level.MEDIUM
    private var myColor = Stone.BLACK
    private var aiJob: Job? = null
    private var resultShown = false
    private var resultDialog: androidx.appcompat.app.AlertDialog? = null
    private var isRemote = false
    private var closingDialogShown = false
    private val chatLines = ArrayList<String>()

    private val sessionListener: (RemoteSession.Event) -> Unit = { event -> handleSessionEvent(event) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PVP
        isRemote = mode == MODE_REMOTE
        aiLevel = GomokuAI.Level.fromIndex(intent.getIntExtra(EXTRA_LEVEL, 1))

        if (isRemote) {
            myColor = RemoteSession.myColor
            engine = GameEngine(RemoteSession.boardSize, RemoteSession.winLength)
            RemoteSession.onEvent = sessionListener
        } else {
            engine = GameEngine(
                intent.getIntExtra(EXTRA_SIZE, 15).coerceIn(9, 19),
                intent.getIntExtra(EXTRA_WIN, 5).coerceIn(4, 6)
            )
        }
        if (mode == MODE_PVE) myColor = Stone.BLACK

        binding.boardView.engine = engine
        binding.boardView.onCellTap = { x, y -> onCellTap(x, y) }

        binding.btnUndo.visibility = if (isRemote) android.view.View.GONE else android.view.View.VISIBLE
        binding.chatPanel.visibility = if (isRemote) android.view.View.VISIBLE else android.view.View.GONE

        binding.btnBack.setOnClickListener { confirmExit() }
        binding.btnExit.setOnClickListener { confirmExit() }
        binding.btnUndo.setOnClickListener { undo() }
        binding.btnRestart.setOnClickListener { requestRestart() }
        binding.btnSendChat.setOnClickListener { sendChat() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmExit()
            }
        })

        configureHeader()
        updateStatus()
        if (isRemote) {
            appendChat("已连接 ${RemoteSession.peerName} · 房间 ${RemoteSession.room}")
            if (engine.current == myColor) appendChat("你执" + colorName(myColor) + "，你先手")
        }
    }

    private fun configureHeader() {
        val size = engine.size
        val win = engine.winLength
        binding.tvModeTitle.text = when (mode) {
            MODE_PVE -> "人机对战"
            MODE_REMOTE -> "钉钉联机"
            else -> "本地双人"
        }
        binding.tvModeSubtitle.text = "$size × $size · $win 连获胜" +
            if (mode == MODE_PVE) " · ${aiLevel.label}" else ""

        if (mode == MODE_PVE) {
            binding.tvBlackName.text = "你（黑）"
            binding.tvWhiteName.text = "AI（白）· ${aiLevel.label}"
        } else if (isRemote) {
            binding.tvBlackName.text = if (myColor == Stone.BLACK) "${RemoteSession.myName}（黑）" else "${RemoteSession.peerName}（黑）"
            binding.tvWhiteName.text = if (myColor == Stone.BLACK) "${RemoteSession.peerName}（白）" else "${RemoteSession.myName}（白）"
        } else {
            binding.tvBlackName.text = "黑方"
            binding.tvWhiteName.text = "白方"
        }
    }

    private fun onCellTap(x: Int, y: Int) {
        if (!binding.boardView.interactive) return
        if (engine.winner != null || engine.isDraw) return
        if (isRemote) {
            if (!RemoteSession.connected) {
                toast("连接已断开")
                return
            }
            if (engine.current != myColor) {
                toast("还没轮到你落子")
                return
            }
        }
        if (!engine.place(x, y)) return
        if (isRemote) {
            RemoteSession.sendMove(x, y, engine.snapshot())
        }
        afterMove()
    }

    private fun afterMove() {
        binding.boardView.invalidate()
        updateStatus()
        if (engine.winner != null || engine.isDraw) {
            showResult()
            return
        }
        maybeAiMove()
    }

    private fun maybeAiMove() {
        if (mode != MODE_PVE) return
        if (engine.current != Stone.WHITE) return
        if (engine.winner != null || engine.isDraw) return

        setThinking(true)
        val snapshot = engine.cellsCopy()
        val level = aiLevel
        val boardSize = engine.size
        val win = engine.winLength
        aiJob = lifecycleScope.launch {
            val move = withContext(Dispatchers.Default) {
                GomokuAI(boardSize, win).findBestMove(snapshot, Stone.WHITE, level)
            }
            if (!isActive) return@launch
            setThinking(false)
            if (move != null && engine.winner == null && engine.current == Stone.WHITE) {
                engine.place(move.x, move.y)
                binding.boardView.invalidate()
                updateStatus()
                if (engine.winner != null || engine.isDraw) showResult()
            }
        }
    }

    private fun setThinking(thinking: Boolean) {
        binding.boardView.interactive = !thinking && canPlay()
        if (thinking) {
            binding.tvTurn.text = "AI 思考中"
        }
    }

    private fun updateStatus() {
        binding.tvMoveCount.text = "第 ${engine.moveCount} 手"
        val winner = engine.winner
        val turn = engine.current
        binding.tvTurn.text = when {
            winner != null -> "${colorName(winner)}获胜"
            engine.isDraw -> "平局"
            mode == MODE_PVE -> if (turn == Stone.BLACK) "轮到你" else "AI 思考中"
            isRemote -> if (turn == myColor) "轮到你" else "等待对方"
            else -> "${colorName(turn)}落子"
        }
        binding.boardView.interactive = canPlay()
    }

    private fun canPlay(): Boolean {
        if (engine.winner != null || engine.isDraw) return false
        return when {
            mode == MODE_PVE -> engine.current == Stone.BLACK
            isRemote -> engine.current == myColor && RemoteSession.connected
            else -> true
        }
    }

    private fun undo() {
        aiJob?.cancel()
        if (mode == MODE_PVE) {
            if (engine.current == Stone.BLACK) {
                if (engine.moveCount >= 2) {
                    engine.undo()
                    engine.undo()
                } else if (engine.moveCount == 1) {
                    engine.undo()
                }
            } else {
                engine.undo()
            }
        } else {
            engine.undo()
        }
        binding.boardView.invalidate()
        updateStatus()
        if (engine.moveCount == 0) {
            resultShown = false
            resultDialog?.dismiss()
        }
    }

    private fun requestRestart() {
        if (isRemote) {
            RemoteSession.requestRestart()
            toast("已发送重开请求，等待对方确认")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("重新开局")
            .setMessage("当前棋局将会清空，确定重开吗？")
            .setPositiveButton("重开") { _, _ -> resetGame() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun resetGame() {
        aiJob?.cancel()
        engine.reset()
        resultShown = false
        resultDialog?.dismiss()
        binding.boardView.invalidate()
        updateStatus()
    }

    private fun showResult(forcedTitle: String? = null) {
        if (resultShown) return
        resultShown = true
        val winner = engine.winner
        val title: String
        val message: String
        if (forcedTitle != null) {
            title = forcedTitle
            message = "本局结束。"
        } else if (engine.isDraw) {
            title = "平局"
            message = "棋盘已满，双方握手言和。"
        } else {
            title = when {
                mode == MODE_PVE -> if (winner == Stone.BLACK) "你赢了！" else "AI 获胜"
                isRemote -> if (winner == myColor) "你赢了！" else "对手获胜"
                else -> if (winner == Stone.BLACK) "黑方获胜" else "白方获胜"
            }
            message = "本局共 ${engine.moveCount} 手，${colorName(winner ?: Stone.EMPTY)}棋连成 ${engine.winLength} 子。"
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)

        if (isRemote) {
            builder.setPositiveButton("请求重开") { dialog, _ ->
                dialog.dismiss()
                RemoteSession.requestRestart()
                toast("已发送重开请求")
            }
            builder.setNegativeButton("返回") { dialog, _ ->
                dialog.dismiss()
                exitGame()
            }
        } else {
            builder.setPositiveButton("再来一局") { dialog, _ ->
                dialog.dismiss()
                resetGame()
            }
            builder.setNegativeButton("返回") { dialog, _ ->
                dialog.dismiss()
                exitGame()
            }
        }
        resultDialog = builder.show()
    }

    private fun handleSessionEvent(event: RemoteSession.Event) {
        when (event) {
            is RemoteSession.Event.Message -> handleRemoteMessage(event.msg)
            is RemoteSession.Event.Error -> toast(event.text)
            is RemoteSession.Event.Log -> appendChat(event.text)
            is RemoteSession.Event.Closed -> showConnectionClosed(event.text)
            is RemoteSession.Event.Joined, is RemoteSession.Event.Waiting -> Unit
        }
    }

    private fun handleRemoteMessage(msg: com.gomoku.bot.net.BotMessage) {
        when (msg.type) {
            "move" -> {
                val moves = BotProtocol.parseMoves(msg.moves)
                if (moves.isEmpty()) return
                if (moves.size < engine.moveCount) {
                    RemoteSession.sendSync(engine.snapshot())
                    return
                }
                engine.rebuild(moves)
                binding.boardView.invalidate()
                updateStatus()
                appendChat("${msg.name} 落子 ${BotProtocol.coordinate(msg.x, msg.y)}")
                if (engine.winner != null || engine.isDraw) showResult()
            }

            "chat" -> appendChat("${msg.name}：${msg.text}")

            "restart_req" -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("对方请求重开")
                    .setMessage("${msg.name} 请求重新开始一局。")
                    .setPositiveButton("同意") { _, _ ->
                        RemoteSession.replyRestart(true)
                        resetGame()
                    }
                    .setNegativeButton("拒绝") { _, _ -> RemoteSession.replyRestart(false) }
                    .show()
            }

            "restart_ok" -> {
                resetGame()
                toast("对方同意重开")
            }

            "restart_no" -> toast("对方拒绝重开")

            "surrender" -> {
                appendChat("${msg.name} 认输")
                showResult(forcedTitle = "对方认输，你赢了")
            }

            "sync" -> {
                val moves = BotProtocol.parseMoves(msg.moves)
                if (moves.size > engine.moveCount) {
                    engine.rebuild(moves)
                    binding.boardView.invalidate()
                    updateStatus()
                }
            }

            "sync_req" -> RemoteSession.sendSync(engine.snapshot())

            "leave" -> showConnectionClosed("${msg.name} 离开了房间")
        }
    }

    private fun showConnectionClosed(text: String) {
        if (closingDialogShown || isFinishing) return
        closingDialogShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle("连接已断开")
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton("返回") { _, _ -> exitGame() }
            .show()
    }

    private fun sendChat() {
        val text = binding.etChat.text.toString().trim()
        if (text.isEmpty()) return
        binding.etChat.setText("")
        appendChat("我：$text")
        RemoteSession.sendChat(text)
    }

    private fun appendChat(text: String) {
        chatLines.add(text)
        while (chatLines.size > 3) chatLines.removeAt(0)
        binding.tvChatLog.text = chatLines.joinToString("\n")
    }

    private fun confirmExit() {
        MaterialAlertDialogBuilder(this)
            .setTitle("退出对局")
            .setMessage(if (isRemote) "退出将断开与对方的机器人连接。" else "确定要退出当前对局吗？")
            .setPositiveButton("退出") { _, _ -> exitGame() }
            .setNegativeButton("继续下棋", null)
            .show()
    }

    private fun exitGame() {
        aiJob?.cancel()
        if (isRemote) {
            RemoteSession.sendLeave()
            RemoteSession.close()
        }
        finish()
    }

    private fun colorName(stone: Stone): String = when (stone) {
        Stone.BLACK -> "黑方"
        Stone.WHITE -> "白方"
        else -> "无"
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (RemoteSession.onEvent === sessionListener) {
            RemoteSession.onEvent = null
        }
        if (isRemote && isFinishing) {
            RemoteSession.close()
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_SIZE = "size"
        const val EXTRA_WIN = "win"
        const val EXTRA_LEVEL = "level"

        const val MODE_PVE = "pve"
        const val MODE_PVP = "pvp"
        const val MODE_REMOTE = "remote"
    }
}
