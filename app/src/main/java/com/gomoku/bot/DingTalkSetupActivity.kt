package com.gomoku.bot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gomoku.bot.databinding.ActivityDingtalkSetupBinding
import com.gomoku.bot.game.Stone
import com.gomoku.bot.net.DingTalkApi
import com.gomoku.bot.net.DingTalkApiException
import com.gomoku.bot.net.DingTalkConfig
import com.gomoku.bot.net.DingTalkGroupStreamClient
import com.gomoku.bot.net.DingTalkPersonalEvent
import com.gomoku.bot.net.DingTalkSession
import com.gomoku.bot.net.DingTalkUserAuth
import com.gomoku.bot.net.GroupEvent
import com.gomoku.bot.net.cleanCredential
import kotlin.random.Random

class DingTalkSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDingtalkSetupBinding
    private var boardSize = 15
    private var winLength = 5
    private var handedOff = false
    private val logLines = ArrayList<String>()
    private var groupClient: DingTalkGroupStreamClient? = null
    private var authDialog: AlertDialog? = null

    @Volatile
    private var loginActive = false

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
        binding.btnUserLogin.setOnClickListener { startUserLogin() }
        binding.btnListen.setOnClickListener { startListen() }
        binding.btnStopListen.setOnClickListener { stopListen() }
        updateListenStatus()
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

    private fun startUserLogin() {
        val appKey = cleanCredential(binding.etAppKey.text.toString())
        val appSecret = cleanCredential(binding.etAppSecret.text.toString())
        if (appKey.isEmpty()) { toast("请先填写 AppKey"); return }
        if (appSecret.isEmpty()) { toast("请先填写 AppSecret"); return }
        if (loginActive) { toast("授权进行中，请稍候"); return }

        loginActive = true
        binding.btnUserLogin.isEnabled = false
        appendLog("正在申请用户授权码…")
        Thread {
            try {
                val auth = DingTalkUserAuth.requestDeviceCode(appKey)
                runOnUiThread { showAuthDialog(auth, appKey, appSecret) }
            } catch (e: Exception) {
                loginActive = false
                runOnUiThread {
                    binding.btnUserLogin.isEnabled = true
                    appendLog("✗ 申请授权码失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun showAuthDialog(auth: com.gomoku.bot.net.DeviceAuth, appKey: String, appSecret: String) {
        val url = auth.verificationUriComplete.ifBlank { auth.verificationUri }
        appendLog("授权码：${auth.userCode}（${auth.expiresIn / 60} 分钟内有效）")

        val dialog = AlertDialog.Builder(this)
            .setTitle("钉钉用户授权")
            .setMessage(
                "1. 点「打开授权页」并登录钉钉\n" +
                    "2. 在页面输入授权码：${auth.userCode}\n" +
                    "3. 确认授权后回到本页，会自动完成\n\n" +
                    "授权页：$url"
            )
            .setPositiveButton("打开授权页") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { appendLog("无法打开浏览器，请手动访问：$url") }
            }
            .setNegativeButton("取消") { _, _ -> loginActive = false }
            .setOnCancelListener { loginActive = false }
            .create()
        authDialog = dialog
        dialog.show()

        Thread {
            val start = System.currentTimeMillis()
            var token: com.gomoku.bot.net.UserToken? = null
            while (loginActive && System.currentTimeMillis() - start < auth.expiresIn * 1000L) {
                runCatching { Thread.sleep(auth.interval * 1000L) }
                if (!loginActive) break
                val code = try {
                    DingTalkUserAuth.pollAuthCode(appKey, auth.deviceCode)
                } catch (e: Exception) {
                    runOnUiThread { appendLog("✗ 轮询授权状态失败：${e.message ?: ""}") }
                    break
                }
                if (code != null) {
                    token = try {
                        DingTalkUserAuth.exchange(appKey, appSecret, code)
                    } catch (e: Exception) {
                        runOnUiThread { appendLog("✗ 换取用户 token 失败：${e.message ?: ""}") }
                        null
                    }
                    break
                }
            }
            loginActive = false
            runOnUiThread {
                authDialog?.dismiss()
                authDialog = null
                binding.btnUserLogin.isEnabled = true
                val result = token
                if (result != null) {
                    Prefs.saveUserToken(this, result.accessToken)
                    appendLog("✓ 用户授权成功，有效期约 ${result.expireIn / 60} 分钟")
                    updateListenStatus()
                } else {
                    appendLog("用户授权未完成")
                }
            }
        }.start()
    }

    private fun startListen() {
        val appKey = cleanCredential(binding.etAppKey.text.toString())
        if (appKey.isEmpty()) { toast("请先填写 AppKey"); return }
        val token = Prefs.userToken(this)
        if (token.isBlank()) { toast("请先完成「① 用户登录授权」"); return }
        if (groupClient != null) { toast("正在监听中"); return }

        binding.btnListen.isEnabled = false
        appendLog("正在订阅群消息…")
        Thread {
            try {
                var subId = Prefs.subscribeId(this)
                if (subId.isBlank()) {
                    subId = DingTalkPersonalEvent.subscribe(token, appKey)
                    Prefs.saveSubscribeId(this, subId)
                    val id = subId
                    runOnUiThread {
                        appendLog(
                            if (id.isBlank()) "✓ 订阅请求已提交（服务端未返回订阅ID）"
                            else "✓ 已订阅全部群消息，订阅ID ${id.take(12)}"
                        )
                    }
                } else {
                    val id = subId
                    runOnUiThread { appendLog("复用已有订阅 ${id.take(12)}") }
                }

                val client = DingTalkGroupStreamClient(
                    userToken = token,
                    appKey = appKey,
                    onEvent = { event -> runOnUiThread { handleGroupEvent(event) } },
                    onRawEvent = { raw -> runOnUiThread { appendLog("原始事件：${raw.take(200)}") } },
                    onLog = { text -> runOnUiThread { appendLog(text) } },
                    onError = { text -> runOnUiThread { appendLog("✗ $text") } },
                    onReady = { runOnUiThread { updateListenStatus() } }
                )
                groupClient = client
                runOnUiThread {
                    binding.btnListen.isEnabled = true
                    updateListenStatus()
                }
                client.start()
            } catch (e: DingTalkApiException) {
                runOnUiThread {
                    binding.btnListen.isEnabled = true
                    appendLog("✗ 订阅群消息失败：${e.format("请求失败")}")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnListen.isEnabled = true
                    appendLog("✗ 订阅群消息失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun stopListen() {
        val client = groupClient
        if (client == null) { toast("当前未监听"); return }
        client.stop()
        groupClient = null
        appendLog("已停止群消息监听")
        updateListenStatus()

        val token = Prefs.userToken(this)
        val appKey = cleanCredential(binding.etAppKey.text.toString())
        val subId = Prefs.subscribeId(this)
        if (subId.isNotBlank() && token.isNotBlank() && appKey.isNotBlank()) {
            Thread {
                DingTalkPersonalEvent.cancel(token, appKey, subId)
                Prefs.saveSubscribeId(this, "")
                runOnUiThread { updateListenStatus() }
            }.start()
        }
    }

    private fun handleGroupEvent(event: GroupEvent) {
        if (event.conversationId.isNotBlank() &&
            event.conversationId != Prefs.openConversationId(this)
        ) {
            Prefs.setConversationId(this, event.conversationId)
            binding.etConversationId.setText(event.conversationId)
            appendLog("已自动记录群会话 ID：${event.conversationId}")
        }
        appendLog("群消息 ${event.senderName}(${event.senderId.take(8)})：${event.text.take(120)}")
    }

    private fun updateListenStatus() {
        val token = Prefs.userToken(this)
        val subId = Prefs.subscribeId(this)
        binding.tvListenStatus.text = if (token.isBlank()) {
            "未授权：请点「① 用户登录授权」"
        } else {
            buildString {
                val at = Prefs.userTokenAt(this@DingTalkSetupActivity)
                append("已授权")
                if (at > 0) append("（${(System.currentTimeMillis() - at) / 60000} 分钟前）")
                append(if (subId.isBlank()) " · 未订阅" else " · 已订阅 ${subId.take(10)}")
                append(if (groupClient != null) " · 监听中" else "")
            }
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
        loginActive = false
        authDialog?.dismiss()
        authDialog = null
        groupClient?.stop()
        groupClient = null
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
