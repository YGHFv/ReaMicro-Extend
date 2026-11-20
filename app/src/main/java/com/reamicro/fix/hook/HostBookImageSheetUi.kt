package com.reamicro.fix.hook

import com.reamicro.fix.core.HostNativeUi
import com.reamicro.fix.core.HostBookUiBindings
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostUiBindings

/**
 * Draws the module's image operations with the host's own menu primitives.
 * They form a card of their own between the native picker card and the native cancel card.
 */
internal class HostBookImageSheetUi(private val loader: ClassLoader) {
    private val ui = HostNativeUi(loader)
    private val native get() = requireNotNull(HostUiBindings.current)
    private val host get() = requireNotNull(HostBookUiBindings.imageSheet)

    /** The compound key of the group the composer is currently inside. */
    fun groupHash(composer: Any): Long = native.field("hash").getLong(composer)

    /** Emits the host's 16dp card gap followed by a native card holding [actions]. */
    fun renderActionCard(
        composer: Any,
        actions: List<BookImageSheetActions.Action>,
        onAction: (BookImageSheetActions.Id) -> Unit,
    ) {
        if (actions.isEmpty()) return
        native.instance("startGroup", composer, CARD_GROUP)
        try {
            ui.spacer(ui.height(ui.modifier(), ui.dp(CARD_GAP)), composer)
            val colors = ui.theme(composer, "getColorScheme")
            val card = ui.card(ui.modifier(), native.call("backgroundAuto", colors) as Long,
                native.call("borderVariant", colors) as Long, ui.dp(CARD_BORDER))
            val content = native.callback(native.type("function3")) { args ->
                val inner = requireNotNull(args?.getOrNull(1))
                actions.forEachIndexed { index, action -> renderRow(inner, action, index > 0, onAction) }
            }
            ui.column(card, ui.top(), ui.start(), content, composer)
        } finally {
            native.instance("endGroup", composer, false)
        }
    }

    private fun renderRow(
        composer: Any,
        action: BookImageSheetActions.Action,
        separated: Boolean,
        onAction: (BookImageSheetActions.Id) -> Unit,
    ) {
        if (separated) {
            val separatorModifier = host.call("menu.paddingDefault", ui.modifier(),
                ui.dp(host.number("menu.dividerInset")), 0f, 0f, 0f, host.number("menu.dividerMask"))
            host.call("menu.divider", separatorModifier, 0L, composer, 0, 2)
        }
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

    private companion object {
        const val CARD_GROUP = 0x524D4943
        /** Host sheet: 16dp between the picker card and the cancel card; 0.8dp card outline. */
        const val CARD_GAP = 16
        const val CARD_BORDER = 0.8
    }
}
