package com.reamicro.fix.core

/** Logical names retained for reflection callers and diagnostics. Every value comes from
 * a validated feature query for this APK; these are roles, never obfuscated aliases. */
internal object HostSemanticFeatureExports {
    fun query(groups: Map<String, Map<String, String>>): Map<String, String> = buildMap {
        fun export(group: String, role: String, logical: String) {
            groups[group]?.get(role)?.let { put(logical, it) }
        }
        fun methods(group: String, owner: String, vararg roles: Pair<String, String>) {
            roles.forEach { (role, name) -> export(group, "method:$role", "method:$owner#$name") }
        }
        fun type(group: String, role: String, logical: String) = export(group, "class:$role", "class:$logical")
        type("runtime.http", "requestBody", "ReaMicroHost.HttpRequestBody")
        type("epub.svg", "box", "ReaMicroHost.SvgBox")
        type("epub.svg", "root", "ReaMicroHost.SvgRoot")
        type("reader.epubSource", "textNode", "com.fleeksoft.ksoup.nodes.TextNode")
        type("reader.epubSource", "assertion", "org.epub.html.EpubCFI\$IDAssertion")
        type("reader.selection", "controller", "org.epub.ui.EpubSelectionController")
        type("graphics.vector", "vector", "androidx.compose.ui.graphics.vector.ImageVector")
        for (status in listOf("Reader", "Menu", "Catalog", "Font", "Highlight", "MarginEditor", "Theme", "TypeSetting", "EinkSettings")) {
            type("reader.backKey", "status.$status", "app.zhendong.reamicro.ui.reader.UiStatus\$$status")
        }
        type("reader.highlightUi", "sheet.insets", "androidx.compose.foundation.layout.WindowInsets")
        methods("epub.svg", "ReaMicroHost.SvgDocument", "dimensions" to "dimensions")
        methods("reader.fontCompletion", "ReaMicroHost.BuiltInFontRow", "row" to "render")
        methods("ui.native", "androidx.compose.foundation.BackgroundKt", "background" to "background-bw27NRU")
        methods("ui.native", "androidx.compose.foundation.layout.SizeKt", "height" to "height-3ABfNKs")
        methods("ui.native", "androidx.compose.runtime.Composer", "remember" to "rememberedValue", "updateRemember" to "updateRememberedValue")
        methods("ui.native", "androidx.compose.ui.graphics.ColorKt", "colorFromArgb" to "Color", "colorToArgb" to "toArgb-8_81llA")
        methods("ui.native", "app.zhendong.reamicro.arch.components.DividerKt", "divider" to "SimpleDivider-iJQMabo")
        methods("reader.pager", "androidx.compose.foundation.pager.PagerState", "current" to "getCurrentPage")
        methods("reader.epub", "androidx.compose.material.icons.outlined.SearchKt", "searchIcon" to "getSearch")
        methods("reader.highlightText", "androidx.compose.ui.node.DrawModifierNodeKt", "invalidate" to "invalidateDraw")
        methods("reader.epubSource", "androidx.compose.ui.text.MultiParagraph", "scroll.lineTop" to "getLineTop", "scroll.lineBottom" to "getLineBottom")
        methods("reader.scroll", "androidx.paging.compose.LazyPagingItems", "snapshot" to "getItemSnapshotList")
        methods("runtime.navigation", "app.zhendong.reamicro.NavGraphScope", "navigate" to "navigate", "pop" to "popBackStack")
        methods("integration.work", "app.zhendong.reamicro.arch.WorkTracker", "update" to "setState")
        methods("books.metadata", "app.zhendong.reamicro.arch.icons.colored.BaiduNetdiskKt", "baidu" to "getBaiduNetdisk")
        methods("reader.epub", "app.zhendong.reamicro.data.epub.Epub", "readElements" to "readSearchElements")
        methods("storage.library", "app.zhendong.reamicro.repository.BookshelfRepository", "liveUri" to "liveBookByUrl", "update" to "updateBook")
        methods("books.backup", "app.zhendong.reamicro.ui.backup.BookBackupScreenKt", "content" to "BookBackupContent")
        methods("books.backup", "app.zhendong.reamicro.ui.backup.components.DriveCardKt", "yun115" to "Yun115NetDiskCard")
        methods("storage.ui", "app.zhendong.reamicro.ui.home.components.BookLibrarySheetKt", "local" to "LocalLibrary")
        methods("books.localSheet", "app.zhendong.reamicro.ui.home.components.BookLocalSheetKt", "info" to "BookInfo")
        methods("reader.lifecycle", "app.zhendong.reamicro.ui.reader.ReaderViewModel", "capture" to "captureReturnPosition", "cleared" to "onCleared")
        methods("reader.virtualPageGuard", "app.zhendong.reamicro.ui.reader.ReaderViewModel", "mapped" to "ensureVirtualPageMapped")
        methods("reader.pager", "app.zhendong.reamicro.ui.reader.components.CurlPagerKt", "curl.root" to "CurlPager")
        methods("reader.pager", "app.zhendong.reamicro.ui.reader.components.CurlPager_androidKt", "curl.platform" to "PlatformCurlPager")
        methods("ui.globalFont", "app.zhendong.reamicro.ui.reader.components.ReaderBottomBarKt", "reader.bottomBar" to "ReaderBottomBar")
        methods("settings.entry", "app.zhendong.reamicro.ui.setting.SettingsScreenKt", "root" to "SettingsScreen")
        methods("storage.account", "app.zhendong.reamicro.ui.storage.baidu.BaiduNetDiskAccountScreenKt", "webdav.screen" to "BaiduNetDiskAccountScreen", "webdav.logout" to "LogOut")
        methods("reader.epubSource", "com.fleeksoft.ksoup.nodes.TextNode", "text.normalized" to "text")
        methods("reader.epub", "kotlinx.coroutines.flow.StateFlowImpl", "flow.cas" to "compareAndSet", "flow.set" to "setValue")
        methods("reader.lifecycle", "org.epub.UIEpubWindow", "dp" to "dp")
        methods("reader.epubSource", "org.epub.UIEpubWindow", "scroll.px" to "px")
        methods("reader.epubSource", "org.epub.css.property.CSSProperty", "css.actual" to "getActualValue")
        methods("reader.epubSource", "org.epub.html.EpubCFI", "cfi.compare" to "compareTo")
        methods("reader.epubSource", "org.epub.html.EpubCFI\$Builder", "builder.build" to "build", "builder.copy" to "newBuilder")
        methods("reader.window", "org.epub.html.EpubCFIRange", "contains" to "contains")
        methods("epub.metadata", "org.epub.structure.metadata.IdentifierId", "id.value" to "getValue")
        methods("reader.selection", "org.epub.ui.EpubSelectionController", "clear" to "clearSelection", "selection" to "getSelection", "payload" to "selectedPayload")
        methods("reader.epubSource", "org.epub.utils.NodeExtKt", "node.steps" to "getAllSteps", "node.step" to "getSteps")
    }
}
