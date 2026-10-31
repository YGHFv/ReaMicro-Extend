package com.reamicro.fix

import android.app.Application
import com.reamicro.fix.migration.LegacyAndroidWakeCleanup

class RootEnhancementApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.reamicro.fix.reload.HotReloadService.initialize(this)
        LegacyAndroidWakeCleanup.run(this)
    }
}
