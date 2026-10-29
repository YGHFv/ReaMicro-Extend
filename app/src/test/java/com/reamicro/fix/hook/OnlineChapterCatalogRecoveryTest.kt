package com.reamicro.fix.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 防止已落盘章节因后续刷新失败而永久停留在旧目录表。 */
class OnlineChapterCatalogRecoveryTest {
    @Test
    fun `no new chapters still refreshes host catalog before reporting success`() {
        val file = listOf(
            File("src/main/java/com/reamicro/fix/hook/WebDavDriveHook.Support.kt"),
            File("app/src/main/java/com/reamicro/fix/hook/WebDavDriveHook.Support.kt"),
        ).first { it.isFile }
        val source = file.readText(Charsets.UTF_8)
        val branch = source.substringAfter("if (newRemoteIndices.isEmpty()) {")
            .substringBefore("return OnlineChapterUpdateResult(0,")
        val refresh = branch.indexOf("refreshOnlineCompletionCatalogTables(book, bookDir)")
        assertTrue(refresh >= 0)
        assertTrue(refresh < branch.indexOf("updateLocalBook("))
        assertFalse(branch.contains("if (structureChanged) refreshOnlineCompletionCatalogTables"))
        assertTrue(source.contains("阅微目录条目解析为空，已阻止刷新目录表"))
        assertTrue(source.contains("阅微章节目录解析为空，已阻止刷新目录表"))
    }
}
