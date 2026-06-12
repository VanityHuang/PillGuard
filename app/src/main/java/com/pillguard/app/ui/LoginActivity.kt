package com.pillguard.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pillguard.app.PillGuardApp
import com.pillguard.app.databinding.ActivityLoginBinding
import com.pillguard.app.network.ApiClient
import com.pillguard.app.network.LoginRequest
import com.pillguard.app.security.SecurityManager
import com.pillguard.app.util.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 自动填入上次登录信息
        fillLastLoginInfo()

        // 如果已登录，尝试自动登录
        if (SecurityManager.isLoggedIn(this)) {
            tryAutoLogin()
            return
        }

        setupListeners()
    }

    private fun fillLastLoginInfo() {
        val prefs = getSharedPreferences("pillguard_login", MODE_PRIVATE)
        val lastServer = prefs.getString("server_url", "") ?: ""
        val lastUsername = prefs.getString("username", "") ?: ""
        val lastPassword = prefs.getString("password", "") ?: ""

        if (lastServer.isNotEmpty()) {
            binding.etServerUrl.setText(lastServer)
        }
        if (lastUsername.isNotEmpty()) {
            binding.etUsername.setText(lastUsername)
        }
        if (lastPassword.isNotEmpty()) {
            binding.etPassword.setText(lastPassword)
        }
    }

    private fun tryAutoLogin() {
        val prefs = getSharedPreferences("pillguard_login", MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", null)
        val username = prefs.getString("username", null)
        val password = prefs.getString("password", null)
        val isOffline = prefs.getBoolean("is_offline", false)

        // 离线模式直接进入
        if (isOffline || serverUrl == null || username == null || password == null) {
            if (SecurityManager.isLoggedIn(this)) {
                // 持久化已保存的服务器地址，确保 UploadWorker 进程重启后可用
                serverUrl?.let { url ->
                    val baseUrl = if (url.endsWith("/api/")) url else "${url}/api/"
                    ApiClient.init(baseUrl)
                    ApiClient.saveBaseUrl(this@LoginActivity, baseUrl)
                }
                navigateToMain()
                return
            }
            setupListeners()
            return
        }

        // 尝试在线自动登录
        showLoading(true)
        lifecycleScope.launch {
            try {
                val baseUrl = if (serverUrl.endsWith("/api/")) serverUrl else "${serverUrl}/api/"
                ApiClient.init(baseUrl)

                val response = withContext(Dispatchers.IO) {
                    ApiClient.apiService.login(LoginRequest(username, password))
                }

                if (response.isSuccessful) {
                    val loginResponse = response.body()!!
                    SecurityManager.saveCredentials(this@LoginActivity, loginResponse.userId, loginResponse.token)
                    AuthManager.saveSession(this@LoginActivity, username, false)
                    ApiClient.setAuthToken(loginResponse.token)

                    // 持久化 baseUrl，防止进程重启后 UploadWorker 丢失服务器地址
                    ApiClient.saveBaseUrl(this@LoginActivity, baseUrl)

                    // 同步服务器记录到本地
                    syncRecordsFromServer()

                    navigateToMain()
                } else {
                    // 登录失败（token过期等），显示登录页
                    showLoading(false)
                    SecurityManager.clearCredentials(this@LoginActivity)
                    setupListeners()
                    val errorMsg = when (response.code()) {
                        401 -> "登录已过期，请重新登录"
                        404 -> "服务器地址无效，请重新输入"
                        else -> "自动登录失败 (${response.code()})，请重新登录"
                    }
                    Toast.makeText(this@LoginActivity, errorMsg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // 网络问题，显示登录页（已自动填入信息）
                showLoading(false)
                SecurityManager.clearCredentials(this@LoginActivity)
                setupListeners()
                Toast.makeText(this@LoginActivity, "网络连接失败，请重新登录或使用离线模式", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener { handleOnlineLogin() }
        binding.btnOfflineLogin.setOnClickListener {
            SecurityManager.saveCredentials(this, "offline_user", "offline_token")
            AuthManager.saveSession(this, "offline_user", true)
            // 保存离线模式标记
            getSharedPreferences("pillguard_login", MODE_PRIVATE).edit()
                .putBoolean("is_offline", true)
                .apply()
            navigateToMain()
        }

        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleOnlineLogin()
                true
            } else false
        }
    }

    private fun handleOnlineLogin() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val serverUrl = binding.etServerUrl.text.toString().trim()

        if (username.isEmpty()) {
            binding.etUsername.error = "请输入用户名"
            return
        }
        if (password.isEmpty()) {
            binding.etPassword.error = "请输入密码"
            return
        }

        // 开发者模式：用户名和密码都是 dev
        if (username == "dev" && password == "dev") {
            startActivity(Intent(this, DevModeActivity::class.java))
            return
        }

        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading(true)

        lifecycleScope.launch {
            try {
                val baseUrl = if (serverUrl.endsWith("/api/")) serverUrl else "${serverUrl}/api/"
                ApiClient.init(baseUrl)

                val response = withContext(Dispatchers.IO) {
                    ApiClient.apiService.login(LoginRequest(username, password))
                }

                if (response.isSuccessful) {
                    val loginResponse = response.body()!!
                    SecurityManager.saveCredentials(this@LoginActivity, loginResponse.userId, loginResponse.token)
                    AuthManager.saveSession(this@LoginActivity, username, false)
                    ApiClient.setAuthToken(loginResponse.token)

                    // 持久化 baseUrl，防止进程重启后 UploadWorker 丢失服务器地址
                    ApiClient.saveBaseUrl(this@LoginActivity, baseUrl)

                    // 保存登录信息用于下次自动登录
                    getSharedPreferences("pillguard_login", MODE_PRIVATE).edit()
                        .putString("server_url", serverUrl)
                        .putString("username", username)
                        .putString("password", password)
                        .putBoolean("is_offline", false)
                        .apply()

                    // 同步服务器记录到本地
                    syncRecordsFromServer()

                    navigateToMain()
                } else {
                    showLoading(false)
                    val errorMsg = when (response.code()) {
                        400 -> "请求格式错误 (400)"
                        401 -> "用户名或密码错误 (401)"
                        403 -> "访问被拒绝 (403)"
                        404 -> "服务器地址无效，未找到接口 (404)"
                        500 -> "服务器内部错误 (500)"
                        else -> "登录失败: ${response.code()}"
                    }
                    Toast.makeText(this@LoginActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: java.net.ConnectException) {
                showLoading(false)
                Toast.makeText(this@LoginActivity, "无法连接服务器，请检查地址是否正确", Toast.LENGTH_LONG).show()
            } catch (e: java.net.SocketTimeoutException) {
                showLoading(false)
                Toast.makeText(this@LoginActivity, "连接超时，请检查网络或服务器状态", Toast.LENGTH_LONG).show()
            } catch (e: java.net.UnknownHostException) {
                showLoading(false)
                Toast.makeText(this@LoginActivity, "无法解析服务器地址，请检查输入", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                showLoading(false)
                val detail = e.message ?: e.javaClass.simpleName
                Toast.makeText(this@LoginActivity, "连接失败: $detail", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseTakenAt(takenAt: String?): Long? {
        if (takenAt == null) return null
        return try {
            // 服务器返回ISO格式如 "2024-01-15T08:30:00.123456"
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
            sdf.parse(takenAt.substring(0, 19))?.time
        } catch (e: Exception) {
            takenAt.toLongOrNull()
        }
    }

    private suspend fun syncRecordsFromServer() {
        try {
            val credentials = SecurityManager.getCredentials(this) ?: return
            val userId = credentials.first

            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val calendar = java.util.Calendar.getInstance()
            val endDate = dateFormat.format(calendar.time)
            calendar.add(java.util.Calendar.DAY_OF_YEAR, -30)
            val startDate = dateFormat.format(calendar.time)

            val response = withContext(Dispatchers.IO) {
                ApiClient.apiService.getRecords(userId, startDate, endDate)
            }

            if (response.isSuccessful) {
                val serverRecords = response.body() ?: return
                val db = (application as PillGuardApp).database

                withContext(Dispatchers.IO) {
                    for (dto in serverRecords) {
                        // 按date+timeSlot+isDuplicate精确查找，避免重复记录覆盖正常记录
                        val existing = db.medicationRecordDao().getRecordByDuplicate(dto.date, dto.timeSlot, dto.isDuplicate)
                        if (existing != null) {
                            db.medicationRecordDao().update(
                                existing.copy(
                                    completed = dto.completed,
                                    uploaded = true,
                                    takenAt = parseTakenAt(dto.takenAt)
                                )
                            )
                        } else {
                            db.medicationRecordDao().insert(
                                com.pillguard.app.data.local.MedicationRecord(
                                    date = dto.date,
                                    timeSlot = dto.timeSlot,
                                    completed = dto.completed,
                                    uploaded = true,
                                    takenAt = parseTakenAt(dto.takenAt),
                                    isDuplicate = dto.isDuplicate
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 同步失败不影响登录
            android.util.Log.w("LoginActivity", "同步记录失败", e)
        }
    }

    private fun showLoading(show: Boolean) {
        binding.btnLogin.isEnabled = !show
        binding.btnOfflineLogin.isEnabled = !show
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
