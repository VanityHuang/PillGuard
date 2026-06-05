# PillGuard ProGuard Rules

# 保留所有注解和签名
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 保留整个APP包（避免Gson/Retrofit/Room等反射问题）
-keep class com.pillguard.app.** { *; }
-keepclassmembers class com.pillguard.app.** { *; }
-keep enum com.pillguard.app.** { *; }
-keep interface com.pillguard.app.** { *; }

# ====== Retrofit ======
-keep class retrofit2.** { *; }
-keepclassmembers class retrofit2.** { *; }
-dontwarn retrofit2.**
-keepclassmembers,allowobfuscation class * {
    @retrofit2.http.* <methods>;
}

# ====== OkHttp ======
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class okio.** { *; }
-dontwarn okio.**

# ====== Gson ======
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# ====== Kotlin Coroutines ======
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
