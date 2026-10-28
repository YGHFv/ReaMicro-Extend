package com.reamicro.fix.core

import com.reamicro.fix.epub.editor.SelectionHtmlSourceTree
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionHtmlSourceTreeTest {
    @Test
    fun `quoted greater signs and repeated text retain distinct source identity`() {
        val source = "<html><head><title>T</title></head><body><p title='a>b'>same</p><p>same</p></body></html>"
        val tree = SelectionHtmlSourceTree(source)
        val paragraphs = tree.bodyChildren()
        assertEquals(listOf("p", "p"), paragraphs.map { it.name })
        assertEquals(listOf("same", "same"), paragraphs.map { tree.text(it.children.single()) })
        assertEquals(source.indexOf("same"), paragraphs[0].children.single().start)
        assertEquals(source.lastIndexOf("same"), paragraphs[1].children.single().start)
    }

    @Test
    fun `void tags comments and raw script do not corrupt body tree`() {
        val tree = SelectionHtmlSourceTree("<body><!--x--><p>a<br>b<img src='x'/></p><script>if (a < b) x()</script></body>")
        assertEquals(listOf("p", "script"), tree.bodyChildren().map { it.name })
        assertEquals(listOf("#text", "br", "#text", "img"), tree.bodyChildren()[0].children.map { it.name })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `repaired nesting never receives guessed source positions`() {
        SelectionHtmlSourceTree("<body><p>one<div>two</p></div></body>")
    }
}
