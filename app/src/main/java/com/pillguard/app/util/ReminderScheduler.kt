package com.pillguard.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.pillguard.app.receiver.ReminderReceiver
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
            // 如果时间已过，设置到明天
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        // 使用精确闹钟确保准时提醒，每次触发后重新调度实现每日重复
        setExactAlarm(alarmManager, calendar.timeInMillis, pendingIntent)

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

        setExactAlarm(alarmManager, triggerTime, pendingIntent)

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

    private fun setExactAlarm(alarmManager: AlarmManager, triggerTime: Long, pendingIntent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
    }
}
