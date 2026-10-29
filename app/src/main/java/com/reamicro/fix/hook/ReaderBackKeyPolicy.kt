package com.reamicro.fix.hook

internal fun shouldReleaseReaderBackKey(
    profile: String,
    keyCode: Int,
    action: Int,
    repeatCount: Int,
    cancelled: Boolean,
    readerStatus: String,
    sheetHidden: Boolean,
    selectionKnownEmpty: Boolean,
    editorOrDialogOpen: Boolean,
): Boolean =
    profile in listOf("2.3.3-rc3", "2.3.3") && keyCode == 4 && action == 0 && repeatCount == 0 &&
        !cancelled && readerStatus in listOf("Reader", "Menu") &&
        sheetHidden && selectionKnownEmpty && !editorOrDialogOpen
