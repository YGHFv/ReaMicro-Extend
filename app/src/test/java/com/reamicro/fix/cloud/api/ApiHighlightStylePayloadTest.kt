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
 * 历史问题：安装侧只读浅色字段，下载来的样式会丢掉整套深色配置。而 darkUsesLight
 * 默认 true，表现是深色下静默回落成浅色外观——不报错，但配置没了。
 */
class ApiHighlightStylePayloadTest {

    private fun sample(
        id: String = "my_style",
        name: String = "我的高亮",
        ninePatchPath: String = "",
        darkNinePatchPath: String = "",
    ) = ReaderHighlightStyle(
        id = id,
        name = name,
        color = "#FF8800",
        fontFamily = "serif",
        css = "font-size: 0.9em",
        ninePatchPath = ninePatchPath,
        ninePatchSlice = if (ninePatchPath.isBlank()) "" else "10,10,10,10",
        darkUsesLight = false,
        darkColor = "#442200",
        darkFontFamily = "sans-serif",
        darkCss = "font-size: 0.95em",
        darkNinePatchPath = darkNinePatchPath,
    )

    private fun roundTrip(style: ReaderHighlightStyle): ReaderHighlightStyle {
        val json = JSONObject(String(writeHighlightStylePayload(style), Charsets.UTF_8))
        return readHighlightStylePayload(json.getJSONObject("style"), fallbackId = "fallback")
    }

    @Test
    fun `往返保留深色字段`() {
        val restored = roundTrip(sample())
        assertFalse("darkUsesLight 必须保留", restored.darkUsesLight)
        assertEquals("#442200", restored.darkColor)
        assertEquals("sans-serif", restored.darkFontFamily)
        assertEquals("font-size: 0.95em", restored.darkCss)
    }

    @Test
    fun `往返保留浅色字段`() {
        val restored = roundTrip(sample())
        assertEquals("my_style", restored.id)
        assertEquals("我的高亮", restored.name)
        assertEquals("#FF8800", restored.color)
        assertEquals("serif", restored.fontFamily)
        assertEquals("font-size: 0.9em", restored.css)
    }

    @Test
    fun `不上传本机图片路径`() {
        // 九宫格路径是本机绝对路径，对别的设备毫无意义，带上去只会指向不存在的文件。
        val style = sample(ninePatchPath = "/data/user/0/app/files/x.png", darkNinePatchPath = "/data/dark.png")
        val text = String(writeHighlightStylePayload(style), Charsets.UTF_8)
        assertFalse(text.contains("ninePatchPath"))
        assertFalse(text.contains("/data/"))
    }

    @Test
    fun `缺字段时回落到默认值`() {
        val minimal = JSONObject().put("id", "bare")
        val style = readHighlightStylePayload(minimal, fallbackId = "unused")
        assertEquals("bare", style.id)
        // JUnit 的 assertEquals 是 (message, expected, actual)，与 kotlin.test 顺序相反。
        assertEquals("缺名称时用 ID 兜底", "bare", style.name)
        assertTrue("缺 darkUsesLight 时应默认跟随浅色", style.darkUsesLight)
        assertTrue("缺颜色时应有默认值", style.color.isNotBlank())
    }

    @Test
    fun `缺 id 时用兜底值`() {
        val style = readHighlightStylePayload(JSONObject().put("name", "无 ID"), fallbackId = "from_filename")
        assertEquals("from_filename", style.id)
    }

    @Test
    fun `载荷带 schemaVersion 便于后续演进`() {
        val json = JSONObject(String(writeHighlightStylePayload(sample()), Charsets.UTF_8))
        assertEquals(1, json.getInt("schemaVersion"))
        assertTrue(json.has("style"))
    }

    @Test
    fun `识别依赖本机资源的样式`() {
        assertFalse(highlightStyleUsesLocalAssets(sample()))
        assertTrue(highlightStyleUsesLocalAssets(sample(ninePatchPath = "/data/x.png")))
        assertTrue(highlightStyleUsesLocalAssets(sample(darkNinePatchPath = "/data/y.png")))
    }
}
