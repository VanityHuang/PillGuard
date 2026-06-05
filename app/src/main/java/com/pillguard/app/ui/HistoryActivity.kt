package com.pillguard.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pillguard.app.PillGuardApp
import com.pillguard.app.databinding.ActivityHistoryBinding
import com.pillguard.app.ui.adapter.HistoryAdapter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = HistoryAdapter()
        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = this@HistoryActivity.adapter
        }

        loadHistory()
    }

    private fun loadHistory() {
        val db = (application as PillGuardApp).database
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        lifecycleScope.launch {
            val calendar = Calendar.getInstance()
            val endDate = dateFormat.format(calendar.time)
            calendar.add(Calendar.DAY_OF_YEAR, -30)
            val startDate = dateFormat.format(calendar.time)

            val records = db.medicationRecordDao().getRecordsBetweenDates(startDate, endDate)
            adapter.submitList(records)
        }
    }
}
