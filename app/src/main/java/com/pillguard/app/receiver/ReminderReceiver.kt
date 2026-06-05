package com.pillguard.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.pillguard.app.PillGuardApp
import com.pillguard.app.service.ReminderService
import com.pillguard.app.util.AuthManager
import com.pillguard.app.util.ReminderScheduler
import kotlinx.coroutines.runBlocking

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "ReminderReceiver"
        const val EXTRA_TIME_SLOT = "time_slot"
        const val EXTRA_RETRY_COUNT = "retry_count"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val timeSlot = intent.getStringExtra(EXTRA_TIME_SLOT) ?: return
        val retryCount = intent.getIntExtra(EXTRA_RETRY_COUNT, 0)

        Log.d(TAG, "收到提醒: timeSlot=$timeSlot, retryCount=$retryCount")

        // 首次提醒时，重新调度下一天的提醒
        if (retryCount == 0) {
            ReminderScheduler.scheduleDailyReminders(context)
        }

        // 智能提醒：检查是否已打卡，已打卡则跳过
        val db = (context.applicationContext as PillGuardApp).database
        val today = AuthManager.getCurrentCheckInDate()

        val alreadyCompleted = runBlocking {
            val record = db.medicationRecordDao().getRecord(today, timeSlot)
            record?.completed == true
        }

        if (alreadyCompleted) {
            Log.d(TAG, "已打卡，跳过提醒: timeSlot=$timeSlot")
            return
        }

        // 启动前台服务处理提醒
        val serviceIntent = Intent(context, ReminderService::class.java).apply {
            putExtra(EXTRA_TIME_SLOT, timeSlot)
            putExtra(EXTRA_RETRY_COUNT, retryCount)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
