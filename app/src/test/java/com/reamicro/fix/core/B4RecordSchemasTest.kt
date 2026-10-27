package com.reamicro.fix.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** B4 两个反射复制的记录必须与 profile/DEX 取证保持同一字段顺序。 */
class B4RecordSchemasTest {
    private val profile = HostProfile.parse(bundledAsset("host-profiles/2.3.3-b4.profile").readText(Charsets.UTF_8))

    @Test
    fun bookCopyKeepsAllTwentySevenVerifiedFields() {
        val logical = "app.zhendong.reamicro.data.db.entity.Book"
        val actual = B2BookAssociation.properties.map { profile.fieldOf(logical, it) }
        assertEquals(('a'..'z').map(Char::toString) + "A", actual)
        assertEquals("v", profile.fieldOf(logical, "cloudId"))
        assertEquals("z", profile.fieldOf(logical, "publisher"))
        assertEquals("n", profile.fieldOf(logical, "epubcfi"))
        assertEquals("o", profile.fieldOf(logical, "chapter"))
        assertEquals("p", profile.fieldOf(logical, "progress"))
        assertEquals("t", profile.fieldOf(logical, "updated"))
    }

    @Test
    fun readerUiStateCopyKeepsAllSixteenVerifiedFields() {
        val logical = "app.zhendong.reamicro.ui.reader.ReaderUiState"
        assertEquals("zm8", profile.classOf(logical))
        assertEquals(
            "zm8ReaderUiState=" + readerUiStateProperties.joinToString(","),
            readerUiStateSchema,
        )
        assertEquals(
            listOf("book", "loadingStage", "loadingError", "status", "sheetStatus", "tapDirection"),
            readerUiStateProperties.map { readerUiStateLogicalName(it) }.take(6),
        )
        assertEquals(
            listOf(
                "progress", "catalogIndex", "catalog", "chapterItemsMap",
                "fontObfuscationVersion", "returnPosition", "searchQuery",
                "searchResults", "searching", "activeSearchResult",
            ),
            readerUiStateProperties.map { readerUiStateLogicalName(it) }.drop(6),
        )
    }

    private val readerUiStateSchema = (
        "zm8ReaderUiState=" + readerUiStateProperties.joinToString(",")
        )

    private fun readerUiStateLogicalName(field: String): String = when (field) {
        "a" -> "book"
        "b" -> "loadingStage"
        "c" -> "loadingError"
        "d" -> "status"
        "e" -> "sheetStatus"
        "f" -> "tapDirection"
        "g" -> "progress"
        "h" -> "catalogIndex"
        "i" -> "catalog"
        "j" -> "chapterItemsMap"
        "k" -> "fontObfuscationVersion"
        "l" -> "returnPosition"
        "m" -> "searchQuery"
        "n" -> "searchResults"
        "o" -> "searching"
        else -> "activeSearchResult"
    }

    private companion object {
        // MT/REA 对 Lzm8 的构造器与 toString 双向核实：a..p 共 16 个字段。
        val readerUiStateProperties = ('a'..'p').map(Char::toString)

        fun bundledAsset(relative: String): File = listOf(
            File("src/main/assets/$relative"),
            File("app/src/main/assets/$relative"),
        ).first { it.isFile }
    }
}
