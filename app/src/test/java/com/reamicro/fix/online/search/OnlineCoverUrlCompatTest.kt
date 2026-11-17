package com.reamicro.fix.online.search

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineCoverUrlCompatTest {
    @Test fun failedProxyUsesTheExactOriginalQidianBookId() {
        listOf("1045805062", "1044818845", "1048827563", "1039831421", "1045525701").forEach { id ->
            assertEquals("https://bookcover.yuewen.com/qdbimg/349573/$id/600",
                directOnlineCoverUrl("https://shenmoxs.top/cover?bookId=$id&size=600"))
        }
    }

    @Test fun otherHostsPathsAndNonNumericIdsArePreserved() {
        listOf(
            "https://shenmoxs.top.evil/cover?bookId=1045805062",
            "https://shenmoxs.top/other?bookId=1045805062",
            "https://shenmoxs.top/cover?bookId=abc",
            "https://bookcover.yuewen.com/qdbimg/349573/1045805062/600.webp",
            "data:image/png;base64,aGVsbG8=",
        ).forEach { assertEquals(it, directOnlineCoverUrl(it)) }
    }
}
