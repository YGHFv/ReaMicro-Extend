package com.reamicro.fix.epub.editor

internal object FontPreviewSamples {

    val defaults = listOf(
        "中文简体 Simplified Chinese" to "狐狸说：“重要的东西用眼睛是看不见的，只有用心才能看清。”",
        "中文繁体 Traditional Chinese" to "狐狸說：“重要的東西用眼睛是看不見的，只有用心才能看清。”",
        "英语 English" to "The fox said, \"What is essential is invisible to the eyes; only with the heart can one see clearly.\"",
        "日语 Japanese" to "狐は言った。「大切なものは目には見えない。心でしか見えない。」",
        "韩语 Korean" to "여우가 말했다. \"중요한 것은 눈으로는 보이지 않는다. 오직 마음으로만 볼 수 있다.\"",
    )

    fun edit(samples: List<Pair<String, String>>, index: Int, text: String): List<Pair<String, String>> {
        require(index in samples.indices) { "预览示例不存在" }
        val visible = text.codePoints().anyMatch {
            !Character.isWhitespace(it) && !Character.isSpaceChar(it) && Character.getType(it) != Character.FORMAT.toInt()
        }
        if (!visible) return samples
        return samples.mapIndexed { i, sample -> if (i == index) sample.first to text else sample }
    }
}
