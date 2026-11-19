package com.gomoku.bot

import android.content.Context
import java.util.UUID

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
    private const val KEY_PLAYER_ID = "player_id"
    private const val KEY_USER_TOKEN = "dingtalk_user_token"
    private const val KEY_USER_TOKEN_AT = "dingtalk_user_token_at"
    private const val KEY_SUB_ID = "dingtalk_sub_id"

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

    /** 本机唯一标识：首次使用时生成，作为默认昵称（UUID）。 */
    fun playerId(context: Context): String {
        val sp = sp(context)
        val existing = sp.getString(KEY_PLAYER_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        sp.edit().putString(KEY_PLAYER_ID, created).apply()
        return created
    }

    fun nickname(context: Context): String =
        sp(context).getString(KEY_NICKNAME, null).orEmpty().ifBlank { playerId(context) }

    fun setNickname(context: Context, value: String) {
        sp(context).edit().putString(KEY_NICKNAME, value).apply()
    }

    fun userToken(context: Context): String = sp(context).getString(KEY_USER_TOKEN, "") ?: ""

    fun userTokenAt(context: Context): Long = sp(context).getLong(KEY_USER_TOKEN_AT, 0L)

    fun saveUserToken(context: Context, token: String) {
        sp(context).edit()
            .putString(KEY_USER_TOKEN, token)
            .putLong(KEY_USER_TOKEN_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearUserToken(context: Context) {
        sp(context).edit().remove(KEY_USER_TOKEN).remove(KEY_USER_TOKEN_AT).apply()
    }

    fun subscribeId(context: Context): String = sp(context).getString(KEY_SUB_ID, "") ?: ""

    fun saveSubscribeId(context: Context, id: String) {
        sp(context).edit().putString(KEY_SUB_ID, id).apply()
    }

    fun appKey(context: Context): String = sp(context).getString(KEY_APP_KEY, "") ?: ""

    fun appSecret(context: Context): String = sp(context).getString(KEY_APP_SECRET, "") ?: ""

    fun robotCode(context: Context): String = sp(context).getString(KEY_ROBOT_CODE, "") ?: ""

    fun peerUserId(context: Context): String = sp(context).getString(KEY_PEER_ID, "") ?: ""

    fun openConversationId(context: Context): String = sp(context).getString(KEY_CONVERSATION, "") ?: ""

    fun setConversationId(context: Context, value: String) {
        sp(context).edit().putString(KEY_CONVERSATION, value).apply()
    }

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
