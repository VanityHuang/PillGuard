package com.pillguard.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "medication_records", indices = [Index(value = ["date", "timeSlot", "isDuplicate"])])
data class MedicationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,          // 格式: yyyy-MM-dd
    val timeSlot: String,      // "morning" 或 "evening"
    val completed: Boolean = false,
    val photoPath: String? = null,
    val uploaded: Boolean = false,
    val takenAt: Long? = null, // 服药时间戳
    val isDuplicate: Boolean = false, // 是否为重复打卡
    val createdAt: Long = System.currentTimeMillis()
)
