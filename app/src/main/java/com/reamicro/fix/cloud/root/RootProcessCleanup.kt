package com.reamicro.fix.cloud.root

internal object RootProcessCleanup {
    const val LEGACY_SCRIPT = "/data/adb/service.d/reamicro-watchdog.sh"

    fun legacy(): String = stopScript(LEGACY_SCRIPT, deleteScript = true)

    fun oldSupervisor(): String = stopScript(
        "${RootTaskRepository.MODULE_DIRECTORY}/watchdog.sh",
        deleteScript = false,
    )

    private fun stopScript(path: String, deleteScript: Boolean): String = """
        [ "${'$'}(id -u)" = 0 ] || exit 1
        script=${RootModuleManager.quote(path)}
        ${if (deleteScript) "rm -f \"${'$'}script\" || exit 1" else ""}
        matching_pids() {
            for cmdline in /proc/[0-9]*/cmdline; do
                [ -r "${'$'}cmdline" ] || continue
                IFS= read -r comm <"${'$'}{cmdline%/cmdline}/comm" 2>/dev/null || continue
                case "${'$'}comm" in sh|ash|bash|busybox|timeout) ;; *) continue ;; esac
                if tr '\000' '\n' <"${'$'}cmdline" 2>/dev/null | grep -Fxq "${'$'}script"; then
                    pid=${'$'}{cmdline#/proc/}
                    pid=${'$'}{pid%/cmdline}
                    [ "${'$'}pid" = "${'$'}${'$'}" ] || echo "${'$'}pid"
                fi
            done
        }
        for signal in TERM KILL; do
            for pid in ${'$'}(matching_pids); do
                kill -"${'$'}signal" "${'$'}pid" 2>/dev/null || :
            done
            tries=0
            while [ -n "${'$'}(matching_pids)" ] && [ "${'$'}tries" -lt 10 ]; do
                sleep 0.1
                tries=${'$'}((tries + 1))
            done
            [ -n "${'$'}(matching_pids)" ] || exit 0
        done
        echo "ReaMicro: matching script process did not stop" >&2
        exit 1
    """.trimIndent()
}
