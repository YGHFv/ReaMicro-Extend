package com.reamicro.fix.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineBodyMarkupTest {
    @Test
    fun `illustration uses the standard figure interface`() {
        assertEquals(
            "<figure class=\"te-illustration\">" +
                "<img class=\"te-illustration-image\" src=\"../Images/a.jpg\" alt=\"\"/>" +
                "</figure>",
            OnlineBodyMarkup.illustration("../Images/a.jpg"),
        )
    }

    @Test
    fun `illustration keeps a caption when provided`() {
        assertTrue(
            OnlineBodyMarkup.illustration("../Images/a.jpg", "图 1")
                .contains("<figcaption class=\"te-illustration-caption\">图 1</figcaption>"),
        )
    }

    @Test
    fun `divider carries both the standard and legacy class`() {
        assertEquals("<p class=\"te-divider-line fg1\">※※※</p>", OnlineBodyMarkup.divider("※※※"))
    }

    @Test
    fun `image divider uses the standard interface`() {
        assertEquals(
            "<div class=\"te-divider-image\">" +
                "<img class=\"te-divider-img\" src=\"../Images/divider.png\" alt=\"\"/>" +
                "</div>",
            OnlineBodyMarkup.dividerImage("../Images/divider.png"),
        )
    }

    @Test
    fun `header uses the standard figure interface`() {
        assertEquals(
            "<figure class=\"te-header-figure\">" +
                "<img class=\"te-header-image\" src=\"../Images/header.png\" alt=\"\"/>" +
                "</figure>",
            OnlineBodyMarkup.header("../Images/header.png"),
        )
    }

    @Test
    fun `transition renders the style's own markup`() {
        assertEquals(
            """<p class="te-transition te-transition--diamond" aria-label="场景转换"></p>""",
            OnlineBodyMarkup.transition(
                """<p class="te-transition te-transition--diamond" aria-label="场景转换"></p>""",
                "※※※",
                null,
            ),
        )
    }

    @Test
    fun `transition binds the divider image into the markup`() {
        val html = OnlineBodyMarkup.transition(
            "<div class=\"te-divider-image\">\n  <img class=\"te-divider-img\" src=\"../Images/divider.png\" alt=\"\" />\n</div>",
            "※※※",
            "../Images/divider.jpg",
        )

        assertTrue(html.contains("""src="../Images/divider.jpg""""))
        assertFalse(html.contains("divider.png"))
    }

    @Test
    fun `transition without markup falls back to the default divider`() {
        assertEquals(OnlineBodyMarkup.divider("※※※"), OnlineBodyMarkup.transition("", "※※※", null))
        assertEquals(
            OnlineBodyMarkup.dividerImage("../Images/divider.png"),
            OnlineBodyMarkup.transition("", "※※※", "../Images/divider.png"),
        )
    }

    @Test
    fun `migrates legacy illustration div`() {
        assertEquals(
            OnlineBodyMarkup.illustration("../Images/a.jpg"),
            OnlineBodyMarkup.migrateLegacyBody(
                "<div class=\"online-illustration\"><img src=\"../Images/a.jpg\" alt=\"\"/></div>",
            ),
        )
    }

    @Test
    fun `migrates legacy divider paragraph`() {
        assertEquals(
            OnlineBodyMarkup.divider("※※※"),
            OnlineBodyMarkup.migrateLegacyBody("<p class=\"divider-line\">※※※</p>"),
        )
    }

    @Test
    fun `migration is idempotent on current markup`() {
        val current = OnlineBodyMarkup.illustration("../Images/a.jpg") + OnlineBodyMarkup.divider("※※※")

        assertEquals(current, OnlineBodyMarkup.migrateLegacyBody(current))
    }

    @Test
    fun `plain paragraphs are left untouched`() {
        val body = "<p class=\"te-paragraph\">正文</p>"

        assertEquals(body, OnlineBodyMarkup.migrateLegacyBody(body))
    }
}
