package com.reamicro.fix.cloud.root

/** 已由用户提供的电池 APK 核对；恢复交给厂商组件，不改永久省电设置。 */
internal enum class RootVendorNetworkPolicy(val id: String) {
    NONE("none"),
    XIAOMI("miui_sleep"),
    OPLUS("oplus_deep_sleep");

    fun shouldWake(state: RootNetworkState): Boolean = when (this) {
        NONE -> false
        XIAOMI -> state.probeError.isEmpty() && state.screen == false &&
            state.airplaneMode == false && (state.offline || state.xiaomiSleep == true)
        OPLUS -> state.probeError.isEmpty() && state.screen == false && state.airplaneMode == false &&
            (state.oplusDeepSleep == true || state.oplusNetworkDisabled == true)
    }

    fun wakeCommand(): List<String>? = when (this) {
        NONE -> null
        // Phone/PadSleepModeController: reason extra=1 -> MSG 10 -> tryToQuitSleep(32).
        // REQUEST_WAKE 是发送方权限；--receiver-permission 无法授予此权限。
        XIAOMI -> listOf(
            "/system/bin/am", "broadcast", "--user", "0", "--include-stopped-packages",
            "-f", "0x01000000", "-a", "com.miui.powerkeeper_request_wake",
            "-p", "com.miui.powerkeeper", "--ei", "reason", "1",
        )
        // RestoreNetworkReceiver -> onRestoreNetworkReq(null) -> MSG 6。
        // 非空 pkg 可能被维护策略挡住；不传 pkg，让组件临时恢复并自行返回深睡。
        OPLUS -> listOf(
            "/system/bin/am", "broadcast", "--user", "0", "-f", "0x01000000",
            "-a", "oplus.intent.action.DEEP_SLEEP_RESTORE_NETWORK",
            "-n", "com.oplus.battery/com.oplus.deepsleep.RestoreNetworkReceiver",
        )
    }

    val probeScript: String
        get() = when (this) {
            OPLUS -> """
                printf 'oplusDeepSleep='; /system/bin/settings --user 0 get system oplus_deep_sleep_status
                printf 'oplusNetworkDisabled='; /system/bin/settings --user 0 get system deepsleep_network_disable_state
                printf 'oplusNetworkType='; /system/bin/settings --user 0 get system oplus_deepsleep_network_type
            """.trimIndent()
            // 地震预警分支可保留无线电并改网络白名单；睡眠状态来自厂商私有 provider，
            // 不是 Android settings 键。只输出 userConfigureStatus，不记录完整 Bundle。
            XIAOMI -> """
                printf 'xiaomiSleep='
                /system/bin/content call --user 0 --uri content://com.miui.powerkeeper.configure/GlobalFeatureTable --method GlobalFeatureTablequery --extra userId:i:0 2>/dev/null |
                    /system/bin/sed -n 's/.*userConfigureStatus=\([A-Za-z_][A-Za-z_]*\)[,}].*/\1/p' | /system/bin/head -n 1
                printf '\n'
            """.trimIndent()
            NONE -> ""
        }

    companion object {
        fun forDevice(manufacturer: String, brand: String = ""): RootVendorNetworkPolicy = when {
            listOf("Xiaomi", "Redmi", "POCO").any {
                manufacturer.equals(it, ignoreCase = true) || brand.equals(it, ignoreCase = true)
            } -> XIAOMI
            listOf("OPPO", "OnePlus", "realme", "OPLUS").any {
                manufacturer.equals(it, ignoreCase = true) || brand.equals(it, ignoreCase = true)
            } -> OPLUS
            else -> NONE
        }
    }
}
