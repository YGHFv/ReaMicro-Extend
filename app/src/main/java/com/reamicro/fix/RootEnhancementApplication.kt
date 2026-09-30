package com.reamicro.fix

import android.app.Application
import com.reamicro.fix.migration.LegacyAndroidWakeCleanup

class RootEnhancementApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LegacyAndroidWakeCleanup.run(this)
    }
}
