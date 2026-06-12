package com.pillguard.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import com.pillguard.app.R
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pillguard.app.PillGuardApp
import com.pillguard.app.databinding.ActivitySettingsBinding
import com.pillguard.app.network.ApiClient
import com.pillguard.app.receiver.ReminderReceiver
import com.pillguard.app.security.SecurityManager
import com.pillguard.app.service.ReminderService
import com.pillguard.app.util.AuthManager
import com.pillguard.app.util.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "pillguard_prefs"
        const val KEY_REMINDER_VIBRATE = "reminder_vibrate"
        const val KEY_REMINDER_SOUND = "reminder_sound"
        const val KEY_REMINDER_POPUP = "reminder_popup"
    }

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        // 加载当前状态
        loadCurrentStatus()

        // 加载提醒方式设置
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        binding.switchVibrate.isChecked = prefs.getBoolean(KEY_REMINDER_VIBRATE, true)
        binding.switchSound.isChecked = prefs.getBoolean(KEY_REMINDER_SOUND, true)
        binding.switchPopup.isChecked = prefs.getBoolean(KEY_REMINDER_POPUP, true)

        binding.switchVibrate.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_REMINDER_VIBRATE, isChecked).apply()
        }
        binding.switchSound.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_REMINDER_SOUND, isChecked).apply()
        }
        binding.switchPopup.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_REMINDER_POPUP, isChecked).apply()
        }

        // 系统权限状态
        updatePermissionStatus()

        // 电池优化
        binding.btnBatteryOpt.setOnClickListener {
            openBatteryOptimizationSettings()
        }

        // 精准闹钟权限（API 31+）
        binding.btnAlarmPermission.setOnClickListener {
            openAlarmPermissionSettings()
        }

        // 测试提醒
        binding.btnTestReminder.setOnClickListener { testReminder() }

        binding.btnClearHistory.setOnClickListener {
            val isOnline = !AuthManager.isOfflineMode(this)
            val message = if (isOnline) {
                "确定要清除所有本地打卡记录吗？\n\n在线登录状态下，清除本地记录后将自动退出登录，下次登录时会重新从服务器同步记录。"
            } else {
                "确定要清除所有本地打卡记录吗？\n\n此操作不可恢复。"
            }

            AlertDialog.Builder(this)
                .setTitle("清除打卡记录")
                .setMessage(message)
                .setPositiveButton("确认清除") { _, _ ->
                    clearHistory(isOnline)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置返回后刷新权限状态
        updatePermissionStatus()
        loadCurrentStatus()
    }

    private fun loadCurrentStatus() {
        val isOffline = AuthManager.isOfflineMode(this)

        if (isOffline) {
            // 离线模式
            binding.layoutOnlineStatus.visibility = View.GONE
            binding.layoutOfflineStatus.visibility = View.VISIBLE
        } else {
            // 在线模式
            binding.layoutOnlineStatus.visibility = View.VISIBLE
            binding.layoutOfflineStatus.visibility = View.GONE

            val prefs = getSharedPreferences("pillguard_login", MODE_PRIVATE)
            val serverUrl = prefs.getString("server_url", "") ?: ""

            binding.tvServerAddress.text = serverUrl.ifEmpty { "未配置" }
            binding.tvConnectionStatus.text = "点击下方按钮测试连接"
            binding.viewStatusDot.setBackgroundResource(R.drawable.status_dot)

            binding.btnTestConnection.setOnClickListener { testServerConnection() }
        }
    }

    private fun testServerConnection() {
        val prefs = getSharedPreferences("pillguard_login", MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "") ?: ""

        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "未配置服务器地址", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = "测试中..."
        binding.tvConnectionStatus.text = "正在连接..."
        binding.viewStatusDot.setBackgroundResource(R.drawable.status_dot)

        lifecycleScope.launch {
            try {
                val baseUrl = if (serverUrl.endsWith("/api/")) serverUrl else "${serverUrl}/api/"
                ApiClient.init(baseUrl)

                val response = withContext(Dispatchers.IO) {
                    ApiClient.apiService.healthCheck()
                }

                if (response.isSuccessful) {
                    binding.viewStatusDot.setBackgroundResource(R.drawable.status_dot_green)
                    binding.tvConnectionStatus.text = "✅ 连接正常"
                    binding.tvConnectionStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                } else {
                    binding.viewStatusDot.setBackgroundResource(R.drawable.status_dot_red)
                    binding.tvConnectionStatus.text = "❌ 连接失败: HTTP ${response.code()}"
                    binding.tvConnectionStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                }
            } catch (e: Exception) {
                binding.viewStatusDot.setBackgroundResource(R.drawable.status_dot_red)
                binding.tvConnectionStatus.text = "❌ 连接失败: ${e.javaClass.simpleName}"
                binding.tvConnectionStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            } finally {
                binding.btnTestConnection.isEnabled = true
                binding.btnTestConnection.text = "测试连接"
            }
        }
    }

    private fun updatePermissionStatus() {
        // 电池优化状态
        val batteryDisabled = ReminderScheduler.isBatteryOptimizationDisabled(this)
        if (batteryDisabled) {
            binding.tvBatteryStatus.text = "✓ 已关闭（推荐）"
            binding.tvBatteryStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.btnBatteryOpt.isEnabled = false
        } else {
            binding.tvBatteryStatus.text = "⚠ 未关闭 — 可能影响提醒准时性"
            binding.tvBatteryStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
            binding.btnBatteryOpt.isEnabled = true
        }

        // 精准闹钟权限（仅 API 31+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val exactAlarmOk = ReminderScheduler.canScheduleExactAlarms(this)
            if (exactAlarmOk) {
                binding.tvAlarmStatus.text = "✓ 已授权（推荐）"
                binding.tvAlarmStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                binding.btnAlarmPermission.isEnabled = false
            } else {
                binding.tvAlarmStatus.text = "⚠ 未授权 — 可能延迟提醒"
                binding.tvAlarmStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                binding.btnAlarmPermission.isEnabled = true
            }
        } else {
            binding.tvAlarmStatus.text = "✓ 系统版本无需此权限"
            binding.tvAlarmStatus.setTextColor(getColor(android.R.color.darker_gray))
            binding.btnAlarmPermission.isEnabled = false
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            // 部分设备不支持直接跳转，打开应用详情页
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "无法打开系统设置，请手动设置", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openAlarmPermissionSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "请手动在系统设置中授权闹钟权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun testReminder() {
        val serviceIntent = Intent(this, ReminderService::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TIME_SLOT, "morning")
            putExtra(ReminderReceiver.EXTRA_RETRY_COUNT, 0)
            putExtra(ReminderService.EXTRA_IS_TEST, true)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "正在播放测试提醒...", Toast.LENGTH_SHORT).show()
    }

    private fun clearHistory(isOnline: Boolean) {
        val db = (application as PillGuardApp).database
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.medicationRecordDao().deleteAll()
            }
            Toast.makeText(this@SettingsActivity, "打卡记录已清除", Toast.LENGTH_SHORT).show()

            if (isOnline) {
                SecurityManager.clearCredentials(this@SettingsActivity)
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply()
                startActivity(Intent(this@SettingsActivity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        }
    }
}
