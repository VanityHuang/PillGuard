package com.pillguard.app.network

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.*
import com.pillguard.app.data.local.AppDatabase
import com.pillguard.app.data.local.MedicationRecord
import com.pillguard.app.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class UploadWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "UploadWorker"
        const val KEY_PHOTO_URI = "photo_uri"
        const val KEY_TIME_SLOT = "time_slot"
        const val KEY_DATE = "date"
        const val KEY_IS_DUPLICATE = "is_duplicate"

        fun scheduleUpload(context: Context, photoUri: String, timeSlot: String, isDuplicate: Boolean = false) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val date = dateFormat.format(System.currentTimeMillis())

            val data = workDataOf(
                KEY_PHOTO_URI to photoUri,
                KEY_TIME_SLOT to timeSlot,
                KEY_DATE to date,
                KEY_IS_DUPLICATE to isDuplicate
            )

            val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueue(uploadRequest)

            Log.d(TAG, "上传任务已安排: $photoUri, isDuplicate=$isDuplicate")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val photoUri = inputData.getString(KEY_PHOTO_URI) ?: return@withContext Result.failure()
        val timeSlot = inputData.getString(KEY_TIME_SLOT) ?: return@withContext Result.failure()
        val date = inputData.getString(KEY_DATE) ?: return@withContext Result.failure()
        val isDuplicate = inputData.getBoolean(KEY_IS_DUPLICATE, false)

        return@withContext try {
            val context = applicationContext
            val credentials = SecurityManager.getCredentials(context)
            if (credentials == null) {
                Log.e(TAG, "用户未登录，无法上传")
                return@withContext Result.failure()
            }
            val (userId, token) = credentials

            // 将Uri转为File
            val inputStream = context.contentResolver.openInputStream(Uri.parse(photoUri))
            val tempFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
            inputStream?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val requestBody = tempFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val photoPart = MultipartBody.Part.createFormData("photo", tempFile.name, requestBody)
            val userIdBody = userId.toRequestBody("text/plain".toMediaTypeOrNull())
            val dateBody = date.toRequestBody("text/plain".toMediaTypeOrNull())
            val timeSlotBody = timeSlot.toRequestBody("text/plain".toMediaTypeOrNull())
            val isDuplicateBody = isDuplicate.toString().toRequestBody("text/plain".toMediaTypeOrNull())

            ApiClient.setAuthToken(token)
            val response = ApiClient.apiService.uploadPhoto(photoPart, userIdBody, dateBody, timeSlotBody, isDuplicateBody)

            if (response.isSuccessful && response.body()?.success == true) {
                Log.d(TAG, "照片上传成功, isDuplicate=$isDuplicate")

                // 更新本地数据库记录
                val db = AppDatabase.getInstance(context)
                val record = db.medicationRecordDao().getRecordByDuplicate(date, timeSlot, isDuplicate)
                if (record != null) {
                    db.medicationRecordDao().update(record.copy(uploaded = true))
                }

                // 上传成功后删除本地缓存
                tempFile.delete()
                try {
                    context.contentResolver.delete(Uri.parse(photoUri), null, null)
                    Log.d(TAG, "本地照片缓存已删除")
                } catch (e: Exception) {
                    Log.w(TAG, "删除本地照片失败", e)
                }

                Result.success()
            } else {
                Log.e(TAG, "照片上传失败: ${response.code()}")
                tempFile.delete()
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "上传异常", e)
            Result.retry()
        }
    }
}
