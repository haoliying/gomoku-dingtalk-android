package com.gomoku.bot

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.gomoku.bot.databinding.ActivityRemoteSetupBinding
import com.gomoku.bot.net.NetUtils
import com.gomoku.bot.net.RemoteSession
import kotlin.random.Random

class RemoteSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemoteSetupBinding
    private var boardSize = 15
    private var winLength = 5
    private var busy = false
    private var handedOff = false
    private val logLines = ArrayList<String>()

    private val sessionListener: (RemoteSession.Event) -> Unit = { event -> handleSessionEvent(event) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        boardSize = intent.getIntExtra(EXTRA_SIZE, 15)
        winLength = intent.getIntExtra(EXTRA_WIN, 5)

        binding.etName.setText(Prefs.nickname(this))
        binding.etRoom.setText(String.format("%04d", Random.nextInt(1000, 9999)))
        binding.etPort.setText(Prefs.port(this).toString())
        binding.etWebhook.setText(Prefs.webhook(this))

        binding.etPort.doAfterTextChanged { updateAddressLabel() }
        updateAddressLabel()

        binding.btnCreate.setOnClickListener { createRoom() }
        binding.btnJoin.setOnClickListener { joinRoom() }
        binding.btnBack.setOnClickListener {
            RemoteSession.close()
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                RemoteSession.close()
                finish()
            }
        })

        RemoteSession.onEvent = sessionListener
    }

    private fun updateAddressLabel() {
        val port = binding.etPort.text.toString().toIntOrNull() ?: Prefs.port(this)
        val ip = NetUtils.localIpAddress()
        binding.tvBotAddress.text = if (ip == null) {
            "未检测到局域网地址，请先连接 Wi-Fi 后重试。"
        } else {
            "本机机器人地址（发给对方）：\nhttp://$ip:$port/robot/send"
        }
    }

    private fun createRoom() {
        if (busy) return
        if (NetUtils.localIpAddress() == null) {
            toast("未检测到局域网地址，请先连接 Wi-Fi")
            return
        }
        val name = binding.etName.text.toString().trim().ifBlank { "房主" }
        val room = binding.etRoom.text.toString().trim()
        val port = binding.etPort.text.toString().toIntOrNull() ?: 18765
        if (room.isEmpty()) {
            toast("请填写房间号")
            return
        }
        if (port !in 1024..65535) {
            toast("端口需在 1024 - 65535 之间")
            return
        }
        savePrefs(name, port)
        val error = RemoteSession.startHost(
            size = boardSize,
            win = winLength,
            roomCode = room,
            name = name,
            listenPort = port,
            webhook = binding.etWebhook.text.toString()
        )
        if (error != null) {
            toast(error)
            return
        }
        busy = true
        setControlsEnabled(false)
        binding.tvBotAddress.text = "机器人已启动，等待对方加入：\n${RemoteSession.myUrl()}"
    }

    private fun joinRoom() {
        if (busy) return
        if (NetUtils.localIpAddress() == null) {
            toast("未检测到局域网地址，请先连接 Wi-Fi")
            return
        }
        val name = binding.etName.text.toString().trim().ifBlank { "玩家" }
        val room = binding.etRoom.text.toString().trim()
        val port = binding.etPort.text.toString().toIntOrNull() ?: 18765
        val peer = binding.etPeer.text.toString().trim()
        if (room.isEmpty()) {
            toast("请填写房间号")
            return
        }
        if (peer.isEmpty()) {
            toast("请填写房主机器人地址")
            return
        }
        if (port !in 1024..65535) {
            toast("端口需在 1024 - 65535 之间")
            return
        }
        savePrefs(name, port)
        val error = RemoteSession.join(
            size = boardSize,
            win = winLength,
            roomCode = room,
            name = name,
            listenPort = port,
            peerAddress = peer,
            webhook = binding.etWebhook.text.toString()
        )
        if (error != null) {
            toast(error)
            return
        }
        busy = true
        setControlsEnabled(false)
    }

    private fun savePrefs(name: String, port: Int) {
        Prefs.setNickname(this, name)
        Prefs.setPort(this, port)
        Prefs.setWebhook(this, binding.etWebhook.text.toString().trim())
    }

    private fun handleSessionEvent(event: RemoteSession.Event) {
        when (event) {
            is RemoteSession.Event.Waiting -> {
                appendLog("机器人已就绪")
                busy = true
                setControlsEnabled(false)
            }
            is RemoteSession.Event.Log -> appendLog(event.text)
            is RemoteSession.Event.Error -> {
                appendLog("错误：${event.text}")
                toast(event.text)
                if (!RemoteSession.connected) {
                    RemoteSession.close()
                    busy = false
                    setControlsEnabled(true)
                }
            }
            is RemoteSession.Event.Joined -> {
                appendLog("已连接：${event.peerName}")
                openGame()
            }
            is RemoteSession.Event.Closed -> {
                appendLog(event.text)
                busy = false
                setControlsEnabled(true)
            }
            is RemoteSession.Event.Message -> Unit
        }
    }

    private fun openGame() {
        handedOff = true
        startActivity(
            Intent(this, GameActivity::class.java).apply {
                putExtra(GameActivity.EXTRA_MODE, GameActivity.MODE_REMOTE)
                putExtra(GameActivity.EXTRA_SIZE, RemoteSession.boardSize)
                putExtra(GameActivity.EXTRA_WIN, RemoteSession.winLength)
            }
        )
        finish()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.btnCreate.isEnabled = enabled
        binding.btnJoin.isEnabled = enabled
        binding.etName.isEnabled = enabled
        binding.etRoom.isEnabled = enabled
        binding.etPort.isEnabled = enabled
        binding.etPeer.isEnabled = enabled
        binding.etWebhook.isEnabled = enabled
        binding.btnBack.visibility = View.VISIBLE
    }

    private fun appendLog(text: String) {
        logLines.add("› $text")
        while (logLines.size > 12) logLines.removeAt(0)
        binding.tvLog.text = logLines.joinToString("\n")
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (RemoteSession.onEvent === sessionListener) {
            RemoteSession.onEvent = null
        }
        if (!handedOff) {
            RemoteSession.close()
        }
    }

    companion object {
        const val EXTRA_SIZE = "size"
        const val EXTRA_WIN = "win"
    }
}
