package com.pillguard.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pillguard.app.PillGuardApp
import com.pillguard.app.data.local.MedicationRecord
import com.pillguard.app.databinding.ActivityCameraBinding
import com.pillguard.app.network.UploadWorker
import com.pillguard.app.util.AuthManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        const val PHOTOS_DIR = "pillsguard_photos"
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var timeSlot: String = "morning"
    private var isOffline: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        timeSlot = intent.getStringExtra("time_slot") ?: AuthManager.getCurrentTimeSlot()
        isOffline = intent.getBooleanExtra("is_offline", false)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnClose.setOnClickListener { finish() }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Log.e(TAG, "相机绑定失败", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        if (isOffline) {
            // 离线模式：仍保存照片到内部存储（上线后上传）
            captureToInternalStorage(imageCapture)
            return
        }

        // 在线模式：保存照片到内部存储并排队上传
        captureToInternalStorage(imageCapture)
    }

    private fun captureToInternalStorage(imageCapture: ImageCapture) {
        val photosDir = File(filesDir, PHOTOS_DIR)
        if (!photosDir.exists()) photosDir.mkdirs()

        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val fileName = "PillGuard_${dateFormat.format(System.currentTimeMillis())}.jpg"
        val photoFile = File(photosDir, fileName)

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "照片已保存: ${photoFile.absolutePath}")
                    // 传递绝对路径，不再使用 content:// URI
                    saveCheckInRecord(photoFile.absolutePath)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败", exception)
                    Toast.makeText(this@CameraActivity, "拍照失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun saveCheckInRecord(photoPath: String?) {
        val db = (application as PillGuardApp).database
        val date = AuthManager.getCurrentCheckInDate()
        val now = System.currentTimeMillis()

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val timeStr = String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        val slotLabel = AuthManager.getTimeSlotLabel(timeSlot)

        lifecycleScope.launch(Dispatchers.IO) {
            val existingRecord = db.medicationRecordDao().getRecord(date, timeSlot)

            if (existingRecord?.completed == true) {
                // 已有打卡记录，这是重复打卡
                runOnUiThread {
                    AlertDialog.Builder(this@CameraActivity)
                        .setTitle("重复打卡提醒")
                        .setMessage("您在${slotLabel}时段已经打卡过了（${existingRecord.takenAt?.let {
                            val c = java.util.Calendar.getInstance().apply { timeInMillis = it }
                            String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
                        } ?: ""}），确定要再次打卡吗？")
                        .setPositiveButton("确认打卡") { _, _ ->
                            // 保存重复打卡记录到本地
                            lifecycleScope.launch(Dispatchers.IO) {
                                db.medicationRecordDao().insert(
                                    MedicationRecord(
                                        date = date,
                                        timeSlot = timeSlot,
                                        completed = true,
                                        photoPath = photoPath,
                                        uploaded = photoPath == null,
                                        takenAt = now,
                                        isDuplicate = true
                                    )
                                )

                                // 有照片则排队上传（WorkManager 会等到网络可用）
                                if (photoPath != null) {
                                    UploadWorker.scheduleUpload(
                                        this@CameraActivity,
                                        photoPath,
                                        timeSlot,
                                        isDuplicate = true
                                    )
                                }
                            }
                            Toast.makeText(this@CameraActivity, "${slotLabel}重复打卡已记录 $timeStr", Toast.LENGTH_SHORT).show()
                            setResult(RESULT_OK, intent.apply {
                                putExtra("time_slot", timeSlot)
                                putExtra("is_duplicate", true)
                            })
                            finish()
                        }
                        .setNegativeButton("取消") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .setCancelable(false)
                        .show()
                }
            } else if (existingRecord != null) {
                // 有记录但未完成，更新为已完成
                db.medicationRecordDao().update(
                    existingRecord.copy(
                        completed = true,
                        photoPath = photoPath,
                        takenAt = now,
                        uploaded = photoPath == null
                    )
                )
                // 有照片则排队上传（WorkManager 会等到网络可用）
                if (photoPath != null) {
                    UploadWorker.scheduleUpload(this@CameraActivity, photoPath, timeSlot)
                }
                runOnUiThread {
                    Toast.makeText(this@CameraActivity, "${slotLabel}打卡成功！$timeStr", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK, intent.apply {
                        putExtra("time_slot", timeSlot)
                    })
                    finish()
                }
            } else {
                // 新打卡记录
                db.medicationRecordDao().insert(
                    MedicationRecord(
                        date = date,
                        timeSlot = timeSlot,
                        completed = true,
                        photoPath = photoPath,
                        uploaded = photoPath == null,
                        takenAt = now
                    )
                )
                // 有照片则排队上传（WorkManager 会等到网络可用）
                if (photoPath != null) {
                    UploadWorker.scheduleUpload(this@CameraActivity, photoPath, timeSlot)
                }
                runOnUiThread {
                    Toast.makeText(this@CameraActivity, "${slotLabel}打卡成功！$timeStr", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK, intent.apply {
                        putExtra("time_slot", timeSlot)
                    })
                    finish()
                }
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "需要相机权限才能使用拍照功能", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
