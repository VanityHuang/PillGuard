package com.pillguard.app

import android.app.Application
import com.pillguard.app.data.local.AppDatabase

class PillGuardApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
}
