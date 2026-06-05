package com.pillguard.app.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

data class LoginRequest(val username: String, val password: String)
data class LoginResponse(val token: String, val userId: String)
data class UploadResponse(val success: Boolean, val message: String, val fileId: String? = null)
data class ApiResponse(val success: Boolean, val message: String)

interface ApiService {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @Multipart
    @POST("upload/photo")
    suspend fun uploadPhoto(
        @Part photo: MultipartBody.Part,
        @Part("userId") userId: RequestBody,
        @Part("date") date: RequestBody,
        @Part("timeSlot") timeSlot: RequestBody,
        @Part("isDuplicate") isDuplicate: RequestBody
    ): Response<UploadResponse>

    @GET("records")
    suspend fun getRecords(
        @Query("userId") userId: String,
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String
    ): Response<List<MedicationRecordDto>>

    @POST("auth/refresh")
    suspend fun refreshToken(@Header("Authorization") token: String): Response<LoginResponse>
}

data class MedicationRecordDto(
    val id: String,
    val date: String,
    val timeSlot: String,
    val completed: Boolean,
    val photoUrl: String? = null,
    val takenAt: String? = null,
    val isDuplicate: Boolean = false
)
