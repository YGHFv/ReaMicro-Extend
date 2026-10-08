package com.reamicro.fix.i18n

import android.content.Context
import androidx.annotation.StringRes
import com.reamicro.fix.BuildConfig

internal fun Context.moduleString(@StringRes id: Int, vararg args: Any): String {
    val moduleContext = if (packageName == BuildConfig.APPLICATION_ID) this
        else createPackageContext(BuildConfig.APPLICATION_ID, 0)
    return if (args.isEmpty()) moduleContext.getString(id) else moduleContext.getString(id, *args)
}
