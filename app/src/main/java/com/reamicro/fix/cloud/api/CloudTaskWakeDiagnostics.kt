package com.reamicro.fix.cloud.api

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.reamicro.fix.notification.CloudTaskNotifications

/**
 * 云端任务后台唤醒能力的自检。
 *
 * 服务端调度不依赖模块在线（签到、抽卡、云端阅读都在服务器跑），模块被唤醒只为
 * 收消息和发通知。所以这里检查的是"通知能否及时到达"，而不是"任务能否执行"。
 */
data class CloudTaskWakeStatus(
    val exactAlarmAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val notificationAllowed: Boolean,
) {
    /** 三项齐备时通知基本能按时到；缺任一项都只是延迟，不影响任务本身执行。 */
    val healthy: Boolean get() = exactAlarmAllowed && batteryUnrestricted && notificationAllowed

    fun summary(): String = when {
        healthy -> "后台唤醒正常，任务结果会及时通知"
        !notificationAllowed -> "未授予通知权限，任务结果只能在打开阅微时以提示条显示"
        !exactAlarmAllowed && !batteryUnrestricted -> "精确闹钟与电池优化均未放行，通知可能延迟数小时"
        !exactAlarmAllowed -> "未授予精确闹钟，通知可能延迟数小时"
        else -> "电池优化未放行，系统可能冻结模块导致通知延迟"
    }

    /** 逐项说明，用于设置页展开显示。 */
    fun details(): List<String> = listOf(
        "精确闹钟：${if (exactAlarmAllowed) "已允许" else "未允许（通知会被推迟）"}",
        "电池优化：${if (batteryUnrestricted) "已放行" else "未放行（进程可能被冻结）"}",
        "通知权限：${if (notificationAllowed) "已授予" else "未授予（只能显示提示条）"}",
    )
}

object CloudTaskWakeDiagnostics {
    fun inspect(context: Context): CloudTaskWakeStatus = CloudTaskWakeStatus(
        exactAlarmAllowed = CloudTaskWakeScheduler.canScheduleExact(context),
        batteryUnrestricted = isBatteryUnrestricted(context),
        notificationAllowed = CloudTaskNotifications.hasPermission(context),
    )

    private fun isBatteryUnrestricted(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(PowerManager::class.java) ?: return false
        // 判断的是模块包，不是阅微包：闹钟和通知都由模块进程承担。
        manager.isIgnoringBatteryOptimizations(CloudTaskNotifications.MODULE_PACKAGE_NAME)
    }.getOrDefault(false)

    /** 跳转系统的精确闹钟授权页。Android 12 以下无需授权，返回 false。 */
    fun exactAlarmSettingsIntent(): Intent? {
        if (Build.VERSION.SDK_INT < 31) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:${CloudTaskNotifications.MODULE_PACKAGE_NAME}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * 跳转电池优化白名单请求。
     * 直接用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 会弹系统确认框；
     * 部分 ROM 屏蔽该 action，失败时回退到应用详情页由用户手动设置。
     */
    fun batteryOptimizationIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${CloudTaskNotifications.MODULE_PACKAGE_NAME}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun moduleDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${CloudTaskNotifications.MODULE_PACKAGE_NAME}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, CloudTaskNotifications.MODULE_PACKAGE_NAME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            moduleDetailsIntent()
        }

    /** 依次尝试多个 Intent，返回第一个成功拉起的。部分 ROM 会屏蔽标准 action。 */
    fun launchFirstAvailable(context: Context, vararg intents: Intent?): Boolean {
        intents.filterNotNull().forEach { intent ->
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
