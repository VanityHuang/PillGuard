package com.pillguard.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
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

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // 加载当前服务器地址
        binding.etServerUrl.setText(prefs.getString("server_url", ""))

        binding.btnSaveServer.setOnClickListener {
            val serverUrl = binding.etServerUrl.text.toString().trim()
            if (serverUrl.isNotEmpty()) {
                prefs.edit().putString("server_url", serverUrl).apply()
                val baseUrl = if (serverUrl.endsWith("/api/")) serverUrl else "${serverUrl}/api/"
                ApiClient.init(baseUrl)
                Toast.makeText(this, "服务器地址已保存", Toast.LENGTH_SHORT).show()
            }
        }

        // 加载提醒方式设置
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
