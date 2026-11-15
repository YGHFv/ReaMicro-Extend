package com.reamicro.fix.cloud.root

internal object RootProcessCleanup {
    const val LEGACY_SCRIPT = "/data/adb/service.d/reamicro-watchdog.sh"

    fun legacy(): String = stopScript(LEGACY_SCRIPT, deleteScript = true)

    fun oldSupervisor(): String = stopScript(
        "${RootTaskRepository.MODULE_DIRECTORY}/watchdog.sh",
        deleteScript = false,
        includeRuntime = true,
    )

    fun removeState(): String = stopScript(
        "${RootTaskRepository.MODULE_DIRECTORY}/watchdog.sh",
        deleteScript = false,
        includeRuntime = true,
        clearState = true,
    )

    private fun stopScript(
        path: String,
        deleteScript: Boolean,
        includeRuntime: Boolean = false,
        clearState: Boolean = false,
    ): String = """
        [ "${'$'}(id -u)" = 0 ] || exit 1
        script=${RootModuleManager.quote(path)}
        state='${RootTaskRepository.STATE_DIRECTORY}'
        module='${RootTaskRepository.MODULE_DIRECTORY}'
        [ ! -L "${'$'}state" ] || exit 73
        ${if (deleteScript) "rm -f \"${'$'}script\" || exit 1" else ""}
        ${if (includeRuntime) """if [ -d "${'$'}state" ]; then umask 077; touch "${'$'}state/stop" || exit 1; fi""" else ""}
        matching_pid() {
            case "${'$'}1" in ''|*[!0-9]*|0|1) return 1 ;; esac
            [ "${'$'}1" != "${'$'}${'$'}" ] || return 1
            [ -r "/proc/${'$'}1/cmdline" ] && [ -r "/proc/${'$'}1/status" ] || return 1
            IFS= read -r comm <"/proc/${'$'}1/comm" 2>/dev/null || return 1
            case "${'$'}comm" in sh|ash|bash|busybox|timeout|app_process|app_process32|app_process64|wake-timer) ;; *) return 1 ;; esac
            grep -Eq '^State:[[:space:]]+[XZ]' "/proc/${'$'}1/status" && return 1
            args=${'$'}(tr '\000' '\n' <"/proc/${'$'}1/cmdline" 2>/dev/null)
            printf '%s\n' "${'$'}args" | grep -Fxq "${'$'}script" && return 0
            ${if (includeRuntime) """
            printf '%s\n' "${'$'}args" | grep -Fxq "${'$'}module/runner.sh" && return 0
            printf '%s\n' "${'$'}args" | grep -Fxq 'com.reamicro.fix.cloud.root.RootTaskMain' && return 0
            if printf '%s\n' "${'$'}args" | grep -Fxq "${'$'}module/bin/wake-timer"; then
                printf '%s\n' "${'$'}args" | grep -Fxq "${'$'}state" && return 0
            fi
            """ else ""}
            return 1
        }
        matching_pids() {
            for cmdline in /proc/[0-9]*/cmdline; do
                pid=${'$'}{cmdline#/proc/}
                pid=${'$'}{pid%/cmdline}
                matching_pid "${'$'}pid" && echo "${'$'}pid"
            done
        }
        survivors() {
            for pid in ${'$'}pids; do
                matching_pid "${'$'}pid" && echo "${'$'}pid"
            done
        }
        pids=${'$'}(matching_pids)
        for signal in TERM KILL; do
            [ -n "${'$'}pids" ] || break
            for pid in ${'$'}pids; do
                matching_pid "${'$'}pid" && kill -"${'$'}signal" "${'$'}pid" 2>/dev/null
            done
            tries=0
            while [ -n "${'$'}pids" ] && [ "${'$'}tries" -lt 10 ]; do
                pids=${'$'}(survivors)
                [ -n "${'$'}pids" ] || break
                sleep 0.1
                tries=${'$'}((tries + 1))
            done
            pids=${'$'}(matching_pids)
            [ -n "${'$'}pids" ] || break
        done
        if [ -n "${'$'}pids" ]; then
            echo "ReaMicro: matching runtime process did not stop" >&2
            exit 1
        fi
        ${if (includeRuntime) """
        { printf 'reamicro_tasks\n' >/sys/power/wake_unlock; } 2>/dev/null || :
        rm -f "${'$'}state/daemon.pid" "${'$'}state/heartbeat" "${'$'}state/runner.pid" \
            "${'$'}state/wake.pid" "${'$'}state/wake-at" "${'$'}state/wake-mode" "${'$'}state/wake-error"
        """ else ""}
        ${if (clearState) """rm -rf "${'$'}state" && [ ! -e "${'$'}state" ] || exit 1""" else ""}
        exit 0
    """.trimIndent()
}
