package com.gomoku.bot

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.gomoku.bot.databinding.ActivityDingtalkSetupBinding
import com.gomoku.bot.game.Stone
import com.gomoku.bot.net.DingTalkConfig
import com.gomoku.bot.net.DingTalkSession
import kotlin.random.Random

class DingTalkSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDingtalkSetupBinding
    private var boardSize = 15
    private var winLength = 5
    private var handedOff = false
    private val logLines = ArrayList<String>()

    private val sessionListener: (DingTalkSession.Event) -> Unit = { event -> handleSessionEvent(event) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDingtalkSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        boardSize = intent.getIntExtra(EXTRA_SIZE, 15)
        winLength = intent.getIntExtra(EXTRA_WIN, 5)

        binding.etName.setText(Prefs.nickname(this))
        binding.etAppKey.setText(Prefs.appKey(this))
        binding.etAppSecret.setText(Prefs.appSecret(this))
        binding.etRobotCode.setText(Prefs.robotCode(this))
        binding.etPeerId.setText(Prefs.peerUserId(this))
        binding.etConversationId.setText(Prefs.openConversationId(this))
        binding.etRoom.setText(Prefs.room(this).ifBlank { String.format("%04d", Random.nextInt(1000, 9999)) })

        if (Prefs.color(this) == Stone.WHITE.value) {
            binding.chipWhite.isChecked = true
        } else {
            binding.chipBlack.isChecked = true
        }

        binding.btnStart.setOnClickListener { startGame() }
        binding.btnBack.setOnClickListener {
            DingTalkSession.close()
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                DingTalkSession.close()
                finish()
            }
        })

        DingTalkSession.onEvent = sessionListener
    }

    private fun startGame() {
        val appKey = binding.etAppKey.text.toString().trim()
        val appSecret = binding.etAppSecret.text.toString().trim()
        val robotCode = binding.etRobotCode.text.toString().trim()
        val peerId = binding.etPeerId.text.toString().trim()
        val conversationId = binding.etConversationId.text.toString().trim()
        val room = binding.etRoom.text.toString().trim()
        val name = binding.etName.text.toString().trim().ifBlank { "棋友" }
        val color = if (binding.chipWhite.isChecked) Stone.WHITE else Stone.BLACK

        if (room.isEmpty()) {
            toast("请填写房间号")
            return
        }
        if (peerId.isEmpty() && conversationId.isEmpty()) {
            toast("请填写对方钉钉 userId，或群会话 ID")
            return
        }

        Prefs.saveDingTalk(
            context = this,
            appKey = appKey,
            appSecret = appSecret,
            robotCode = robotCode,
            peerUserId = peerId,
            openConversationId = conversationId,
            room = room,
            color = color.value,
            nickname = name
        )

        val error = DingTalkSession.start(
            config = DingTalkConfig(
                appKey = appKey,
                appSecret = appSecret,
                robotCode = robotCode,
                peerUserId = peerId,
                openConversationId = conversationId,
                room = room,
                nickname = name
            ),
            size = boardSize,
            win = winLength,
            color = color,
            name = name
        )
        if (error != null) {
            toast(error)
            return
        }
        openGame()
    }

    private fun handleSessionEvent(event: DingTalkSession.Event) {
        when (event) {
            is DingTalkSession.Event.Log -> appendLog(event.text)
            is DingTalkSession.Event.Error -> {
                appendLog("错误：${event.text}")
                toast(event.text)
            }
            is DingTalkSession.Event.Started -> appendLog("会话已启动")
            is DingTalkSession.Event.Closed -> appendLog(event.text)
            is DingTalkSession.Event.Message -> Unit
        }
    }

    private fun appendLog(text: String) {
        logLines.add("› $text")
        while (logLines.size > 10) logLines.removeAt(0)
        binding.tvLog.text = logLines.joinToString("\n")
    }

    private fun openGame() {
        handedOff = true
        startActivity(
            Intent(this, GameActivity::class.java).apply {
                putExtra(GameActivity.EXTRA_MODE, GameActivity.MODE_REMOTE)
                putExtra(GameActivity.EXTRA_SIZE, DingTalkSession.boardSize)
                putExtra(GameActivity.EXTRA_WIN, DingTalkSession.winLength)
            }
        )
        finish()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (DingTalkSession.onEvent === sessionListener) {
            DingTalkSession.onEvent = null
        }
        if (!handedOff) {
            DingTalkSession.close()
        }
    }

    companion object {
        const val EXTRA_SIZE = "size"
        const val EXTRA_WIN = "win"
    }
}
