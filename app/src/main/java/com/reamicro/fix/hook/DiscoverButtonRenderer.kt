package com.reamicro.fix.hook

import com.reamicro.fix.core.HostDiscoverBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.hook.discover.DiscoverIcon

/** Both halves keep the native row's icon, text style, arrow and divider color. */
internal fun renderDiscoverButton(
    classLoader: ClassLoader,
    rowScope: Any,
    composer: Any,
    nativeArrowColor: Long,
    nativeArrow: Any,
    nativeDividerColor: Long,
    onHostRowClick: () -> Unit,
    onDiscoverClick: () -> Unit,
    discoverVector: Any = requireNotNull(DiscoverIcon(classLoader).imageVectorOrNull()) { "Discover icon unavailable" },
) {
    val renderer = DiscoverRowRenderer()
    renderer.arrow(composer, nativeArrowColor, nativeArrow, 12, onHostRowClick)
    renderer.divider(composer, nativeDividerColor)
    renderer.image(discoverVector, composer, onDiscoverClick)
    renderer.label(composer, onDiscoverClick)
    renderer.spacer(rowScope, composer, onDiscoverClick)
    renderer.arrow(composer, nativeArrowColor, nativeArrow, 16, onDiscoverClick)
}

internal fun ReaMicroSettingsHook.leftCellIconModifier(composer: Any, onClick: () -> Unit): Any =
    DiscoverRowRenderer().iconModifier(16, onClick)

internal fun ReaMicroSettingsHook.leftCellLabelModifier(composer: Any, onClick: () -> Unit): Any =
    DiscoverRowRenderer().labelModifier(onClick)

internal fun ReaMicroSettingsHook.leftCellSpacerModifier(rowScope: Any, composer: Any, onClick: () -> Unit): Any =
    DiscoverRowRenderer().spacerModifier(rowScope, onClick)

internal fun ReaMicroSettingsHook.fillMaxWidthModifier(base: Any): Any =
    requireNotNull(requireNotNull(HostUiBindings.current).call("fillWidth", base, 1f))

private class DiscoverRowRenderer {
    private val host = requireNotNull(HostDiscoverBindings.current)
    private val ui = requireNotNull(HostUiBindings.current)
    private fun dp(value: Int) = (ui.call("dpInt", value) as Number).toFloat()
    private fun base() = ui.value("modifier")
    private fun padding(base: Any, start: Int, top: Int, end: Int, bottom: Int) =
        requireNotNull(ui.call("padding", base, dp(start), dp(top), dp(end), dp(bottom)))
    private fun clickable(base: Any, action: () -> Unit): Any {
        val callback = ui.callback(host.type("function0")) { action() }
        return requireNotNull(ui.call("clickable", base, true, null, null, callback, 6))
    }
    fun iconModifier(start: Int, action: () -> Unit): Any =
        requireNotNull(host.call("size", padding(clickable(base(), action), start, 16, 12, 16), dp(24)))
    fun labelModifier(action: () -> Unit) = padding(clickable(base(), action), 0, 16, 0, 16)
    fun spacerModifier(scope: Any, action: () -> Unit): Any {
        val weighted = ui.rowWeight(scope, base())
        return requireNotNull(ui.call("height", padding(clickable(weighted, action), 0, 16, 0, 16), dp(24)))
    }
    fun arrow(composer: Any, color: Long, vector: Any, end: Int, action: () -> Unit) {
        host.call("icon", vector, null, padding(clickable(base(), action), 0, 16, end, 16), color, composer, 48, 0)
    }
    fun divider(composer: Any, color: Long) {
        val width = requireNotNull(ui.call("width", base(), (ui.call("dpDouble", 0.8) as Number).toFloat()))
        val height = requireNotNull(ui.call("height", width, dp(56)))
        val colored = requireNotNull(ui.call("background", height, color, ui.value("rectangle")))
        ui.call("box", colored, composer, 0)
    }
    fun image(vector: Any, composer: Any, action: () -> Unit) {
        host.call("image", vector, "发现", iconModifier(12, action), composer, 48, 120)
    }
    fun label(composer: Any, action: () -> Unit) {
        val colors = requireNotNull(ui.call("colorScheme", composer))
        val typography = requireNotNull(ui.call("typography", composer))
        ui.call("text", "发现", labelModifier(action), ui.field("color.onBackground").getLong(colors), 0L,
            null, null, 0L, null, null, 0L, 0, false, 1, 1, null,
            ui.field("typography.titleSmall").get(typography), composer, 0, 0, 73720)
    }
    fun spacer(scope: Any, composer: Any, action: () -> Unit) {
        val args = arrayOfNulls<Any>(2)
        args[host.index("spacer.modifier")] = spacerModifier(scope, action)
        args[host.index("spacer.composer")] = composer
        host.call("spacer", *args)
    }
}
