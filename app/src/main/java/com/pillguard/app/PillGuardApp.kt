package com.pillguard.app

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.pillguard.app.data.local.AppDatabase
import com.pillguard.app.network.PhotoCleanupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class PillGuardApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    companion object {
        private const val TAG = "PillGuardApp"
        private const val PREFS_NAME = "pillguard_migration"
        private const val KEY_MIGRATED_V2 = "migrated_to_v2"
    }

    override fun onCreate() {
        super.onCreate()
        // 启动定期照片清理任务（每6小时清理超过24小时的本地照片）
        PhotoCleanupWorker.schedule(this)
        // 一次性迁移：清理旧版 MediaStore Pictures/PillGuard/ 照片
        migrateCleanOldPhotos()
    }

    /**
     * 一次性迁移：删除旧版本遗留在 MediaStore Pictures/PillGuard/ 目录的照片
     * 这些照片是公开存储的，新的 PhotoCleanupWorker 无法管控它们
     */
    private fun migrateCleanOldPhotos() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED_V2, false)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                var deleted = 0

                // 方式1：通过 MediaStore 查询 Pictures/PillGuard 目录中的照片
                val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH)
                val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%Pictures/PillGuard%")

                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, selectionArgs, null
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        )
                        contentResolver.delete(uri, null, null)
                        deleted++
                    }
                }

                // 方式2：直接删除文件系统中的 Pictures/PillGuard 目录（兜底）
                try {
                    val pillguardDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "PillGuard"
                    )
                    if (pillguardDir.exists()) {
                        pillguardDir.deleteRecursively()
                        Log.i(TAG, "已删除 Pictures/PillGuard 目录")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "文件系统清理失败（可能无权限）: ${e.message}")
                }

                prefs.edit().putBoolean(KEY_MIGRATED_V2, true).apply()
                if (deleted > 0) {
                    Log.i(TAG, "旧版照片迁移完成：已删除 $deleted 张 MediaStore 照片，释放约 ${deleted * 2}MB")
                } else {
                    Log.i(TAG, "未发现旧版照片，迁移跳过")
                }
            } catch (e: Exception) {
                Log.e(TAG, "旧版照片清理失败", e)
                // 即使失败也标记完成，避免每次启动都重试
                prefs.edit().putBoolean(KEY_MIGRATED_V2, true).apply()
            }
        }
    }
}
