package com.pillguard.app.network

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val PREFS_NAME = "pillguard_api_client"
    private const val KEY_BASE_URL = "base_url"

    private var baseUrl: String = "https://pillguard.example.com/api/"
    private var authToken: String? = null

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val authInterceptor = okhttp3.Interceptor { chain ->
        val requestBuilder = chain.request().newBuilder()
        authToken?.let {
            requestBuilder.addHeader("Authorization", "Bearer $it")
        }
        chain.proceed(requestBuilder.build())
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd HH:mm:ss")
        .create()

    private var _retrofit: Retrofit? = null
    private var _apiService: ApiService? = null

    private fun buildRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    val apiService: ApiService
        get() {
            if (_retrofit == null) {
                _retrofit = buildRetrofit()
                _apiService = _retrofit!!.create(ApiService::class.java)
            }
            return _apiService!!
        }

    fun init(baseUrl: String, authToken: String? = null) {
        this.baseUrl = baseUrl
        this.authToken = authToken
        // 重建Retrofit实例以使用新的baseUrl
        _retrofit = buildRetrofit()
        _apiService = _retrofit!!.create(ApiService::class.java)
    }

    fun setAuthToken(token: String) {
        this.authToken = token
    }

    /**
     * 持久化 baseUrl 到 SharedPreferences，防止进程重启后丢失。
     * 应在登录成功后调用（LoginActivity 中已在 init 后调用）。
     */
    fun saveBaseUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, url)
            .apply()
    }

    /**
     * 从 SharedPreferences 恢复 baseUrl。
     * 用于 UploadWorker 在进程重启后恢复正确的服务器地址。
     * @return true 如果成功恢复了之前保存的 URL
     */
    fun restoreBaseUrl(context: Context): Boolean {
        val savedUrl = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, null)
        if (savedUrl != null) {
            this.baseUrl = savedUrl
            _retrofit = buildRetrofit()
            _apiService = _retrofit!!.create(ApiService::class.java)
            return true
        }
        return false
    }

    /**
     * 获取当前 baseUrl（用于调试）
     */
    fun getBaseUrl(): String = baseUrl
}
