package com.pillguard.app.data.local

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface MedicationRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: MedicationRecord): Long

    @Update
    suspend fun update(record: MedicationRecord)

    @Query("SELECT * FROM medication_records WHERE date = :date ORDER BY timeSlot ASC, isDuplicate ASC")
    fun getRecordsByDate(date: String): LiveData<List<MedicationRecord>>

    @Query("SELECT * FROM medication_records WHERE date BETWEEN :startDate AND :endDate ORDER BY date DESC, timeSlot ASC, isDuplicate ASC")
    suspend fun getRecordsBetweenDates(startDate: String, endDate: String): List<MedicationRecord>

    @Query("SELECT * FROM medication_records WHERE date = :date AND timeSlot = :timeSlot AND isDuplicate = 0 LIMIT 1")
    suspend fun getRecord(date: String, timeSlot: String): MedicationRecord?

    @Query("SELECT * FROM medication_records WHERE date = :date AND timeSlot = :timeSlot AND isDuplicate = 0 LIMIT 1")
    fun getRecordLiveData(date: String, timeSlot: String): MedicationRecord?

    @Query("SELECT * FROM medication_records WHERE date = :date AND timeSlot = :timeSlot AND isDuplicate = :isDuplicate LIMIT 1")
    suspend fun getRecordByDuplicate(date: String, timeSlot: String, isDuplicate: Boolean): MedicationRecord?

    @Query("SELECT COUNT(*) FROM medication_records WHERE date = :date AND timeSlot = :timeSlot AND completed = 1")
    suspend fun getCompletedCount(date: String, timeSlot: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM medication_records WHERE date = :date AND timeSlot = :timeSlot AND isDuplicate = 1 AND completed = 1)")
    suspend fun hasDuplicateRecord(date: String, timeSlot: String): Boolean

    @Query("SELECT * FROM medication_records WHERE completed = 1 AND uploaded = 0 AND isDuplicate = 0")
    suspend fun getUnuploadedRecords(): List<MedicationRecord>

    @Query("UPDATE medication_records SET completed = :completed, photoPath = :photoPath, takenAt = :takenAt WHERE date = :date AND timeSlot = :timeSlot AND isDuplicate = 0")
    suspend fun markCompleted(date: String, timeSlot: String, completed: Boolean, photoPath: String?, takenAt: Long?)

    @Query("UPDATE medication_records SET uploaded = :uploaded WHERE id = :id")
    suspend fun setUploaded(id: Long, uploaded: Boolean)

    @Query("DELETE FROM medication_records WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM medication_records ORDER BY date DESC, timeSlot ASC, isDuplicate ASC")
    fun getAllRecords(): LiveData<List<MedicationRecord>>

    @Query("DELETE FROM medication_records")
    suspend fun deleteAll()
}
