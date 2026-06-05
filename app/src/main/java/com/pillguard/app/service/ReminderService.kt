package com.pillguard.app.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pillguard.app.R
import com.pillguard.app.receiver.ReminderReceiver
import com.pillguard.app.ui.MainActivity
import com.pillguard.app.ui.SettingsActivity
import com.pillguard.app.util.ReminderScheduler
import kotlinx.coroutines.*

class ReminderService : Service() {

    companion object {
        private const val TAG = "ReminderService"
        private const val CHANNEL_ID = "pillguard_reminder_channel"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_IS_TEST = "is_test"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val timeSlot = intent?.getStringExtra(ReminderReceiver.EXTRA_TIME_SLOT) ?: return START_NOT_STICKY
        val retryCount = intent?.getIntExtra(ReminderReceiver.EXTRA_RETRY_COUNT, 0) ?: 0
        val isTest = intent?.getBooleanExtra(EXTRA_IS_TEST, false) ?: false

        // 读取提醒方式设置
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        val enableVibrate = prefs.getBoolean(SettingsActivity.KEY_REMINDER_VIBRATE, true)
        val enableSound = prefs.getBoolean(SettingsActivity.KEY_REMINDER_SOUND, true)
        val enablePopup = prefs.getBoolean(SettingsActivity.KEY_REMINDER_POPUP, true)

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(timeSlot, retryCount, enablePopup),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(timeSlot, retryCount, enablePopup))
        }

        // 播放提醒
        playReminder(timeSlot, retryCount, enableVibrate, enableSound, enablePopup)

        // 测试模式不调度重试
        if (!isTest) {
            if (retryCount == 0) {
                ReminderScheduler.scheduleRetryReminder(this, timeSlot, 1)
            } else if (retryCount < 6) {
                ReminderScheduler.scheduleRetryReminder(this, timeSlot, retryCount + 1)
            }
        }

        // 铃声播放10秒后自动停止服务
        serviceScope.launch {
            delay(12_000) // 等铃声停止后再等2秒
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun playReminder(timeSlot: String, retryCount: Int, enableVibrate: Boolean, enableSound: Boolean, enablePopup: Boolean) {
        serviceScope.launch {
            if (enableVibrate) vibrate()
            if (enableSound) playSound()
            updateNotification(timeSlot, retryCount, enablePopup)
        }
    }

    private fun vibrate() {
        val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
        vibrator?.let {
            if (!it.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                it.vibrate(VibrationEffect.createWaveform(pattern, -1), attrs)
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(pattern, -1)
            }
        }
    }

    private fun playSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(this, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ringtone.audioAttributes = audioAttributes
            } else {
                @Suppress("DEPRECATION")
                ringtone.streamType = AudioManager.STREAM_ALARM
            }
            ringtone.play()

            // 10秒后停止响铃
            serviceScope.launch {
                delay(10_000)
                if (ringtone.isPlaying) {
                    ringtone.stop()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "播放铃声失败", e)
        }
    }

    private fun buildNotification(timeSlot: String, retryCount: Int, enablePopup: Boolean): Notification {
        val slotLabel = if (timeSlot == "morning") "早上" else "晚上"
        val title = "服药提醒 - $slotLabel"
        val message = if (retryCount == 0) {
            "请按时服药并拍照确认！"
        } else {
            "您尚未确认服药，请立即拍照确认！（第${retryCount}次提醒）"
        }

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_camera", true)
            putExtra("time_slot", timeSlot)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (enablePopup) {
            builder.setFullScreenIntent(pendingIntent, true)
        }

        return builder.build()
    }

    private fun updateNotification(timeSlot: String, retryCount: Int, enablePopup: Boolean) {
        val notification = buildNotification(timeSlot, retryCount, enablePopup)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_description)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
