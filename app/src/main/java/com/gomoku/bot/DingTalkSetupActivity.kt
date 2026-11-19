package com.gomoku.bot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.gomoku.bot.databinding.ActivityDingtalkSetupBinding
import com.gomoku.bot.game.Stone
import com.gomoku.bot.net.DingTalkApi
import com.gomoku.bot.net.DingTalkConfig
import com.gomoku.bot.net.DingTalkSession
import com.gomoku.bot.net.cleanCredential
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

        binding.cbHideSecret.setOnCheckedChangeListener { _, hide ->
            binding.etAppSecret.inputType = if (hide) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            binding.etAppSecret.setSelection(binding.etAppSecret.text?.length ?: 0)
        }

        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnStart.setOnClickListener { startGame() }
        binding.btnCopyLog.setOnClickListener { copyLog() }
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

    private fun buildConfig(): DingTalkConfig? {
        val rawKey = binding.etAppKey.text.toString()
        val rawSecret = binding.etAppSecret.text.toString()
        val appKey = cleanCredential(rawKey)
        val appSecret = cleanCredential(rawSecret)
        val robotCode = cleanCredential(binding.etRobotCode.text.toString())
        val peerId = cleanCredential(binding.etPeerId.text.toString())
        val conversationId = cleanCredential(binding.etConversationId.text.toString())
        val room = binding.etRoom.text.toString().trim()
        val name = binding.etName.text.toString().trim().ifBlank { "棋友" }
        val color = if (binding.chipWhite.isChecked) Stone.WHITE else Stone.BLACK

        if (rawKey != appKey || rawSecret != appSecret) {
            toast("已自动清除凭据中的空格/不可见字符")
        }

        if (appKey.isEmpty()) { toast("请填写 AppKey"); return null }
        if (appSecret.isEmpty()) { toast("请填写 AppSecret"); return null }
        if (robotCode.isEmpty()) { toast("请填写 RobotCode"); return null }
        if (peerId.isEmpty() && conversationId.isEmpty()) {
            toast("请填写对方钉钉 userId，或群会话 ID"); return null
        }
        if (room.isEmpty()) { toast("请填写房间号"); return null }

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
        return DingTalkConfig(
            appKey = appKey,
            appSecret = appSecret,
            robotCode = robotCode,
            peerUserId = peerId,
            openConversationId = conversationId,
            room = room,
            nickname = name
        )
    }

    private fun selectedColor() = if (binding.chipWhite.isChecked) Stone.WHITE else Stone.BLACK

    private fun selectedName() = binding.etName.text.toString().trim().ifBlank { "棋友" }

    private fun testConnection() {
        val config = buildConfig() ?: return
        appendLog("正在测试连接…")
        binding.btnTest.isEnabled = false
        Thread {
            val results = DingTalkApi.testConnection(config)
            runOnUiThread {
                binding.btnTest.isEnabled = true
                results.forEach { r ->
                    appendLog("${if (r.ok) "✓" else "✗"} ${r.step}：${r.detail}")
                }
                if (results.all { it.ok }) {
                    appendLog("连接测试全部通过，可以开始对局。")
                } else {
                    appendLog("连接测试未通过，请根据上方错误排查。")
                }
            }
        }.start()
    }

    private fun startGame() {
        val config = buildConfig() ?: return
        appendLog("正在验证凭据并连接…")
        binding.btnStart.isEnabled = false
        Thread {
            val results = DingTalkApi.testConnection(config)
            runOnUiThread {
                binding.btnStart.isEnabled = true
                results.forEach { r ->
                    appendLog("${if (r.ok) "✓" else "✗"} ${r.step}：${r.detail}")
                }
                if (results.any { !it.ok }) {
                    toast("凭据验证未通过，请检查后重试")
                    return@runOnUiThread
                }
                val error = DingTalkSession.start(
                    config = config,
                    size = boardSize,
                    win = winLength,
                    color = selectedColor(),
                    name = selectedName()
                )
                if (error != null) {
                    toast(error)
                    return@runOnUiThread
                }
                openGame()
            }
        }.start()
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
        while (logLines.size > 200) logLines.removeAt(0)
        binding.tvLog.text = logLines.joinToString("\n")
    }

    private fun copyLog() {
        val text = if (logLines.isEmpty()) {
            binding.tvLog.text.toString()
        } else {
            logLines.joinToString("\n")
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("连接日志", text))
        toast("日志已复制到剪贴板")
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
