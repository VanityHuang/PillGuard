package com.pillguard.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.pillguard.app.receiver.ReminderReceiver
import com.pillguard.app.ui.MainActivity
import java.util.Calendar

object ReminderScheduler {

    private const val TAG = "ReminderScheduler"
    private const val REQUEST_CODE_MORNING = 1001
    private const val REQUEST_CODE_EVENING = 1002

    fun scheduleDailyReminders(context: Context) {
        scheduleReminder(context, 8, 0, "morning", REQUEST_CODE_MORNING)
        scheduleReminder(context, 20, 0, "evening", REQUEST_CODE_EVENING)
        Log.d(TAG, "每日提醒已设置: 08:00 和 20:00")
    }

    private fun scheduleReminder(context: Context, hour: Int, minute: Int, timeSlot: String, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.pillguard.ACTION_REMINDER"
            putExtra(ReminderReceiver.EXTRA_TIME_SLOT, timeSlot)
            putExtra(ReminderReceiver.EXTRA_RETRY_COUNT, 0)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        setAlarm(context, alarmManager, calendar.timeInMillis, pendingIntent)
        Log.d(TAG, "提醒已设置: $timeSlot at ${calendar.time}")
    }

    fun scheduleRetryReminder(context: Context, timeSlot: String, retryCount: Int) {
        if (retryCount >= 6) {
            Log.d(TAG, "已达到最大重试次数(6次)，终止提醒: $timeSlot")
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val requestCode = if (timeSlot == "morning") REQUEST_CODE_MORNING + 100 else REQUEST_CODE_EVENING + 100

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.pillguard.ACTION_REMINDER"
            putExtra(ReminderReceiver.EXTRA_TIME_SLOT, timeSlot)
            putExtra(ReminderReceiver.EXTRA_RETRY_COUNT, retryCount)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode + retryCount,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + 5 * 60 * 1000 // 5分钟后
        setAlarm(context, alarmManager, triggerTime, pendingIntent)
        Log.d(TAG, "重试提醒已设置: $timeSlot, 第${retryCount}次, 5分钟后触发")
    }

    fun cancelRetryReminders(context: Context, timeSlot: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val baseRequestCode = if (timeSlot == "morning") REQUEST_CODE_MORNING + 100 else REQUEST_CODE_EVENING + 100

        for (i in 1..6) {
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = "com.pillguard.ACTION_REMINDER"
                putExtra(ReminderReceiver.EXTRA_TIME_SLOT, timeSlot)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                baseRequestCode + i,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
                Log.d(TAG, "已取消重试提醒: $timeSlot, 第${i}次")
            }
        }
    }

    /**
     * 使用 setAlarmClock 设置闹钟（厂商 ROM 兼容性更好）。
     * 如果精准闹钟权限未授权，回退到 setAndAllowWhileIdle。
     */
    private fun setAlarm(context: Context, alarmManager: AlarmManager, triggerTime: Long, pendingIntent: PendingIntent) {
        // 构建 showIntent：用户点击状态栏闹钟图标时打开 MainActivity
        val showIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val showPendingIntent = PendingIntent.getActivity(
            context, 0, showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val alarmInfo = AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent)
                alarmManager.setAlarmClock(alarmInfo, pendingIntent)
                Log.d(TAG, "使用 setAlarmClock: triggerTime=$triggerTime")
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: SecurityException) {
            // 如果没有 SCHEDULE_EXACT_ALARM 权限（API 31+），setAlarmClock 会抛异常
            Log.w(TAG, "无精准闹钟权限，回退到 setAndAllowWhileIdle")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "设置闹钟失败", e2)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setAlarmClock 失败，尝试回退", e)
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "回退闹钟也失败", e2)
            }
        }
    }

    /** 检查是否已关闭电池优化 */
    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true // API < 23，无需检查
        }
    }

    /** 检查是否可以调度精准闹钟（API 31+） */
    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true // API < 31，默认可以
        }
    }
}
