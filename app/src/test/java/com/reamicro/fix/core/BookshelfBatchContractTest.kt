package com.reamicro.fix.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BookshelfBatchContractTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(path: String) = main.resolve("java/com/reamicro/fix/$path").readText(Charsets.UTF_8)

    @Test fun batchUsesSilentUpdatesAndOneRetainedReport() {
        val batch = source("hook/BookshelfCompletionHook.kt").substringAfter("private fun startBatch(")
            .substringBefore("private fun number(")
        assertFalse(batch.contains("toast("))
        assertTrue(batch.contains("silent = true"))
        assertTrue(batch.contains("skipCompleted = settings.shelfSkipCompleted"))
        assertEquals(1, Regex("BookshelfUpdateNotifications.send").findAll(batch).count())
        val receiver = source("notification/BookshelfUpdateNotifications.kt")
        assertTrue(receiver.contains("Notification.BigTextStyle()"))
        assertFalse(receiver.contains("cancelOnlineCompletionNotificationIfDone"))
    }

    @Test fun scopeMatchesNativeGroupAndCompletionIsNotReadFinishedTimestamp() {
        val batch = source("hook/BookshelfCompletionHook.kt")
        assertTrue(batch.contains("settings.shelfCurrentOnly"))
        assertTrue(batch.contains("\"bookshelfGroups\""))
        assertTrue(batch.contains("group == currentGroup"))
        assertTrue(batch.contains("bookshelfPager.get()"))
        val update = source("hook/WebDavDriveHook.Support.kt")
        val start = update.substringAfter("internal fun WebDavDriveHook.updateOnlineCompletionBookIncrementally(")
            .substringBefore("val remoteChapters")
        assertTrue(start.contains("skipCompleted && target.result.status == \"完结\""))
        assertFalse(start.contains("getFinished"))
        assertTrue(start.indexOf("skipped = true") < start.indexOf("syncOnlineCompletionDefaultStyle"))
    }

    @Test fun threeNestedOptionsShareBatchVisibilityGate() {
        val ui = source("hook/ReaMicroSettingsHook.SettingsUi.kt")
            .substringAfter("internal fun ReaMicroSettingsHook.renderBookshelfCompletionSettingsContent(")
            .substringBefore("internal fun ReaMicroSettingsHook.renderAssociationCompletionSettingsContent(")
        assertTrue(ui.contains("title = \"更新当前书架\""))
        assertTrue(ui.contains("title = \"跳过完结图书\""))
        assertEquals(3, Regex("visibleProvider = \\{ settings.snapshot\\(\\).shelfBatchUpdate \\}").findAll(ui).count())
    }

    @Test fun groupScopeUsesShelfPagerOrCurrentReadingBookNotHomeTabs() {
        val hook = source("hook/BookshelfCompletionHook.kt")
        assertTrue(hook.contains("param.args[2] as? List<*>"))
        assertFalse(hook.contains("param.args[0] as? List<*>"))
        assertTrue(hook.contains("\"getCurrentPage\""))
        assertTrue(hook.contains("if (showingShelf == false)"))
        assertTrue(hook.contains("firstValue(requireNotNull(readingFlow))"))
        assertTrue(hook.contains("callMethod(readingBook, \"getGroup\")"))
    }
}
