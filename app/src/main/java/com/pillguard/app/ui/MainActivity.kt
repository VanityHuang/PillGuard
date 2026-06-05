package com.pillguard.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.pillguard.app.PillGuardApp
import com.pillguard.app.R
import com.pillguard.app.data.local.MedicationRecord
import com.pillguard.app.databinding.ActivityMainBinding
import com.pillguard.app.security.SecurityManager
import com.pillguard.app.ui.adapter.WeeklyCalendarAdapter
import com.pillguard.app.ui.adapter.WeeklyDayItem
import com.pillguard.app.util.AuthManager
import com.pillguard.app.util.ReminderScheduler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var weeklyAdapter: WeeklyCalendarAdapter
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("M/d", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val timeSlot = result.data?.getStringExtra("time_slot") ?: ""
            val slotLabel = AuthManager.getTimeSlotLabel(timeSlot)
            Toast.makeText(this, "${slotLabel}打卡成功！", Toast.LENGTH_SHORT).show()
            refreshTodayStatus()
            loadWeeklyData()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!SecurityManager.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupWeeklyCalendar()
        setupClickListeners()
        requestPermissions()

        // 设置每日提醒
        ReminderScheduler.scheduleDailyReminders(this)

        // 处理从通知打开的拍照请求
        if (intent?.getBooleanExtra("open_camera", false) == true) {
            openCamera(intent.getStringExtra("time_slot") ?: AuthManager.getCurrentTimeSlot())
        }

        refreshTodayStatus()
        loadWeeklyData()
    }

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_logout -> {
                    SecurityManager.clearCredentials(this)
                    AuthManager.clearSession(this)
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupWeeklyCalendar() {
        weeklyAdapter = WeeklyCalendarAdapter()
        binding.rvWeeklyCalendar.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 7)
            adapter = weeklyAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnTakePhoto.setOnClickListener {
            val timeSlot = AuthManager.getCurrentTimeSlot()
            openCamera(timeSlot)
        }

        binding.tvViewAll.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    private fun openCamera(timeSlot: String) {
        val isOffline = AuthManager.isOfflineMode(this)
        val intent = Intent(this, CameraActivity::class.java).apply {
            putExtra("time_slot", timeSlot)
            putExtra("is_offline", isOffline)
        }
        cameraLauncher.launch(intent)
    }

    private fun refreshTodayStatus() {
        val today = AuthManager.getCurrentCheckInDate()
        val db = (application as PillGuardApp).database

        lifecycleScope.launch {
            val morningRecord = db.medicationRecordDao().getRecord(today, "morning")
                ?: db.medicationRecordDao().getRecordByDuplicate(today, "morning", true)
            val eveningRecord = db.medicationRecordDao().getRecord(today, "evening")
                ?: db.medicationRecordDao().getRecordByDuplicate(today, "evening", true)

            // 检查是否有重复打卡
            val morningDuplicate = db.medicationRecordDao().hasDuplicateRecord(today, "morning")
            val eveningDuplicate = db.medicationRecordDao().hasDuplicateRecord(today, "evening")

            updateStatusText(binding.tvMorningStatus, morningRecord, "morning", morningDuplicate)
            updateStatusText(binding.tvEveningStatus, eveningRecord, "evening", eveningDuplicate)
        }
    }

    private fun updateStatusText(textView: TextView, record: MedicationRecord?, timeSlot: String, hasDuplicate: Boolean) {
        if (record?.completed == true) {
            val timeStr = record.takenAt?.let {
                val cal = Calendar.getInstance().apply { timeInMillis = it }
                String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            } ?: ""

            if (hasDuplicate) {
                // 重复打卡：紫色标识
                textView.text = "已完成 $timeStr (重复)"
                textView.setTextColor(ContextCompat.getColor(this, R.color.purple))
                textView.setBackgroundResource(R.drawable.bg_status_duplicate)
            } else {
                textView.text = "已完成 $timeStr"
                textView.setTextColor(ContextCompat.getColor(this, R.color.green))
                textView.setBackgroundResource(R.drawable.bg_status_completed)
            }
        } else {
            // 判断是否已过打卡时段
            val currentSlot = AuthManager.getCurrentTimeSlot()
            val isMorning = timeSlot == "morning"
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

            // 判断该时段是否已过
            val slotPassed = if (isMorning) {
                currentHour >= 14 // 早上时段2:00-14:00已过
            } else {
                currentHour >= 2 && currentHour < 14 // 晚上时段14:00-次日2:00，如果现在是2:00-14:00说明昨晚的已过
            }

            if (slotPassed) {
                textView.text = "未完成"
                textView.setTextColor(ContextCompat.getColor(this, R.color.red))
                textView.setBackgroundResource(R.drawable.bg_status_missed)
            } else {
                textView.text = "待打卡"
                textView.setTextColor(ContextCompat.getColor(this, R.color.orange))
                textView.setBackgroundResource(R.drawable.bg_status_pending)
            }
        }
    }

    private fun loadWeeklyData() {
        val db = (application as PillGuardApp).database
        val calendar = Calendar.getInstance()

        lifecycleScope.launch {
            val items = mutableListOf<WeeklyDayItem>()
            val dayNames = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

            for (i in 6 downTo 0) {
                val cal = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, -i)
                }
                val dateStr = dateFormat.format(cal.time)
                val morningRecord = db.medicationRecordDao().getRecord(dateStr, "morning")
                val eveningRecord = db.medicationRecordDao().getRecord(dateStr, "evening")
                val morningDuplicate = db.medicationRecordDao().hasDuplicateRecord(dateStr, "morning")
                val eveningDuplicate = db.medicationRecordDao().hasDuplicateRecord(dateStr, "evening")

                items.add(
                    WeeklyDayItem(
                        date = dateStr,
                        displayDate = displayDateFormat.format(cal.time),
                        dayOfWeek = dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1],
                        morningCompleted = morningRecord?.completed == true,
                        eveningCompleted = eveningRecord?.completed == true,
                        morningDuplicate = morningDuplicate,
                        eveningDuplicate = eveningDuplicate
                    )
                )
            }

            weeklyAdapter.submitList(items)
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (permissions.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                val allGranted = results.all { it.value }
                if (!allGranted) {
                    Toast.makeText(this, "部分功能需要权限才能正常使用", Toast.LENGTH_SHORT).show()
                }
            }.launch(permissions.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTodayStatus()
        loadWeeklyData()
    }
}
