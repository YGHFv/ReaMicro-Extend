package com.reamicro.fix.online

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineJsonPathCompatTest {
    @Test
    fun `recursive descent continues through array and object wildcards`() {
        val root = JSONObject(
            """
            {
              "data": {
                "chapterListWithVolume": [
                  {
                    "第一卷": [
                      {"title":"第一章","itemId":"1","volume_name":"第一卷"},
                      {"title":"第二章","itemId":"2","volume_name":"第一卷"}
                    ]
                  },
                  {
                    "第二卷": [
                      {"title":"第三章","itemId":"3","volume_name":"第二卷"},
                      {"title":"第四章","itemId":"4","volume_name":"第二卷"}
                    ]
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        val values = OnlineJsonPathCompat.values(root, "$..chapterListWithVolume[*].*")
        val chapters = values.flatMap { value ->
            when (value) {
                is JSONArray -> (0 until value.length()).map { value.optJSONObject(it) }
                else -> listOf(value as? JSONObject)
            }.filterNotNull()
        }

        assertEquals(listOf("第一章", "第二章", "第三章", "第四章"), chapters.map { it.getString("title") })
        assertEquals(listOf("1", "2", "3", "4"), chapters.map { it.getString("itemId") })
    }

    @Test
    fun `plain recursive field lookup remains compatible`() {
        val root = JSONObject("""{"data":{"book":{"book_name":"测试书"}}}""")

        assertEquals("测试书", OnlineJsonPathCompat.values(root, "$..book_name").single())
    }

    @Test
    fun `recursive array wildcard returns every item`() {
        val root = JSONObject("""{"data":{"books":[{"id":1},{"id":2},{"id":3}]}}""")

        val items = OnlineJsonPathCompat.values(root, "$..books[*]")

        assertEquals(listOf(1, 2, 3), items.map { (it as JSONObject).getInt("id") })
    }

    @Test
    fun `and operator merges aladdin object and data array`() {
        val root = JSONObject(
            """{"aladdin":{"bid":1},"data":[{"bid":2},{"bid":3}]}""",
        )

        val items = OnlineJsonPathCompat.values(root, "$..aladdin&&$.data")

        assertEquals(listOf(1, 2, 3), items.flatMap { value ->
            when (value) {
                is JSONArray -> (0 until value.length()).map { value.getJSONObject(it).getInt("bid") }
                else -> listOf((value as JSONObject).getInt("bid"))
            }
        })
    }

    @Test
    fun `shuqi volume list path returns chapter objects`() {
        val root = JSONObject(
            """{"data":{"chapterList":[{"volumeList":[{"chapterId":"1"},{"chapterId":"2"}]}]}}""",
        )

        val values = OnlineJsonPathCompat.values(root, "$.data.chapterList[0].volumeList")

        assertEquals(
            listOf("1", "2"),
            values.flatMap { value ->
                when (value) {
                    is JSONArray -> (0 until value.length()).map { value.getJSONObject(it).getString("chapterId") }
                    else -> listOf((value as JSONObject).getString("chapterId"))
                }
            },
        )
    }
}
