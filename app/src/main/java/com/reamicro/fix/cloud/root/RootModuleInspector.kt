package com.reamicro.fix.cloud.root

internal object RootModuleInspector {
    fun inspect(access: RootAccessReport, run: (String) -> RootCommandResult): RootModuleStatus {
        if (!access.granted) return RootModuleStatus(access = access, error = access.detail)
        return try {
            val result = run(script)
            if (result.exitCode != 0 || result.truncated) {
                RootModuleStatus(rootAvailable = true, access = access, inspectionComplete = false,
                    error = "模块读取失败 (exit=${result.exitCode})；Root UID 0 已验证")
            } else parse(result.output, access)
        } catch (error: RootAccessException) {
            RootModuleStatus(access = error.report, error = error.report.detail)
        } catch (error: Exception) {
            RootModuleStatus(rootAvailable = true, access = access, inspectionComplete = false,
                error = "模块检测未完成：${error.message.orEmpty().take(300)}")
        }
    }

    fun parse(output: String, access: RootAccessReport): RootModuleStatus {
        val fields = output.lineSequence().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        if (fields["probe"] != "complete") {
            return RootModuleStatus(rootAvailable = true, access = access, inspectionComplete = false,
                error = "模块检测回执不完整；请重试检测，不需要重复授予 Root")
        }
        return RootModuleStatus(
            rootAvailable = true, access = access,
            installed = fields["installed"] == "1",
            statePresent = fields["state"] == "1",
            ready = fields["ready"] == "1",
            installedVersion = fields["version"]?.toIntOrNull() ?: 0,
            stagedVersion = fields["staged"]?.toIntOrNull() ?: 0,
            stagedPresent = fields["stagedPresent"] == "1",
            stagedDisabled = fields["stagedDisabled"] == "1",
            stagedRemoval = fields["stagedRemoval"] == "1",
            stagedReady = fields["stagedReady"] == "1",
            pendingUpdate = fields["update"] == "1",
            disabled = fields["disabled"] == "1",
            pendingRemoval = fields["remove"] == "1",
            stopRequested = fields["stopped"] == "1",
            daemonRunning = fields["daemon"] == "1",
            taskRunning = fields["running"] == "1",
            heartbeatAt = (fields["heartbeat"]?.toLongOrNull() ?: 0L)
                .coerceIn(0L, Long.MAX_VALUE / 1000L) * 1000L,
            wakeMode = fields["wakeMode"]?.takeIf {
                it in setOf("realtime_alarm", "boottime_alarm", "unavailable")
            } ?: "unknown",
            wakeAt = (fields["wakeAt"]?.toLongOrNull() ?: 0L)
                .coerceIn(0L, Long.MAX_VALUE / 1000L) * 1000L,
            wakeHelperRunning = fields["wakeHelper"] == "1",
            legacyWakeInstalled = fields["legacy"] == "1",
        )
    }

    val script: String = """
        module='${RootTaskRepository.MODULE_DIRECTORY}'
        state='${RootTaskRepository.STATE_DIRECTORY}'
        staged='${RootModuleManager.UPDATE_DIRECTORY}'
        [ ! -L "${'$'}module" ] && [ ! -L "${'$'}staged" ] || exit 73
        [ -d "${'$'}staged" ] && echo stagedPresent=1
        [ -f "${'$'}staged/disable" ] && echo stagedDisabled=1
        [ -f "${'$'}staged/remove" ] && echo stagedRemoval=1
        [ -r /data/adb ] && [ -x /data/adb ] || exit 72
        [ -d "${'$'}module" ] && echo installed=1
        [ -f "${'$'}state/state.json" ] && echo state=1
        [ -f "${'$'}module/disable" ] && echo disabled=1
        [ -f "${'$'}module/remove" ] && echo remove=1
        [ -f "${'$'}module/update" ] && echo update=1
        [ -f "${'$'}state/stop" ] && echo stopped=1
        module_version() {
            version=${'$'}(sed -n 's/^REAMICRO_MODULE_VERSION=//p' "${'$'}1/common.sh" 2>/dev/null | head -n 1)
            [ -n "${'$'}version" ] || version=${'$'}(sed -n 's/^versionCode=//p' "${'$'}1/module.prop" 2>/dev/null | head -n 1)
            case "${'$'}version" in ''|*[!0-9]*) version=0 ;; esac
            printf '%s' "${'$'}version"
        }
        complete_module() {
            [ "${'$'}(sed -n 's/^id=//p' "${'$'}1/module.prop" 2>/dev/null)" = reamicro_automation ] || return 1
            for part in module.prop common.sh action.sh runner.sh service.sh watchdog.sh lifecycle.sh stop.sh uninstall.sh; do
                [ -s "${'$'}1/${'$'}part" ] || return 1
            done
            if [ "${'$'}(module_version "${'$'}1")" -ge 10 ]; then
                [ -f "${'$'}1/bin/wake-timer" ] && [ -s "${'$'}1/bin/wake-timer" ] &&
                    [ -x "${'$'}1/bin/wake-timer" ] && [ ! -L "${'$'}1/bin/wake-timer" ] || return 1
            fi
        }
        complete_module "${'$'}staged" && echo stagedReady=1
        complete_module "${'$'}module" && [ -x "${'$'}module/runner.sh" ] && [ -x "${'$'}module/action.sh" ] && echo ready=1
        version=${'$'}(module_version "${'$'}module")
        printf 'version=%s\n' "${'$'}version"
        printf 'staged='; sed -n 's/^versionCode=//p' "${'$'}staged/module.prop" 2>/dev/null | head -n 1; echo
        printf 'heartbeat='; cat "${'$'}state/heartbeat" 2>/dev/null; echo
        wake_mode=${'$'}(head -n 1 "${'$'}state/wake-mode" 2>/dev/null)
        case "${'$'}wake_mode" in realtime_alarm|boottime_alarm|unavailable) ;; *) wake_mode=unknown ;; esac
        wake_at=${'$'}(head -n 1 "${'$'}state/wake-at" 2>/dev/null)
        case "${'$'}wake_at" in ''|*[!0-9]*) wake_at=0 ;; esac
        printf 'wakeMode=%s\nwakeAt=%s\n' "${'$'}wake_mode" "${'$'}wake_at"
        matching_pid() {
            pid=${'$'}(cat "${'$'}1" 2>/dev/null)
            case "${'$'}pid" in ''|*[!0-9]*) return 1 ;; esac
            [ -r "/proc/${'$'}pid/cmdline" ] || return 1
            [ -r "/proc/${'$'}pid/status" ] || return 1
            grep -Eq '^State:[[:space:]]+[XZ]' "/proc/${'$'}pid/status" && return 1
            tr '\000' '\n' <"/proc/${'$'}pid/cmdline" 2>/dev/null | grep -Fxq "${'$'}2"
        }
        if matching_pid "${'$'}state/daemon.pid" "${'$'}module/watchdog.sh"; then
            echo daemon=1
            daemon_pid=${'$'}pid
            wake_pid=${'$'}(cat "${'$'}state/wake.pid" 2>/dev/null)
            case "${'$'}wake_pid" in ''|*[!0-9]*) wake_pid=0 ;; esac
            if [ "${'$'}wake_pid" -gt 0 ] &&
                [ -r "/proc/${'$'}wake_pid/cmdline" ] && [ -r "/proc/${'$'}wake_pid/status" ] &&
                ! grep -Eq '^State:[[:space:]]+[XZ]' "/proc/${'$'}wake_pid/status" &&
                [ "${'$'}(tr '\000' '\n' <"/proc/${'$'}wake_pid/cmdline" | head -n 1)" = "${'$'}module/bin/wake-timer" ] &&
                [ "${'$'}(tr '\000' '\n' <"/proc/${'$'}wake_pid/cmdline" | sed -n '3p')" = "${'$'}state" ] &&
                [ "${'$'}(tr '\000' '\n' <"/proc/${'$'}wake_pid/cmdline" | sed -n '4p')" = "${'$'}module" ] &&
                [ "${'$'}(sed -n 's/^PPid:[[:space:]]*//p' "/proc/${'$'}wake_pid/status")" = "${'$'}daemon_pid" ]; then
                echo wakeHelper=1
            fi
        fi

        if matching_pid "${'$'}state/runner.pid" "${'$'}module/runner.sh"; then
            echo running=1
        elif matching_pid "${'$'}state/runner.pid" 'com.reamicro.fix.cloud.root.RootTaskMain' &&
             matching_pid "${'$'}state/runner.pid" run; then
            echo running=1
        fi
        [ -f /data/adb/service.d/reamicro-watchdog.sh ] && echo legacy=1
        echo probe=complete
    """.trimIndent()
}
