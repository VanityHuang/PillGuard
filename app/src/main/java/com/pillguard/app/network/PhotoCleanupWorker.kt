package com.pillguard.app.network

import android.content.Context
import android.util.Log
import androidx.work.*
import com.pillguard.app.data.local.AppDatabase
import com.pillguard.app.ui.CameraActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 定期清理超过24小时的本地照片（说明用户长期离线，照片无法上传）
 * 每6小时执行一次
 */
class PhotoCleanupWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "PhotoCleanupWorker"
        private const val WORK_NAME = "photo_cleanup"
        private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24小时

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PhotoCleanupWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "照片清理任务已调度（每6小时）")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val photosDir = File(context.filesDir, CameraActivity.PHOTOS_DIR)

        if (!photosDir.exists() || !photosDir.isDirectory) {
            Log.d(TAG, "照片目录不存在，跳过清理")
            return@withContext Result.success()
        }

        val now = System.currentTimeMillis()
        var deletedCount = 0
        var totalSize = 0L

        photosDir.listFiles()?.forEach { file ->
            if (file.isFile && now - file.lastModified() > MAX_AGE_MS) {
                totalSize += file.length()
                if (file.delete()) {
                    deletedCount++
                    Log.d(TAG, "已删除过期照片: ${file.name} (${file.length()} bytes)")
                } else {
                    Log.w(TAG, "删除照片失败: ${file.name}")
                }
            }
        }

        if (deletedCount > 0) {
            Log.i(TAG, "清理完成: 删除 $deletedCount 个文件, 释放 ${totalSize / 1024} KB")
        } else {
            Log.d(TAG, "无过期照片需要清理")
        }

        Result.success()
    }
}
