package com.reamicro.fix.cloud.root

/** Commands are specific to the active root provider, never ksud's global uninstall command. */
internal data class RootModuleManager(val kind: Kind, val executable: String) {
    enum class Kind { KERNEL_SU, APATCH, MAGISK }

    fun install(zipPath: String): String = when (kind) {
        Kind.MAGISK -> "${quote(executable)} --install-module ${quote(zipPath)}"
        else -> "${quote(executable)} module install ${quote(zipPath)}"
    }

    fun reactivate(pendingRemoval: Boolean): String = when (kind) {
        Kind.MAGISK -> "rm -f '$MODULE_DIRECTORY/remove' '$MODULE_DIRECTORY/disable'"
        else -> buildString {
            if (pendingRemoval) append("${quote(executable)} module undo-uninstall $MODULE_ID && ")
            append("${quote(executable)} module enable $MODULE_ID")
        }
    }

    fun uninstall(): String = when (kind) {
        Kind.MAGISK -> "if [ -d '$MODULE_DIRECTORY' ]; then touch '$MODULE_DIRECTORY/remove'; fi"
        else -> "${quote(executable)} module uninstall $MODULE_ID"
    }

    fun disable(): String = when (kind) {
        Kind.MAGISK -> "test -d '$MODULE_DIRECTORY' && touch '$MODULE_DIRECTORY/disable'"
        else -> "${quote(executable)} module disable $MODULE_ID"
    }

    fun cancelStaging(): String =
        "test ! -L '$UPDATE_DIRECTORY' && rm -rf '$UPDATE_DIRECTORY' && test ! -e '$UPDATE_DIRECTORY'"

    companion object {
        const val MODULE_ID = "reamicro_automation"
        const val MODULE_DIRECTORY = "/data/adb/modules/$MODULE_ID"
        const val UPDATE_DIRECTORY = "/data/adb/modules_update/$MODULE_ID"

        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        fun parse(result: RootCommandResult): RootModuleManager? {
            if (result.exitCode != 0 || result.truncated) return null
            val fields = result.output.lineSequence().filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=').trim() }
            val kind = when (fields["manager"]) {
                "ksu" -> Kind.KERNEL_SU
                "apatch" -> Kind.APATCH
                "magisk" -> Kind.MAGISK
                else -> return null
            }
            val binary = fields["binary"]?.takeIf { it.startsWith("/") } ?: return null
            return RootModuleManager(kind, binary)
        }

        // su -v identifies the active provider. Only fall back if exactly one installer is present,
        // so leftovers from a previous root manager cannot silently select the wrong installer.
        val detectScript: String get() = detectionScript("su")
        fun detectionScript(su: String): String = """
            [ "${'$'}(id -u)" = 0 ] || exit 1
            ksu=/data/adb/ksud
            [ -x "${'$'}ksu" ] || ksu=/data/adb/ksu/bin/ksud
            [ -x "${'$'}ksu" ] || ksu=${'$'}(command -v ksud 2>/dev/null)
            apatch=/data/adb/apd
            [ -x "${'$'}apatch" ] || apatch=${'$'}(command -v apd 2>/dev/null)
            magisk=${'$'}(command -v magisk 2>/dev/null)
            if [ -z "${'$'}magisk" ]; then
                for candidate in /data/adb/magisk/magisk /sbin/magisk /debug_ramdisk/magisk; do
                    if [ -x "${'$'}candidate" ]; then magisk=${'$'}candidate; break; fi
                done
            fi
            version=${'$'}(${quote(su)} -v 2>/dev/null)
            manager=
            binary=
            case "${'$'}version" in
                *KernelSU*|*kernelsu*|*KSU*) manager=ksu; binary=${'$'}ksu ;;
                *APatch*|*APATCH*|*apatch*) manager=apatch; binary=${'$'}apatch ;;
                *MAGISK*|*Magisk*|*magisk*) manager=magisk; binary=${'$'}magisk ;;
            esac
            if [ -z "${'$'}manager" ]; then
                count=0
                if [ -n "${'$'}ksu" ] && [ -x "${'$'}ksu" ]; then manager=ksu; binary=${'$'}ksu; count=${'$'}((count + 1)); fi
                if [ -n "${'$'}apatch" ] && [ -x "${'$'}apatch" ]; then manager=apatch; binary=${'$'}apatch; count=${'$'}((count + 1)); fi
                if [ -n "${'$'}magisk" ] && [ -x "${'$'}magisk" ]; then manager=magisk; binary=${'$'}magisk; count=${'$'}((count + 1)); fi
                [ "${'$'}count" = 1 ] || exit 3
            fi
            [ -n "${'$'}binary" ] && [ -x "${'$'}binary" ] || exit 3
            printf 'manager=%s\nbinary=%s\n' "${'$'}manager" "${'$'}binary"
        """.trimIndent()
    }
}
