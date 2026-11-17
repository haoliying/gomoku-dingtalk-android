package com.gomoku.bot

import android.content.Context

/** 本地偏好设置。 */
object Prefs {

    private const val NAME = "gomoku_prefs"
    private const val KEY_BOARD_SIZE = "board_size"
    private const val KEY_WIN = "win_length"
    private const val KEY_LEVEL = "ai_level"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_PORT = "bot_port"
    private const val KEY_WEBHOOK = "mirror_webhook"

    private fun sp(context: Context) = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun boardSize(context: Context): Int = sp(context).getInt(KEY_BOARD_SIZE, 15).coerceIn(9, 19)

    fun setBoardSize(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_BOARD_SIZE, value).apply()
    }

    fun winLength(context: Context): Int = sp(context).getInt(KEY_WIN, 5).coerceIn(4, 6)

    fun setWinLength(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_WIN, value).apply()
    }

    fun aiLevel(context: Context): Int = sp(context).getInt(KEY_LEVEL, 1).coerceIn(0, 2)

    fun setAiLevel(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_LEVEL, value).apply()
    }

    fun nickname(context: Context): String =
        sp(context).getString(KEY_NICKNAME, "棋友") ?: "棋友"

    fun setNickname(context: Context, value: String) {
        sp(context).edit().putString(KEY_NICKNAME, value).apply()
    }

    fun port(context: Context): Int = sp(context).getInt(KEY_PORT, 18765)

    fun setPort(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_PORT, value).apply()
    }

    fun webhook(context: Context): String = sp(context).getString(KEY_WEBHOOK, "") ?: ""

    fun setWebhook(context: Context, value: String) {
        sp(context).edit().putString(KEY_WEBHOOK, value).apply()
    }
}
