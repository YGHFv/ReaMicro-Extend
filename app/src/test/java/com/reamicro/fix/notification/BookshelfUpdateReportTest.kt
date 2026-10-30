package com.reamicro.fix.notification

import com.reamicro.fix.online.download.OnlineChapterUpdateOutcome
import org.junit.Assert.*
import org.junit.Test

class BookshelfUpdateReportTest {
    @Test fun reportListsActualAddedCountsAndFailures() {
        val report = BookshelfUpdateReport(listOf(
            OnlineChapterUpdateOutcome("甲书", added = 3),
            OnlineChapterUpdateOutcome("乙书", added = 2, failed = 1),
            OnlineChapterUpdateOutcome("丙书", error = "源不可用"),
            OnlineChapterUpdateOutcome("丁书"),
        ), 4, false)
        assertEquals("书架批量更新完成", report.title)
        assertTrue(report.summary.contains("2 本新增 5 章"))
        assertTrue(report.summary.contains("2 本未完成"))
        assertTrue(report.details.contains("甲书：新增 3 章"))
        assertTrue(report.details.contains("乙书：新增 2 章，1 章失败"))
        assertTrue(report.details.contains("丙书：新增 0 章，未完成（源不可用）"))
        assertTrue(report.details.contains("丁书：新增 0 章，已是最新"))
    }

    @Test fun skipAndInterruptedBatchesAreNotReportedAsUpdated() {
        val report = BookshelfUpdateReport(listOf(
            OnlineChapterUpdateOutcome("完结书", skipped = true),
            OnlineChapterUpdateOutcome("连载书", added = 1, retried = 2),
        ), 3, true)
        assertEquals("书架更新已停止", report.title)
        assertTrue(report.details.contains("完结书：新增 0 章，已完结，跳过"))
        assertTrue(report.details.contains("连载书：新增 1 章，补回 2 章"))
        assertTrue(report.details.contains("剩余 1 本未检查"))
    }

    @Test fun emptyAndErrorReportsDoNotDisappear() {
        assertTrue(BookshelfUpdateReport(emptyList(), 0, true).details.contains("没有可更新"))
        assertEquals("书架自动更新完成", BookshelfUpdateReport(emptyList(), 0, true).title)
        assertEquals("书架更新未完成", BookshelfUpdateReport(emptyList(), 0, false, "服务未就绪").title)
    }
}
