package com.reamicro.fix.cloud.webdav

internal data class SyncAuthCardRenderContext(
    val bookId: Long? = null,
    val onSetupDefaultDir: Any? = null,
    val onPick: Any? = null,
    var webDavRendered: Boolean = false,
    var nativeAuthRowsSeen: Int = 0,
)

internal data class ImportUnauthRenderContext(
    val expectedNativeRows: Int,
    var renderedNativeRows: Int = 0,
    var webDavRendered: Boolean = false,
)

internal data class ImportLocalLibraryRowContext(
    val navGraphScope: Any?,
    val coroutineScope: Any?,
    val sheetState: Any?,
    val intentReceiver: Any?,
)

internal data class HomeSearchRenderContext(
    val sections: List<HomeSearchSection>,
    val intentReceiver: Any,
    var rendered: Boolean = false,
)

internal data class HomeSearchSection(
    val type: Int,
    val title: String = "",
    val results: List<*>,
)

internal data class CloudBookRowExtendedDisplayContext(
    val updatedAt: Long,
    val extensionLabel: String,
    var textIndex: Int = 0,
    var extensionSuppressed: Boolean = false,
)
