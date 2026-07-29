package com.reamicro.fix.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineParagraphCommentRuntimePayloadTest {
    @Test
    fun `在线段评运行时载荷可无损编解码`() {
        val payload = OnlineParagraphCommentRuntimePayload(
            sourceId = "fanqie-source",
            bookId = "book-123",
            itemId = "item/456",
            paraIndex = 17,
            paragraphText = "包含中文、反斜线\\和分隔符\u001F的段落。",
            paragraphHash = "hash-1",
            runtimeId = "reamicro-online-comment:runtime-1",
        )

        val encoded = OnlineParagraphCommentRuntimePayloadCodec.encode(payload)
        val decoded = OnlineParagraphCommentRuntimePayloadCodec.decode(encoded)

        assertTrue(encoded.startsWith(OnlineParagraphCommentRuntimePayloadCodec.PREFIX))
        assertEquals(payload, decoded)
    }

    @Test
    fun `拒绝非在线段评和缺少必要字段的载荷`() {
        assertNull(OnlineParagraphCommentRuntimePayloadCodec.decode("https://example.test/comment"))
        assertNull(
            OnlineParagraphCommentRuntimePayloadCodec.decode(
                OnlineParagraphCommentRuntimePayloadCodec.PREFIX +
                    java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("{}".toByteArray()),
            ),
        )
    }
}
