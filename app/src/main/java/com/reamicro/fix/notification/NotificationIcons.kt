package com.reamicro.fix.notification

import android.app.Notification
import android.graphics.drawable.Icon
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.R

internal fun Notification.Builder.setReaMicroSmallIcon(): Notification.Builder =
    setSmallIcon(
        Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_notification_reamicrofix),
    )

        .setColor(Notification.COLOR_DEFAULT)
        .setColorized(false)
