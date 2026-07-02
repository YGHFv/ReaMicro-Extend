package com.reamicro.fix.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiApiStoreTest {
    @Test
    fun dictionarySettingsDefaultToDisableThinking() {
        val settings = AiApiStore.dictionarySettings(null)

        assertTrue(settings.disableThinking)
        assertEquals(AiApiStore.DEFAULT_DICTIONARY_PRESET_ID, settings.presetId)
    }

    @Test
    fun dictionaryHasThreeBuiltinPresets() {
        val presets = AiApiStore.dictionaryPresets(null)

        assertEquals(
            listOf(
                "\u8bcd\u5178\u91ca\u4e49",
                "\u8be6\u7ec6\u767e\u79d1",
                "\u6587\u8a00\u6587/\u53e4\u8bd7\u8bcd",
            ),
            presets.map { it.name },
        )
        assertTrue(presets.all { it.builtIn })
    }

    @Test
    fun dictionaryPromptReplacesSelectedTextPlaceholder() {
        val prompt = AiApiStore.renderDictionaryPrompt("\u89e3\u91ca {{text}}", "\u6625\u98ce")

        assertEquals("\u89e3\u91ca \u6625\u98ce", prompt)
    }
}
