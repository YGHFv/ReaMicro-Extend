package com.reamicro.fix.cloud.local
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class LocalTaskManifestTest {
    @Test fun `Root callback remains without Android wake jobs`() {
        val path = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }
        val text = path.readText()
        assertTrue(text.contains(".cloud.root.RootTaskSyncReceiver"))
        assertTrue(text.contains("com.reamicro.fix.ROOT_TASK_SYNC"))
        assertFalse(text.contains("LocalTaskJobService"))
        assertFalse(text.contains("SCHEDULE_EXACT_ALARM"))
    }
}
