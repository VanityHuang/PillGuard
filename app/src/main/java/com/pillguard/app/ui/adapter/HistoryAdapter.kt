package com.pillguard.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pillguard.app.R
import com.pillguard.app.data.local.MedicationRecord
import java.util.Calendar

class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var records: List<MedicationRecord> = emptyList()

    fun submitList(newRecords: List<MedicationRecord>) {
        records = newRecords
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvTimeSlot: TextView = view.findViewById(R.id.tvTimeSlot)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        holder.tvDate.text = record.date

        val slotLabel = if (record.timeSlot == "morning") "早上" else "晚上"
        holder.tvTimeSlot.text = slotLabel

        if (record.completed) {
            val timeStr = record.takenAt?.let {
                val cal = Calendar.getInstance().apply { timeInMillis = it }
                String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            } ?: ""

            if (record.isDuplicate) {
                holder.tvStatus.text = "重复打卡 $timeStr"
                holder.tvStatus.setTextColor(holder.itemView.context.getColor(R.color.purple))
                holder.tvStatus.setBackgroundResource(R.drawable.bg_status_duplicate)
            } else {
                holder.tvStatus.text = "已完成 $timeStr"
                holder.tvStatus.setTextColor(holder.itemView.context.getColor(R.color.green))
                holder.tvStatus.setBackgroundResource(R.drawable.bg_status_completed)
            }
        } else {
            holder.tvStatus.text = "未完成"
            holder.tvStatus.setTextColor(holder.itemView.context.getColor(R.color.red))
            holder.tvStatus.setBackgroundResource(R.drawable.bg_status_missed)
        }
    }

    override fun getItemCount() = records.size
}
