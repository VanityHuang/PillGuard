package com.pillguard.app.util

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.Calendar

object AuthManager {

    private const val PREFS_NAME = "pillguard_users"
    private const val KEY_USERS = "registered_users"
    private const val SESSION_PREFS = "pillguard_session"

    /**
     * 判断当前时间属于哪个打卡时段
     * 早上：2:00 - 14:00
     * 晚上：14:00 - 次日2:00
     */
    fun getCurrentTimeSlot(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (hour >= 2 && hour < 14) "morning" else "evening"
    }

    /**
     * 获取当前打卡日期
     * 晚上0:00-2:00属于前一天的晚上打卡
     */
    fun getCurrentCheckInDate(): String {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        // 0:00-2:00算前一天的晚上
        if (hour < 2) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return String.format("%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH))
    }

    /**
     * 获取时段的中文标签
     */
    fun getTimeSlotLabel(timeSlot: String): String {
        return if (timeSlot == "morning") "早上" else "晚上"
    }

    /**
     * 注册离线用户
     */
    fun registerUser(context: Context, username: String, password: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val users = getUsers(prefs)
        if (users.containsKey(username)) return false
        users[username] = hashPassword(password)
        saveUsers(prefs, users)
        return true
    }

    /**
     * 离线登录验证
     */
    fun loginOffline(context: Context, username: String, password: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val users = getUsers(prefs)
        val hashedPassword = users[username] ?: return false
        return hashedPassword == hashPassword(password)
    }

    /**
     * 保存登录会话
     */
    fun saveSession(context: Context, username: String, isOffline: Boolean) {
        val prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("username", username)
            .putBoolean("is_offline", isOffline)
            .apply()
    }

    /**
     * 是否为离线模式
     */
    fun isOfflineMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean("is_offline", false)
    }

    /**
     * 获取当前登录用户名
     */
    fun getCurrentUsername(context: Context): String {
        val prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        return prefs.getString("username", "") ?: ""
    }

    /**
     * 清除会话
     */
    fun clearSession(context: Context) {
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * 检查用户是否已注册
     */
    fun isUserRegistered(context: Context, username: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return getUsers(prefs).containsKey(username)
    }

    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getUsers(prefs: SharedPreferences): MutableMap<String, String> {
        val json = prefs.getString(KEY_USERS, "{}") ?: "{}"
        val result = mutableMapOf<String, String>()
        // 简单解析 username:hash,username:hash 格式
        if (json.isNotEmpty() && json != "{}") {
            json.split(",").forEach { entry ->
                val parts = entry.split(":", limit = 2)
                if (parts.size == 2) {
                    result[parts[0]] = parts[1]
                }
            }
        }
        return result
    }

    private fun saveUsers(prefs: SharedPreferences, users: Map<String, String>) {
        val json = users.entries.joinToString(",") { "${it.key}:${it.value}" }
        prefs.edit().putString(KEY_USERS, json).apply()
    }
}
