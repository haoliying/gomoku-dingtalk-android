package com.gomoku.bot

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.gomoku.bot.databinding.ActivityMainBinding
import com.gomoku.bot.game.GomokuAI
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var boardSize = 15
    private var winLength = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        boardSize = Prefs.boardSize(this)
        winLength = Prefs.winLength(this)

        binding.sliderSize.value = boardSize.toFloat()
        binding.sliderSize.addOnChangeListener { _, value, _ ->
            boardSize = value.toInt()
            Prefs.setBoardSize(this, boardSize)
            updateBoardSizeLabel()
        }

        when (winLength) {
            4 -> binding.chipWin4.isChecked = true
            6 -> binding.chipWin6.isChecked = true
            else -> binding.chipWin5.isChecked = true
        }
        binding.chipGroupWin.setOnCheckedStateChangeListener { _, checkedIds ->
            winLength = when (checkedIds.firstOrNull()) {
                R.id.chipWin4 -> 4
                R.id.chipWin6 -> 6
                else -> 5
            }
            Prefs.setWinLength(this, winLength)
        }

        binding.cardPve.setOnClickListener { chooseDifficulty() }
        binding.cardPvp.setOnClickListener { startGame(MODE_PVP, Prefs.aiLevel(this)) }
        binding.cardRemote.setOnClickListener { openRemoteSetup() }

        updateBoardSizeLabel()
    }

    private fun updateBoardSizeLabel() {
        binding.tvBoardSize.text = "$boardSize × $boardSize"
    }

    private fun chooseDifficulty() {
        val levels = GomokuAI.Level.entries
        val labels = levels.map { "${it.label} · ${it.desc}" }.toTypedArray()
        val checked = Prefs.aiLevel(this).coerceIn(0, levels.size - 1)
        MaterialAlertDialogBuilder(this)
            .setTitle("选择 AI 难度")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                Prefs.setAiLevel(this, which)
                dialog.dismiss()
                startGame(MODE_PVE, which)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startGame(mode: String, levelIndex: Int) {
        val intent = Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_MODE, mode)
            putExtra(GameActivity.EXTRA_SIZE, boardSize)
            putExtra(GameActivity.EXTRA_WIN, winLength)
            putExtra(GameActivity.EXTRA_LEVEL, levelIndex)
        }
        startActivity(intent)
    }

    private fun openRemoteSetup() {
        val intent = Intent(this, DingTalkSetupActivity::class.java).apply {
            putExtra(DingTalkSetupActivity.EXTRA_SIZE, boardSize)
            putExtra(DingTalkSetupActivity.EXTRA_WIN, winLength)
        }
        startActivity(intent)
    }

    companion object {
        const val MODE_PVE = "pve"
        const val MODE_PVP = "pvp"
    }
}
