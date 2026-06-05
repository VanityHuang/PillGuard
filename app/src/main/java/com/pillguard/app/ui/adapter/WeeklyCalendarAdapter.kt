package com.pillguard.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pillguard.app.R

data class WeeklyDayItem(
    val date: String,          // yyyy-MM-dd
    val displayDate: String,   // M/d
    val dayOfWeek: String,     // 周一
    val morningCompleted: Boolean,
    val eveningCompleted: Boolean,
    val morningDuplicate: Boolean = false,
    val eveningDuplicate: Boolean = false
)

class WeeklyCalendarAdapter : RecyclerView.Adapter<WeeklyCalendarAdapter.ViewHolder>() {

    private var items: List<WeeklyDayItem> = emptyList()

    fun submitList(newItems: List<WeeklyDayItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDayOfWeek: TextView = view.findViewById(R.id.tvDayOfWeek)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val indicatorMorning: View = view.findViewById(R.id.indicatorMorning)
        val indicatorEvening: View = view.findViewById(R.id.indicatorEvening)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_weekly_day, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvDayOfWeek.text = item.dayOfWeek
        holder.tvDate.text = item.displayDate

        // 早上指示点
        holder.indicatorMorning.setBackgroundResource(
            when {
                item.morningDuplicate -> R.drawable.bg_status_duplicate
                item.morningCompleted -> R.drawable.bg_status_completed
                else -> R.drawable.bg_status_missed
            }
        )

        // 晚上指示点
        holder.indicatorEvening.setBackgroundResource(
            when {
                item.eveningDuplicate -> R.drawable.bg_status_duplicate
                item.eveningCompleted -> R.drawable.bg_status_completed
                else -> R.drawable.bg_status_missed
            }
        )
    }

    override fun getItemCount() = items.size
}
