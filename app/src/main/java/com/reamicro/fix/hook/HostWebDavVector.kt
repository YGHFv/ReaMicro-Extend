package com.reamicro.fix.hook

import com.reamicro.fix.core.HostGraphicsBindings
import com.reamicro.fix.hook.webdav.*

/** Module paths rendered with the host's queried vector parser and builder. */
internal class HostWebDavVector {
    private fun build(name: String, layers: List<Pair<Long, String>>): Any =
        requireNotNull(HostGraphicsBindings.current).build(name, layers, 24f, 24f, false)

    fun build(): Any = build("Colored.WebDAV", listOf(
        0xFF4BAFA7L to WEBDAV_ICON_BODY_PATH,
        0xFFFFFFFFL to WEBDAV_ICON_CLOUD_PATH,
        0xD6FFFFFFL to WEBDAV_ICON_SLOT_PATH,
    ))
    fun buildRefresh(): Any = build("Colored.Refresh", listOf(0xFFFFFFFFL to REFRESH_ICON_PATH))
}
