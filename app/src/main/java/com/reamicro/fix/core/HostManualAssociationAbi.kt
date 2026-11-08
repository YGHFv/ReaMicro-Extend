package com.reamicro.fix.core

/** The built-in editor uses the verified publisher screen and native candidate submission. */
internal object HostManualAssociationAbi {
    const val SCREEN = "app.zhendong.reamicro.ui.book.BookPublishScreenKt"
    const val SCREEN_METHOD = "BookPublishScreen"

    fun supports(profileId: String = HostSymbols.profileId): Boolean = profileId == "2.3.4-b1"
}
