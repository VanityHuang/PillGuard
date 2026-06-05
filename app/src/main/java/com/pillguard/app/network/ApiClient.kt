package com.pillguard.app.network

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

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
}
