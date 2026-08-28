package com.reamicro.fix.cloud.api

import com.reamicro.fix.settings.ReaderHighlightStyle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高亮样式内容包的编解码。
 *
 * 关键约定：**样式本身不区分深色浅色**。深浅只存在于高亮规则（styleId / darkStyleId）
 * 和默认样式设置（defaultLightStyleId / defaultDarkStyleId）两层。
 * ReaderHighlightStyle 里的 dark* 字段是该设定移除前的残留，设置界面不再写入，
 * 所以内容包不携带它们。
 */
class ApiHighlightStylePayloadTest {

    private fun sample(
        id: String = "my_style",
        name: String = "我的高亮",
        ninePatchPath: String = "",
    ) = ReaderHighlightStyle(
        id = id,
        name = name,
        color = "#FF8800",
        fontFamily = "serif",
        css = "font-size: 0.9em",
        ninePatchPath = ninePatchPath,
        ninePatchSlice = if (ninePatchPath.isBlank()) "" else "10,10,10,10",
    )

    private fun payloadJson(style: ReaderHighlightStyle): JSONObject =
        JSONObject(String(writeHighlightStylePayload(style), Charsets.UTF_8))

    private fun roundTrip(style: ReaderHighlightStyle): ReaderHighlightStyle =
        readHighlightStylePayload(payloadJson(style).getJSONObject("style"), fallbackId = "fallback")

    @Test
    fun `往返保留样式字段`() {
        val restored = roundTrip(sample())
        assertEquals("my_style", restored.id)
        assertEquals("我的高亮", restored.name)
        assertEquals("#FF8800", restored.color)
        assertEquals("serif", restored.fontFamily)
        assertEquals("font-size: 0.9em", restored.css)
    }

    @Test
    fun `载荷不携带深色字段`() {
        // 样式不带深浅外观，传这些字段会让人误以为样式能自带深色版本。
        val text = String(writeHighlightStylePayload(sample()), Charsets.UTF_8)
        for (field in listOf("darkUsesLight", "darkColor", "darkFontFamily", "darkCss", "darkNinePatch")) {
            assertFalse("载荷不应包含 $field", text.contains(field))
        }
    }

    @Test
    fun `还原后深色字段保持默认值`() {
        val restored = roundTrip(sample())
        assertTrue("深色应跟随浅色", restored.darkUsesLight)
        assertEquals("", restored.darkColor)
        assertEquals("", restored.darkCss)
        assertEquals("", restored.darkFontFamily)
    }

    @Test
    fun `即便载荷里混入深色字段也不采纳`() {
        // 旧版本上传过带 dark* 的包，读回时要忽略，不能让残留字段复活。
        val legacy = JSONObject()
            .put("id", "legacy")
            .put("name", "旧包")
            .put("color", "#112233")
            .put("darkUsesLight", false)
            .put("darkColor", "#000000")
            .put("darkCss", "font-size: 2em")
        val style = readHighlightStylePayload(legacy, fallbackId = "unused")
        assertTrue("必须忽略旧包里的 darkUsesLight", style.darkUsesLight)
        assertEquals("", style.darkColor)
        assertEquals("", style.darkCss)
    }

    @Test
    fun `不上传本机图片路径`() {
        // 九宫格路径是本机绝对路径，对别的设备毫无意义，带上去只会指向不存在的文件。
        val text = String(writeHighlightStylePayload(sample(ninePatchPath = "/data/user/0/app/files/x.png")), Charsets.UTF_8)
        assertFalse(text.contains("ninePatchPath"))
        assertFalse(text.contains("/data/"))
    }

    @Test
    fun `缺字段时回落到默认值`() {
        val style = readHighlightStylePayload(JSONObject().put("id", "bare"), fallbackId = "unused")
        assertEquals("bare", style.id)
        // JUnit 的 assertEquals 是 (message, expected, actual)，与 kotlin.test 顺序相反。
        assertEquals("缺名称时用 ID 兜底", "bare", style.name)
        assertTrue("缺颜色时应有默认值", style.color.isNotBlank())
    }

    @Test
    fun `缺 id 时用兜底值`() {
        val style = readHighlightStylePayload(JSONObject().put("name", "无 ID"), fallbackId = "from_filename")
        assertEquals("from_filename", style.id)
    }

    @Test
    fun `载荷带 schemaVersion 便于后续演进`() {
        val json = payloadJson(sample())
        assertEquals(1, json.getInt("schemaVersion"))
        assertTrue(json.has("style"))
    }

    @Test
    fun `识别依赖本机资源的样式`() {
        assertFalse(highlightStyleUsesLocalAssets(sample()))
        assertTrue(highlightStyleUsesLocalAssets(sample(ninePatchPath = "/data/x.png")))
    }
}
