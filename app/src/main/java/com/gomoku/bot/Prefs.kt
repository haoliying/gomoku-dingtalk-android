package com.gomoku.bot

import android.content.Context

/** 本地偏好设置。 */
object Prefs {

    private const val NAME = "gomoku_prefs"
    private const val KEY_BOARD_SIZE = "board_size"
    private const val KEY_WIN = "win_length"
    private const val KEY_LEVEL = "ai_level"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_APP_KEY = "dingtalk_app_key"
    private const val KEY_APP_SECRET = "dingtalk_app_secret"
    private const val KEY_ROBOT_CODE = "dingtalk_robot_code"
    private const val KEY_PEER_ID = "dingtalk_peer_id"
    private const val KEY_CONVERSATION = "dingtalk_conversation"
    private const val KEY_ROOM = "dingtalk_room"
    private const val KEY_COLOR = "dingtalk_color"

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

    fun appKey(context: Context): String = sp(context).getString(KEY_APP_KEY, "") ?: ""

    fun appSecret(context: Context): String = sp(context).getString(KEY_APP_SECRET, "") ?: ""

    fun robotCode(context: Context): String = sp(context).getString(KEY_ROBOT_CODE, "") ?: ""

    fun peerUserId(context: Context): String = sp(context).getString(KEY_PEER_ID, "") ?: ""

    fun openConversationId(context: Context): String = sp(context).getString(KEY_CONVERSATION, "") ?: ""

    fun room(context: Context): String = sp(context).getString(KEY_ROOM, "") ?: ""

    fun color(context: Context): Int = sp(context).getInt(KEY_COLOR, 1)

    fun saveDingTalk(
        context: Context,
        appKey: String,
        appSecret: String,
        robotCode: String,
        peerUserId: String,
        openConversationId: String,
        room: String,
        color: Int,
        nickname: String
    ) {
        sp(context).edit()
            .putString(KEY_APP_KEY, appKey)
            .putString(KEY_APP_SECRET, appSecret)
            .putString(KEY_ROBOT_CODE, robotCode)
            .putString(KEY_PEER_ID, peerUserId)
            .putString(KEY_CONVERSATION, openConversationId)
            .putString(KEY_ROOM, room)
            .putInt(KEY_COLOR, color)
            .putString(KEY_NICKNAME, nickname)
            .apply()
    }
}
