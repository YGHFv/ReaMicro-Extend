package com.reamicro.fix.cloud.root

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class RootModuleBundleTest {
    private fun verify(id: String = RootModuleManager.MODULE_ID, version: Int = 3, missing: String = "") {
        val file = Files.createTempFile("reamicro-module-test", ".zip").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                val files = listOf("module.prop", "customize.sh", "common.sh", "service.sh",
                    "lifecycle.sh", "stop.sh", "watchdog.sh", "runner.sh", "uninstall.sh", "action.sh")
                for (name in files.filterNot { it == missing }) {
                    zip.putNextEntry(ZipEntry(name))
                    val text = if (name == "module.prop") "id=$id\nversionCode=$version\n" else "#!/system/bin/sh\n"
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
            RootModuleBundle.verify(file, 3)
        } finally {
            file.delete()
        }
    }

    @Test fun `matching complete bundle is accepted`() = verify()

    @Test fun `a different module must never be installed`() {
        assertTrue(runCatching { verify(id = "other_module") }.isFailure)
    }

    @Test fun `stale bundled version is rejected`() {
        assertTrue(runCatching { verify(version = 2) }.isFailure)
    }

    @Test fun `missing scripts are rejected before installation`() {
        assertTrue(runCatching { verify(missing = "uninstall.sh") }.isFailure)
    }
}
