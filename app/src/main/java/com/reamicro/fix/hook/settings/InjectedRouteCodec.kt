package com.reamicro.fix.hook.settings

import android.os.Bundle
import com.reamicro.fix.ai.AiImagePresetTarget
import com.reamicro.fix.settings.OnlineEpubStyleKind

/** Only framework values cross an Activity recreation or a module ClassLoader change. */
internal object InjectedRouteCodec {
    private val pages = linkedMapOf(
        "module" to InjectedRoute.ModuleSettings,
        "association" to InjectedRoute.AssociationCompletionSettings,
        "reader" to InjectedRoute.ReaderCompletionSettings,
        "bookshelf" to InjectedRoute.BookshelfCompletionSettings,
        "selection" to InjectedRoute.ReaderSelectionMenuSettings,
        "highlight" to InjectedRoute.ReaderHighlightSettings,
        "highlight-style" to InjectedRoute.ReaderHighlightConfigSettings,
        "highlight-rules" to InjectedRoute.ReaderHighlightTextSettings,
        "highlight-color" to InjectedRoute.ReaderHighlightColorPicker,
        "profile" to InjectedRoute.ProfileBackgroundSettings,
        "cloud" to InjectedRoute.CloudCompletionSettings,
        "account" to InjectedRoute.AccountCompletionSettings,
        "api" to InjectedRoute.ApiServerSettings,
        "cloud-tasks" to InjectedRoute.CloudAutomationSettings,
        "local-tasks" to InjectedRoute.LocalAutomationSettings,
        "rotation" to InjectedRoute.RotationCompletionSettings,
        "switch-account" to InjectedRoute.AccountSwitch,
        "online" to InjectedRoute.OnlineCompletionSettings,
        "download-style" to InjectedRoute.OnlineDownloadStyleSettings,
        "ai" to InjectedRoute.AiConfigSettings,
        "dictionary" to InjectedRoute.DictionarySettings,
        "dictionary-api" to InjectedRoute.DictionaryApiPicker,
        "dictionary-presets" to InjectedRoute.DictionaryPresetPicker,
        "image" to InjectedRoute.ImageSettings,
        "image-api" to InjectedRoute.ImageApiPicker,
        "fonts" to InjectedRoute.FontSettings,
        "font-library" to InjectedRoute.FontLibrary,
        "about" to InjectedRoute.AboutCompletion,
        "discover" to InjectedRoute.Discover,
    )

    fun encode(route: InjectedRoute): Bundle = Bundle().apply {
        fun book(tag: String, key: String, title: String) {
            putString("page", tag)
            putString("book", key)
            putString("title", title)
        }
        when (route) {
            is InjectedRoute.BookStructure -> {
                book("book-structure", route.uuid, route.bookTitle)
                putLong("account", route.uid)
                putLong("book-id", route.bookId)
                putString("panel", route.pageKey)
            }
            is InjectedRoute.ReaderCompletionPlan -> book("book-plan", route.bookKey, route.bookTitle)
            is InjectedRoute.ReaderBookHighlightRules -> book("book-rules", route.bookKey, route.bookTitle)
            is InjectedRoute.ReaderBookOnlyHighlightRules -> book("book-only", route.bookKey, route.bookTitle)
            is InjectedRoute.ReaderBookGlobalHighlightRules -> book("book-global", route.bookKey, route.bookTitle)
            is InjectedRoute.OnlineEpubStyleList -> {
                putString("page", "epub-style")
                putString("target", route.kind.name)
            }
            is InjectedRoute.FontPicker -> {
                putString("page", "font-picker")
                putString("target", route.target.name)
            }
            is InjectedRoute.ImagePresetPicker -> {
                putString("page", "image-presets")
                putString("target", route.target.name)
            }
            else -> putString("page", pages.entries.single { it.value == route }.key)
        }
    }

    fun decode(data: Bundle): InjectedRoute? {
        val page = data.getString("page") ?: return null
        // 已移除的编辑页面在旧状态恢复时转到保留文件编辑入口的阅读补全。
        if (page == "edit") return InjectedRoute.ReaderCompletionSettings
        if (page == "module-diagnostics") return InjectedRoute.AboutCompletion
        pages[page]?.let { return it }
        val key = data.getString("book").orEmpty()
        val title = data.getString("title").orEmpty()
        val target = data.getString("target")
        return when (page) {
            "book-structure" -> {
                val uid = data.getLong("account", -1L)
                val id = data.getLong("book-id", -1L)
                val panel = data.getString("panel").orEmpty()
                if (uid < 0L || id <= 0L || key.isBlank() || key == "." || key == ".." ||
                    key.any { it == '/' || it == '\\' } || panel.isBlank()) null
                else InjectedRoute.BookStructure(uid, id, key, title, panel)
            }
            "book-plan" -> InjectedRoute.ReaderCompletionPlan(key, title)
            "book-rules" -> InjectedRoute.ReaderBookHighlightRules(key, title)
            "book-only" -> InjectedRoute.ReaderBookOnlyHighlightRules(key, title)
            "book-global" -> InjectedRoute.ReaderBookGlobalHighlightRules(key, title)
            "epub-style" -> OnlineEpubStyleKind.entries.firstOrNull { it.name == target }?.let(InjectedRoute::OnlineEpubStyleList)
            "font-picker" -> FontPickerTarget.entries.firstOrNull { it.name == target }?.let(InjectedRoute::FontPicker)
            "image-presets" -> AiImagePresetTarget.entries.firstOrNull { it.name == target }?.let(InjectedRoute::ImagePresetPicker)
            else -> null
        }
    }
}
