package com.reamicro.fix.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 阅读页背景取值判定。
 *
 * 对应的真机现象：切换深浅色时会闪一帧没有背景的纯白/纯黑，或者闪出另一个主题的图。
 * 这类一帧的问题真机上截图都抓不到，只能靠这里把分支钉死。
 */
class ReaderBackgroundValueResolverTest {

    private val token = "reamicro-bg-refresh://"
    private val lightUri = "file:///data/bg/light.jpg"
    private val darkUri = "file:///data/bg/dark.jpg"
    private val hostBuiltIn = "reader_paper_beige"

    private fun resolve(
        rawValue: String,
        remembered: String = "",
        selected: String = "",
        sideLoaded: Set<String> = setOf(lightUri, darkUri),
    ) = ReaderBackgroundValueResolver.resolve(
        rawValue = rawValue,
        rememberedHostValue = remembered,
        selectedUri = selected,
        refreshTokenPrefix = token,
        isSideLoaded = { it in sideLoaded },
    )

    @Test
    fun `选中侧载背景时直接返回它`() {
        val result = resolve(rawValue = hostBuiltIn, selected = darkUri)
        assertEquals(darkUri, result.result)
        assertEquals(hostBuiltIn, result.hostValue)
    }

    @Test
    fun `宿主自己的背景原样放行`() {
        val result = resolve(rawValue = hostBuiltIn)
        assertNull(result.result)
        assertEquals(hostBuiltIn, result.hostValue)
    }

    @Test
    fun `本主题没选侧载时不继承另一个主题的图`() {
        // 宿主 BACKGROUND 是全局单值，切主题后里面还留着上一个主题选的侧载图。
        val result = resolve(rawValue = lightUri, selected = "")
        assertEquals("", result.result)
    }

    @Test
    fun `刷新令牌帧还原成宿主原值，不把令牌交给宿主`() {
        // 这一帧要是把令牌透出去，宿主加载不到就画出纯白/纯黑——正是切换主题时闪的那一下。
        val result = resolve(rawValue = "$token 1234567890", remembered = hostBuiltIn)
        assertEquals(hostBuiltIn, result.result)
        assertEquals(hostBuiltIn, result.hostValue)
    }

    @Test
    fun `刷新令牌不会污染记住的宿主原值`() {
        // 记错了的话，下次"恢复原值"会把令牌永久写回宿主。
        val result = resolve(rawValue = "$token 999", remembered = hostBuiltIn)
        assertEquals(hostBuiltIn, result.hostValue)
    }

    @Test
    fun `令牌帧遇到已选侧载背景时仍返回侧载背景`() {
        val result = resolve(rawValue = "$token 42", remembered = hostBuiltIn, selected = darkUri)
        assertEquals(darkUri, result.result)
        assertEquals(hostBuiltIn, result.hostValue)
    }

    @Test
    fun `令牌帧且此前没记住原值时返回空而不是令牌`() {
        val result = resolve(rawValue = "$token 7", remembered = "")
        assertEquals("", result.result)
    }
}
