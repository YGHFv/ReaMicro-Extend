package com.reamicro.fix.online.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineVolumeHeadingMarkupTest {
    @Test
    fun `splits numbered volume label with separator`() {
        assertEquals(
            OnlineVolumeHeading("第一卷", "风起南疆"),
            OnlineVolumeHeadingMarkup.parse("第一卷 风起南疆"),
        )
    }

    @Test
    fun `splits numbered volume label without separator`() {
        assertEquals(
            OnlineVolumeHeading("第一卷", "风起南疆"),
            OnlineVolumeHeadingMarkup.parse("第一卷风起南疆"),
        )
    }

    @Test
    fun `splits section labels`() {
        assertEquals(
            OnlineVolumeHeading("第三节", "旧日之影"),
            OnlineVolumeHeadingMarkup.parse("第三节 旧日之影"),
        )
        assertEquals(
            OnlineVolumeHeading("第三节", "旧日之影"),
            OnlineVolumeHeadingMarkup.parse("第三节旧日之影"),
        )
    }

    @Test
    fun `splits sub section labels`() {
        assertEquals(
            OnlineVolumeHeading("第八小节", "直至时间的尽头"),
            OnlineVolumeHeadingMarkup.parse("第八小节 直至时间的尽头"),
        )
        assertEquals(
            OnlineVolumeHeading("第八小节", "直至时间的尽头"),
            OnlineVolumeHeadingMarkup.parse("第八小节直至时间的尽头"),
        )
    }

    @Test
    fun `splits preface labels`() {
        assertEquals(
            OnlineVolumeHeading("序", "长夜将至"),
            OnlineVolumeHeadingMarkup.parse("序 长夜将至"),
        )
        assertEquals(
            OnlineVolumeHeading("序", "长夜将至"),
            OnlineVolumeHeadingMarkup.parse("序长夜将至"),
        )
        assertEquals(
            OnlineVolumeHeading("序章", "长夜将至"),
            OnlineVolumeHeadingMarkup.parse("序章：长夜将至"),
        )
    }

    @Test
    fun `keeps number only when volume has no name`() {
        assertEquals(OnlineVolumeHeading("第二卷", ""), OnlineVolumeHeadingMarkup.parse("第二卷"))
        assertEquals(OnlineVolumeHeading("序", ""), OnlineVolumeHeadingMarkup.parse("序"))
        assertEquals(OnlineVolumeHeading("第十卷", ""), OnlineVolumeHeadingMarkup.parse("第 十 卷"))
    }

    @Test
    fun `keeps plain title when no label is recognized`() {
        assertEquals(OnlineVolumeHeading("", "呢喃诗章"), OnlineVolumeHeadingMarkup.parse("呢喃诗章"))
    }

    @Test
    fun `splits latin volume label`() {
        assertEquals(
            OnlineVolumeHeading("Volume 2", "Dawn"),
            OnlineVolumeHeadingMarkup.parse("Volume 2: Dawn"),
        )
    }

    @Test
    fun `split markup keeps number above title`() {
        val html = OnlineVolumeHeadingMarkup.split("第八小节", "直至时间的尽头")

        assertEquals(
            "<div class=\"te-volume-page\">" +
                "<div class=\"te-volume-ornament\">※</div>" +
                "<h1 class=\"te-volume-title\">" +
                "<span class=\"te-volume-number\">第八小节</span>" +
                "<span class=\"te-volume-name\">直至时间的尽头</span>" +
                "</h1></div>",
            html,
        )
        assertTrue(html.indexOf("te-volume-number") < html.indexOf("te-volume-name"))
    }

    @Test
    fun `single markup renders only one line`() {
        assertEquals(
            "<div class=\"te-volume-page\">" +
                "<div class=\"te-volume-ornament\">※</div>" +
                "<h1 class=\"te-volume-title\"><span class=\"te-volume-name\">第二卷</span></h1>" +
                "</div>",
            OnlineVolumeHeadingMarkup.single("第二卷"),
        )
    }
}
