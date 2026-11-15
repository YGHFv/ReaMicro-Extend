package com.reamicro.fix.hook

internal fun shouldReleaseReaderBackKey(
    contractAvailable: Boolean,
    keyCode: Int,
    action: Int,
    repeatCount: Int,
    cancelled: Boolean,
    readerStatus: String,
    sheetHidden: Boolean,
    selectionKnownEmpty: Boolean,
    editorOrDialogOpen: Boolean,
): Boolean =
    contractAvailable && keyCode == 4 && action == 0 && repeatCount == 0 &&
        !cancelled && readerStatus in listOf("Reader", "Menu") &&
        sheetHidden && selectionKnownEmpty && !editorOrDialogOpen
