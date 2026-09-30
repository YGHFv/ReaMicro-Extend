package com.reamicro.fix.cloud.root

import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

/** Validate the packaged payload before invoking any installer. */
internal object RootModuleBundle {
    fun verify(file: File, expectedVersionCode: Int) {
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            check(names.size == names.toSet().size) { "Duplicate module ZIP entries" }
            check(names.none { it.startsWith("/") || ".." in it.split("/") }) { "Invalid module ZIP path" }
            val required = setOf("module.prop", "customize.sh", "common.sh", "service.sh",
                "lifecycle.sh", "stop.sh", "watchdog.sh", "runner.sh", "uninstall.sh", "action.sh")
            check(required.all { zip.getEntry(it)?.let { entry -> !entry.isDirectory && entry.size > 0 } == true }) {
                "Incomplete bundled Root module"
            }
            val props = Properties().apply {
                zip.getInputStream(zip.getEntry("module.prop")).bufferedReader(Charsets.UTF_8).use { load(it) }
            }
            check(props.getProperty("id") == RootModuleManager.MODULE_ID) { "Unexpected Root module ID" }
            check(props.getProperty("versionCode")?.toIntOrNull() == expectedVersionCode) { "Bundled Root module version mismatch" }
        }
    }
}
