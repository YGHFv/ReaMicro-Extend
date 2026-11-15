package com.reamicro.fix.hook

import com.reamicro.fix.core.HostNativeUi
import com.reamicro.fix.core.HostBookUiBindings
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostUiBindings

/** Adds image operations inside the host's own menu card, after its native picker row. */
internal class HostBookImageSheetUi(private val loader: ClassLoader) {
    private val ui = HostNativeUi(loader)
    private val native get() = requireNotNull(HostUiBindings.current)
    private val host get() = requireNotNull(HostBookUiBindings.imageSheet)

    fun renderAdditionalRows(
        composer: Any,
        actions: List<BookImageSheetActions.Action>,
        onAction: (BookImageSheetActions.Id) -> Unit,
    ) {
        actions.forEach { action ->
            val separatorModifier = host.call("menu.paddingDefault", ui.modifier(),
                ui.dp(host.number("menu.dividerInset")), 0f, 0f, 0f, host.number("menu.dividerMask"))
            host.call("menu.divider", separatorModifier, 0L, composer, 0, 2)
            val click = native.callback(host.type("function")) { onAction(action.id) }
            val clickable = requireNotNull(host.call("menu.clickable", ui.modifier(), false, null, null, click,
                host.number("menu.clickMask")))
            val modifier = ui.padding(clickable, ui.dp(host.number("menu.start")), ui.dp(host.number("menu.top")),
                ui.dp(host.number("menu.end")), ui.dp(host.number("menu.bottom")))
            val content = native.callback(native.type("function3")) { args ->
                val scope = requireNotNull(args?.getOrNull(0))
                val inner = requireNotNull(args?.getOrNull(1))
                val colors = ui.theme(inner, "getColorScheme")
                val typography = ui.theme(inner, "getTypography")
                val foreground = host.field("menu.foreground").getLong(colors)
                val icon = if (action.icon == "Edit") requireNotNull(HostBookUiBindings.metadata).call("edit")
                    else host.call("menu.icon.${action.icon}")
                val iconPadding = host.call("menu.paddingDefault", ui.modifier(), 0f, 0f,
                    ui.dp(host.number("menu.iconGap")), 0f, host.number("menu.iconMask"))
                val iconModifier = host.call("menu.size", iconPadding, ui.dp(host.number("menu.iconSize")))
                host.call("menu.icon", icon, null, iconModifier, foreground, inner, 48, 0)
                host.call("menu.text", action.label, ui.weight(scope, ui.modifier()), foreground, 0L, null, null,
                    0L, null, null, 0L, 0, false, 0, 0, null, host.field("menu.style").get(typography),
                    inner, host.number("menu.textFlags"), host.number("menu.textFlags2"), host.number("menu.textMask"))
                host.call("menu.icon", host.call("menu.next"), null,
                    host.call("menu.paddingDefault", ui.modifier(), 0f, ui.dp(host.number("menu.arrowTop")),
                        0f, 0f, host.number("menu.arrowMask")),
                    host.field("menu.trailing").getLong(colors), inner, 48, 0)
            }
            ui.row(modifier, content, composer)
        }
    }

    fun hide(role: String, scope: Any, sheetState: Any, finished: (Boolean, Throwable?) -> Unit) {
        // Hide on the native scope's dispatcher; wait off the Compose thread.
        com.reamicro.fix.reload.ModuleResources.thread {
            val result = runCatching {
                val context = host.method("$role.context").invoke(scope)
                    ?: error("图片工作表协程上下文不可用")
                HostCoroutines.runBlocking(loader, context) { continuation ->
                    host.method("hide").invoke(sheetState, continuation)
                }
                host.method("visible").invoke(sheetState) == false
            }
            finished(result.getOrDefault(false), result.exceptionOrNull())
        }.apply { name = "ReaMicroImageSheetHide" }.start()
    }
}
