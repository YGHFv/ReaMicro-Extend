package com.reamicro.fix.cloud.root

import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

internal object RootModuleBundle {
    fun verify(file: File, expectedVersionCode: Int) {
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            check(names.size == names.toSet().size) { "Duplicate module ZIP entries" }
            check(names.none { it.startsWith("/") || ".." in it.split("/") }) { "Invalid module ZIP path" }
            val required = setOf("module.prop", "customize.sh", "common.sh", "service.sh",
                "lifecycle.sh", "stop.sh", "watchdog.sh", "runner.sh", "uninstall.sh", "action.sh",
                "bin/wake-timer", "META-INF/com/google/android/update-binary",
                "META-INF/com/google/android/updater-script")
            check(required.all { zip.getEntry(it)?.let { entry -> !entry.isDirectory && entry.size > 0 } == true }) {
                "Incomplete bundled Root module"
            }
            for (name in required - "bin/wake-timer") {
                val data = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                check(data.none { it == '\r'.code.toByte() }) { "Root module text must use LF: $name" }
            }
            val installerMarker = zip.getInputStream(zip.getEntry("META-INF/com/google/android/updater-script"))
                .bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
            check(installerMarker == "#MAGISK") { "Invalid Root module installer marker" }
            val header = zip.getInputStream(zip.getEntry("bin/wake-timer")).use { it.readBytes().take(20) }
            check(header.size == 20 &&
                header.take(6) == listOf(0x7f, 0x45, 0x4c, 0x46, 2, 1).map { it.toByte() } &&
                header[18] == 0xb7.toByte() && header[19] == 0.toByte()) {
                "Root wake helper must be a little-endian arm64 ELF executable"
            }
            val props = Properties().apply {
                zip.getInputStream(zip.getEntry("module.prop")).bufferedReader(Charsets.UTF_8).use { load(it) }
            }
            check(props.getProperty("id") == RootModuleManager.MODULE_ID) { "Unexpected Root module ID" }
            check(props.getProperty("versionCode") == expectedVersionCode.toString()) { "Bundled Root module version mismatch" }
            val runtimeVersions = zip.getInputStream(zip.getEntry("common.sh")).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.lineSequence().filter { it.startsWith("REAMICRO_MODULE_VERSION=") }
                    .map { it.substringAfter('=') }.toList()
            }
            check(runtimeVersions == listOf(expectedVersionCode.toString())) {
                "Bundled Root runtime version mismatch: expected=$expectedVersionCode, common.sh=$runtimeVersions"
            }
        }
    }
}
