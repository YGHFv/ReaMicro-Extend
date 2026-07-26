package com.reamicro.fix.webdav

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineChapterContentValidatorTest {
    @Test
    fun exactBatchApiErrorIsRejected() {
        val reason = OnlineChapterContentValidator.downloadedFailureReason(
            """{"code":1,"msg":"批量请求重试3次后仍失败:批量API返回空响应","data":null}""",
        )

        assertTrue(reason.orEmpty().contains("批量请求重试3次后仍失败"))
    }

    @Test
    fun jsonWithRealContentIsAcceptedEvenWhenCodeIsNonZero() {
        assertNull(
            OnlineChapterContentValidator.downloadedFailureReason(
                """{"code":1,"msg":"降级响应","data":{"content":"这是正常章节正文。"}}""",
            ),
        )
    }

    @Test
    fun jsonTextFieldIsAccepted() {
        assertNull(
            OnlineChapterContentValidator.downloadedFailureReason(
                """{"chapter":{"text":"另一段正常正文。"}}""",
            ),
        )
    }

    @Test
    fun errorObjectTextIsNotAcceptedAsChapterContent() {
        assertTrue(
            OnlineChapterContentValidator.downloadedFailureReason(
                """{"code":1,"error":{"text":"请求频率过高"},"data":null}""",
            ).orEmpty().contains("没有章节正文"),
        )
    }

    @Test
    fun xmlEscapedErrorJsonInStoredXhtmlIsRejected() {
        val reason = OnlineChapterContentValidator.storedXhtmlFailureReason(
            """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>第十章</title></head>
                <body><h1>第十章</h1><p>{&amp;#34;code&amp;#34;:1,&amp;#34;msg&amp;#34;:&amp;#34;批量API返回空响应&amp;#34;,&amp;#34;data&amp;#34;:null}</p></body></html>
            """.trimIndent(),
        )

        assertTrue(reason.orEmpty().contains("批量API返回空响应"))
    }

    @Test
    fun splitChapterHeadingDoesNotHideDevicePoolErrorJson() {
        val reason = OnlineChapterContentValidator.storedXhtmlFailureReason(
            """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>第833章 途中轶事</title></head>
                <body><div class="te-chapter-heading"><div class="te-chapter-number">第833章</div><h1 class="te-chapter-title">途中轶事</h1></div>
                <p>{&quot;code&quot;:1,&quot;msg&quot;:&quot;批量请求失败: 设备池为空&quot;,&quot;data&quot;:null}</p>
                </body></html>
            """.trimIndent(),
        )

        assertTrue(reason.orEmpty().contains("设备池为空"))
    }

    @Test
    fun historicalFailurePlaceholderIsRejected() {
        assertTrue(
            OnlineChapterContentValidator.storedXhtmlFailureReason(
                chapterXhtml("本章下载失败，已按在线源重试 3 次。"),
            ).orEmpty().contains("下载失败占位"),
        )
    }

    @Test
    fun historicalBackgroundPlaceholderIsRejected() {
        assertTrue(
            OnlineChapterContentValidator.storedXhtmlFailureReason(
                chapterXhtml("本章正在后台下载中，请稍后重新打开。"),
            ).orEmpty().contains("后台下载占位"),
        )
    }

    @Test
    fun dailyQuotaPendingPlaceholderIsRejected() {
        assertTrue(
            OnlineChapterContentValidator.storedXhtmlFailureReason(
                chapterXhtml("本章尚未下载，当前在线源今日额度已用完。请明日点击更新继续补全。"),
            ).orEmpty().contains("日限额未完成占位"),
        )
    }

    @Test
    fun xhtmlWithoutBodyTextIsRejected() {
        assertTrue(
            OnlineChapterContentValidator.storedXhtmlFailureReason(
                chapterXhtml(""),
            ).orEmpty().contains("正文为空"),
        )
    }

    @Test
    fun normalStoredChapterIsAccepted() {
        assertNull(
            OnlineChapterContentValidator.storedXhtmlFailureReason(
                chapterXhtml("这是第一段正文。<br/>这是第二段正文。"),
            ),
        )
    }

    private fun chapterXhtml(content: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>第一章</title></head>
            <body><h1 class="te-chapter-title">第一章</h1><p>$content</p></body>
            </html>
        """.trimIndent()
}
