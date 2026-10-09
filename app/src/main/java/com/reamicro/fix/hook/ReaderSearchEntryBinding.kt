package com.reamicro.fix.hook

import java.lang.ref.WeakReference

internal fun ReaderHook.beginReaderSearchBar(receiver: Any?, book: Any?) {
    readerBottomBarComposeScope.enter()
    if (!canRunFullTextSearch()) return
    bottomSearchReceiverRef = receiver?.let { WeakReference(it) }
    bottomSearchBookRef = book?.let { WeakReference(it) }
    activityProvider()?.let(ReaderSearchUiResources::warm)
}

internal fun ReaderHook.endReaderSearchBar() {
    readerBottomBarComposeScope.exit()
}

internal fun ReaderHook.canReplaceReaderThemeControl(): Boolean {
    if (!readerBottomBarComposeScope.active) return false
    val snapshot = settingsProvider()
    return snapshot.moduleEnabled && snapshot.inlineSearchIconEnabled
}

internal fun ReaderHook.replaceReaderThemeIcon(args: Array<Any?>, name: String) {
    if (!canReplaceReaderThemeControl() || !isSearchThemeIcon(name)) return
    args[0] = searchImageVector() ?: return
    args[1] = "全文搜索"
    val flags = args[5] as? Int ?: 0
    args[5] = searchPaintChangedFlags(searchPaintChangedFlags(flags, 0), 1)
}

internal fun ReaderHook.bindReaderThemeClick(original: Any): Any {
    if (!canReplaceReaderThemeControl() || !isReaderBottomBarDarkLightToggleClick(original)) return original
    return readerInlineSearchClick ?: nativeFunction0 { openBottomSearchPage() }
        .also { readerInlineSearchClick = it }
}
