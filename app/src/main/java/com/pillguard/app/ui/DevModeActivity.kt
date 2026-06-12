package com.pillguard.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pillguard.app.PillGuardApp
import com.pillguard.app.databinding.ActivityDevModeBinding
import com.pillguard.app.network.ApiClient
import com.pillguard.app.network.LoginRequest
import com.pillguard.app.security.SecurityManager
import com.pillguard.app.util.AuthManager
import com.pillguard.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class DevModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDevModeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        loadAllInfo()

        binding.btnTestConnection.setOnClickListener { testServerConnection() }
    }

    private fun loadAllInfo() {
        loadDeviceInfo()
        loadLoginState()
        loadDbStats()
        loadNetworkState()
        loadServerState()
        loadPreferences()
    }

    private fun loadDeviceInfo() {
        val info = buildString {
            appendLine("型号: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("品牌: ${Build.BRAND}")
            appendLine("设备: ${Build.DEVICE}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("构建: ${Build.DISPLAY}")
            appendLine("主板: ${Build.BOARD}")
            appendLine("硬件: ${Build.HARDWARE}")
            appendLine("包名: ${packageName}")

            // APP 版本
            try {
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                appendLine("版本: ${pInfo.versionName} (${pInfo.longVersionCode})")
            } catch (e: Exception) {
                appendLine("版本: 未知")
            }

            // 内存
            val runtime = Runtime.getRuntime()
            val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMB = runtime.maxMemory() / 1024 / 1024
            appendLine("内存: ${usedMB}MB / ${maxMB}MB")

            // 存储
            val filesDir = filesDir
            val dbSize = getDbSize()
            val photosDir = File(filesDir, "pillsguard_photos")
            val photosCount = if (photosDir.exists()) photosDir.listFiles()?.size ?: 0 else 0
            appendLine("数据库: $dbSize")
            appendLine("本地照片: ${photosCount} 张")
        }
        binding.tvDeviceInfo.text = info.trimEnd()
    }

    private fun loadLoginState() {
        val info = buildString {
            val isLoggedIn = SecurityManager.isLoggedIn(this@DevModeActivity)
            val isOffline = AuthManager.isOfflineMode(this@DevModeActivity)
            val username = AuthManager.getCurrentUsername(this@DevModeActivity)

            appendLine("登录状态: ${if (isLoggedIn) "✅ 已登录" else "❌ 未登录"}")
            appendLine("模式: ${if (isOffline) "📱 离线" else "🌐 在线"}")
            appendLine("用户名: ${username.ifEmpty { "无" }}")

            if (isLoggedIn) {
                val credentials = SecurityManager.getCredentials(this@DevModeActivity)
                if (credentials != null) {
                    appendLine("用户ID: ${credentials.first}")
                    appendLine("Token: ${credentials.second.take(20)}...")
                }
            }

            // 检查 Token 过期情况
            val loginPrefs = getSharedPreferences("pillguard_login", MODE_PRIVATE)
            val serverUrl = loginPrefs.getString("server_url", "")
            val savedUser = loginPrefs.getString("username", "")
            appendLine("保存的服务器: ${serverUrl?.ifEmpty { "无" }}")
            appendLine("保存的用户: ${savedUser?.ifEmpty { "无" }}")
        }
        binding.tvLoginState.text = info.trimEnd()
    }

    private fun loadDbStats() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                val db = (application as PillGuardApp).database
                val dao = db.medicationRecordDao()

                buildString {
                    // 总记录数
                    val allRecords = dao.getAllRecordsSync()
                    appendLine("总记录数: ${allRecords.size}")

                    // 未上传记录
                    val unuploaded = dao.getUnuploadedRecordsSync()
                    appendLine("未上传: ${unuploaded.size}")

                    // 按日期统计
                    val dateGroups = allRecords.groupBy { it.date }
                    appendLine("日期范围: ${dateGroups.keys.minOrNull() ?: "无"} ~ ${dateGroups.keys.maxOrNull() ?: "无"}")

                    // 按用户统计（通过 photoPath 前缀推断）
                    val userIds = allRecords.mapNotNull { it.photoPath?.split("_")?.firstOrNull() }.distinct()
                    appendLine("用户数: ${userIds.size}")

                    // 有照片的记录
                    val withPhotos = allRecords.count { it.photoPath != null }
                    appendLine("有照片记录: $withPhotos")

                    // 已上传
                    val uploaded = allRecords.count { it.uploaded }
                    appendLine("已上传: $uploaded")

                    // 重复打卡
                    val duplicates = allRecords.count { it.isDuplicate }
                    appendLine("重复打卡: $duplicates")
                }
            }
            binding.tvDbStats.text = info.trimEnd()
        }
    }

    private fun loadNetworkState() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val isOnline = caps != null && (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        )

        // 电池
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging

        val info = buildString {
            appendLine("网络: ${if (isOnline) "✅ 在线" else "❌ 离线"}")
            if (caps != null) {
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> appendLine("类型: WiFi")
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> appendLine("类型: 蜂窝数据")
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> appendLine("类型: 有线网络")
                    else -> appendLine("类型: 其他")
                }
            }
            appendLine("电量: ${batteryLevel}% ${if (isCharging) "⚡ 充电中" else ""}")
        }
        binding.tvNetworkState.text = info.trimEnd()
    }

    private fun loadServerState() {
        val prefs = getSharedPreferences("pillguard_login", MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "") ?: ""
        val isOffline = AuthManager.isOfflineMode(this)

        val info = buildString {
            if (isOffline) {
                appendLine("状态: 离线模式")
                appendLine("无需连接服务器")
            } else if (serverUrl.isEmpty()) {
                appendLine("状态: 未配置服务器地址")
            } else {
                appendLine("服务器: $serverUrl")
                appendLine("状态: 点击下方按钮测试连接")
            }
        }
        binding.tvServerState.text = info.trimEnd()
    }

    private fun testServerConnection() {
        val prefs = getSharedPreferences("pillguard_login", MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "") ?: ""

        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "未配置服务器地址", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = "测试中..."
        binding.tvServerState.text = "正在诊断连接..."

        lifecycleScope.launch {
            val info = StringBuilder()
            try {
                // 提取主机名和端口
                val url = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
                val isHttps = url.startsWith("https://")
                val host = url.removePrefix("https://").removePrefix("http://")
                    .split("/").first().split(":").first()
                val port = if (url.contains(":") && url.split(":").size >= 3) {
                    url.split(":")[2].split("/").first().toIntOrNull()
                } else null

                info.appendLine("服务器: ${host}:${port ?: if (isHttps) 443 else 80}")
                info.appendLine("协议: ${if (isHttps) "HTTPS" else "HTTP"}")

                // 1. DNS 解析测试
                info.appendLine("\n--- DNS 解析 ---")
                val startDns = System.currentTimeMillis()
                val address = withContext(Dispatchers.IO) {
                    InetAddress.getByName(host)
                }
                val dnsTime = System.currentTimeMillis() - startDns
                info.appendLine("IP: ${address.hostAddress}")
                info.appendLine("耗时: ${dnsTime}ms")

                // 2. 创建独立 OkHttp 客户端（绕过 ApiClient 缓存）
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .build()

                // 3. Health 检查
                info.appendLine("\n--- Health 检查 ---")
                val healthUrl = "${url}api/health".replace("//api", "/api")
                val healthRequest = Request.Builder().url(healthUrl).get().build()
                val startHealth = System.currentTimeMillis()
                try {
                    val healthResponse = withContext(Dispatchers.IO) {
                        client.newCall(healthRequest).execute()
                    }
                    val healthTime = System.currentTimeMillis() - startHealth
                    healthResponse.use {
                        if (it.isSuccessful) {
                            info.appendLine("✅ 成功 (HTTP ${it.code})")
                            info.appendLine("耗时: ${healthTime}ms")
                            info.appendLine("响应: ${it.body?.string()?.take(100)}")
                        } else {
                            info.appendLine("❌ 失败: HTTP ${it.code}")
                            info.appendLine("耗时: ${healthTime}ms")
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    info.appendLine("❌ 连接超时 (${System.currentTimeMillis() - startHealth}ms)")
                    info.appendLine("原因: 网络延迟过高或服务器不可达")
                } catch (e: java.net.ConnectException) {
                    info.appendLine("❌ 连接被拒绝")
                    info.appendLine("原因: ${e.message}")
                } catch (e: java.net.UnknownHostException) {
                    info.appendLine("❌ DNS 解析失败: ${e.message}")
                } catch (e: Exception) {
                    info.appendLine("❌ 异常: ${e.javaClass.simpleName}")
                    info.appendLine("详情: ${e.message}")
                }

                // 4. Login 测试
                info.appendLine("\n--- 登录接口测试 ---")
                val loginUrl = "${url}api/auth/login".replace("//api", "/api")
                val jsonBody = "{\"username\":\"test\",\"password\":\"test\"}"
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = jsonBody.toRequestBody(mediaType)
                val loginRequest = Request.Builder().url(loginUrl).post(body).build()
                val startLogin = System.currentTimeMillis()
                try {
                    val loginResponse = withContext(Dispatchers.IO) {
                        client.newCall(loginRequest).execute()
                    }
                    val loginTime = System.currentTimeMillis() - startLogin
                    loginResponse.use {
                        val code = it.code
                        val normalCode = code == 401 || code == 200
                        info.appendLine("HTTP ${code} (${if (normalCode) "正常" else "异常"})")
                        info.appendLine("耗时: ${loginTime}ms")
                        if (it.body != null) {
                            info.appendLine("响应: ${it.body?.string()?.take(100)}")
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    info.appendLine("❌ 连接超时 (${System.currentTimeMillis() - startLogin}ms)")
                } catch (e: Exception) {
                    info.appendLine("❌ 异常: ${e.javaClass.simpleName}")
                    info.appendLine("详情: ${e.message}")
                }

            } catch (e: Exception) {
                info.appendLine("\n❌ 诊断失败: ${e.javaClass.simpleName}")
                info.appendLine(e.message ?: "")
            } finally {
                binding.tvServerState.text = info.toString().trimEnd()
                binding.btnTestConnection.isEnabled = true
                binding.btnTestConnection.text = "测试连接"
            }
        }
    }

    private fun loadPreferences() {
        val info = buildString {
            // pillguard_login
            val loginPrefs = getSharedPreferences("pillguard_login", MODE_PRIVATE)
            appendLine("=== pillguard_login ===")
            loginPrefs.all.forEach { (k, v) ->
                when (v) {
                    is String -> {
                        if (k == "password") appendLine("  $k: ***隐藏***")
                        else appendLine("  $k: $v")
                    }
                    is Boolean -> appendLine("  $k: $v")
                    is Int -> appendLine("  $k: $v")
                    is Long -> appendLine("  $k: $v")
                    else -> appendLine("  $k: $v")
                }
            }

            // pillguard_session
            val sessionPrefs = getSharedPreferences("pillguard_session", MODE_PRIVATE)
            appendLine("\n=== pillguard_session ===")
            sessionPrefs.all.forEach { (k, v) ->
                appendLine("  $k: $v")
            }

            // pillguard_prefs
            val appPrefs = getSharedPreferences("pillguard_prefs", MODE_PRIVATE)
            appendLine("\n=== pillguard_prefs ===")
            appPrefs.all.forEach { (k, v) ->
                appendLine("  $k: $v")
            }

            // pillguard_secure_prefs
            val securePrefs = getSharedPreferences("pillguard_secure_prefs", MODE_PRIVATE)
            appendLine("\n=== pillguard_secure_prefs ===")
            securePrefs.all.forEach { (k, v) ->
                appendLine("  $k: ${(v as? String)?.take(30)}...")
            }

            // pillguard_migration
            val migrationPrefs = getSharedPreferences("pillguard_migration", MODE_PRIVATE)
            appendLine("\n=== pillguard_migration ===")
            migrationPrefs.all.forEach { (k, v) ->
                appendLine("  $k: $v")
            }
        }
        binding.tvPrefs.text = info.trimEnd()
    }

    private fun getDbSize(): String {
        val dbFile = getDatabasePath("pillguard_database")
        return if (dbFile.exists()) {
            val sizeKB = dbFile.length() / 1024
            if (sizeKB > 1024) "${sizeKB / 1024}MB" else "${sizeKB}KB"
        } else "未创建"
    }

    // Extension functions for DAO to avoid suspend in buildString
    private suspend fun com.pillguard.app.data.local.MedicationRecordDao.getAllRecordsSync(): List<com.pillguard.app.data.local.MedicationRecord> {
        return withContext(Dispatchers.IO) {
            val db = (application as PillGuardApp).database
            // Use raw query via Room
            val records = mutableListOf<com.pillguard.app.data.local.MedicationRecord>()
            val cursor = db.query("SELECT * FROM medication_records ORDER BY date DESC", null)
            cursor.use {
                while (it.moveToNext()) {
                    records.add(com.pillguard.app.data.local.MedicationRecord(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        date = it.getString(it.getColumnIndexOrThrow("date")),
                        timeSlot = it.getString(it.getColumnIndexOrThrow("timeSlot")),
                        completed = it.getInt(it.getColumnIndexOrThrow("completed")) == 1,
                        photoPath = it.getString(it.getColumnIndexOrThrow("photoPath")),
                        uploaded = it.getInt(it.getColumnIndexOrThrow("uploaded")) == 1,
                        takenAt = if (it.isNull(it.getColumnIndexOrThrow("takenAt"))) null else it.getLong(it.getColumnIndexOrThrow("takenAt")),
                        isDuplicate = it.getInt(it.getColumnIndexOrThrow("isDuplicate")) == 1,
                        createdAt = it.getLong(it.getColumnIndexOrThrow("createdAt"))
                    ))
                }
            }
            records
        }
    }

    private suspend fun com.pillguard.app.data.local.MedicationRecordDao.getUnuploadedRecordsSync(): List<com.pillguard.app.data.local.MedicationRecord> {
        return withContext(Dispatchers.IO) {
            val db = (application as PillGuardApp).database
            val records = mutableListOf<com.pillguard.app.data.local.MedicationRecord>()
            val cursor = db.query("SELECT * FROM medication_records WHERE completed = 1 AND uploaded = 0", null)
            cursor.use {
                while (it.moveToNext()) {
                    records.add(com.pillguard.app.data.local.MedicationRecord(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        date = it.getString(it.getColumnIndexOrThrow("date")),
                        timeSlot = it.getString(it.getColumnIndexOrThrow("timeSlot")),
                        completed = it.getInt(it.getColumnIndexOrThrow("completed")) == 1,
                        photoPath = it.getString(it.getColumnIndexOrThrow("photoPath")),
                        uploaded = it.getInt(it.getColumnIndexOrThrow("uploaded")) == 1,
                        takenAt = if (it.isNull(it.getColumnIndexOrThrow("takenAt"))) null else it.getLong(it.getColumnIndexOrThrow("takenAt")),
                        isDuplicate = it.getInt(it.getColumnIndexOrThrow("isDuplicate")) == 1,
                        createdAt = it.getLong(it.getColumnIndexOrThrow("createdAt"))
                    ))
                }
            }
            records
        }
    }
}
