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
import java.util.concurrent.TimeUnit

class UploadWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "UploadWorker"
        const val KEY_PHOTO_URI = "photo_uri"
        const val KEY_TIME_SLOT = "time_slot"
        const val KEY_DATE = "date"
        const val KEY_IS_DUPLICATE = "is_duplicate"
        const val KEY_CREATED_AT = "created_at"

        /** 照片最大保留时间：24小时 */
        private const val MAX_RETENTION_MS = 24 * 60 * 60 * 1000L
        /** 最大重试延迟：1小时 */
        private const val MAX_BACKOFF_MS = 60 * 60 * 1000L

        fun scheduleUpload(context: Context, photoPath: String, timeSlot: String, isDuplicate: Boolean = false) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val date = dateFormat.format(System.currentTimeMillis())

            val data = workDataOf(
                KEY_PHOTO_URI to photoPath,
                KEY_TIME_SLOT to timeSlot,
                KEY_DATE to date,
                KEY_IS_DUPLICATE to isDuplicate,
                KEY_CREATED_AT to System.currentTimeMillis()
            )

            val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30_000L, TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueue(uploadRequest)

            Log.d(TAG, "上传任务已安排: $photoPath, isDuplicate=$isDuplicate")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val photoPath = inputData.getString(KEY_PHOTO_URI) ?: return@withContext Result.failure()
        val timeSlot = inputData.getString(KEY_TIME_SLOT) ?: return@withContext Result.failure()
        val date = inputData.getString(KEY_DATE) ?: return@withContext Result.failure()
        val isDuplicate = inputData.getBoolean(KEY_IS_DUPLICATE, false)
        val createdAt = inputData.getLong(KEY_CREATED_AT, System.currentTimeMillis())

        val context = applicationContext

        // 24小时超时检查：超过24小时未成功上传，放弃并清理本地照片
        if (System.currentTimeMillis() - createdAt > MAX_RETENTION_MS) {
            Log.w(TAG, "照片已超过24小时未上传，放弃: $photoPath")
            deleteLocalPhoto(context, photoPath)
            return@withContext Result.failure()
        }

        // 检查文件是否还存在（可能已被 PhotoCleanupWorker 清理）
        val file = resolvePhotoFile(context, photoPath)
        if (file == null || !file.exists()) {
            Log.w(TAG, "照片文件已不存在，跳过上传: $photoPath")
            return@withContext Result.failure()
        }

        return@withContext try {
            val credentials = SecurityManager.getCredentials(context)
            if (credentials == null) {
                Log.e(TAG, "用户未登录，无法上传")
                return@withContext Result.failure()
            }
            val (userId, token) = credentials

            val requestBody = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val photoPart = MultipartBody.Part.createFormData("photo", file.name, requestBody)
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

                // 上传成功后删除本地照片
                deleteLocalPhoto(context, photoPath)

                Result.success()
            } else {
                Log.e(TAG, "照片上传失败: ${response.code()}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "上传异常", e)
            Result.retry()
        }
    }

    /**
     * 解析照片文件。支持：
     * - 绝对文件路径（新格式，filesDir 内部存储）
     * - content:// URI（旧格式，MediaStore）
     */
    private fun resolvePhotoFile(context: Context, photoPath: String): File? {
        return try {
            if (photoPath.startsWith("content://")) {
                // 旧格式：content URI → 复制到临时文件
                val inputStream = context.contentResolver.openInputStream(Uri.parse(photoPath))
                val tempFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
                inputStream?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            } else {
                // 新格式：绝对路径
                File(photoPath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析照片文件失败: $photoPath", e)
            null
        }
    }

    private fun deleteLocalPhoto(context: Context, photoPath: String) {
        try {
            if (photoPath.startsWith("content://")) {
                // 旧格式：通过 ContentResolver 删除
                context.contentResolver.delete(Uri.parse(photoPath), null, null)
            } else {
                // 新格式：直接删除文件
                val file = File(photoPath)
                if (file.exists()) {
                    file.delete()
                }
            }
            Log.d(TAG, "本地照片已删除: $photoPath")
        } catch (e: Exception) {
            Log.w(TAG, "删除本地照片失败: $photoPath", e)
        }
    }
}
