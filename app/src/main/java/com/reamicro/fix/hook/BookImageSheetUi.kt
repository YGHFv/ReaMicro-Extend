package com.reamicro.fix.hook

import com.reamicro.fix.core.HostClasses
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

internal class BookImageSheetUi(private val loader: ClassLoader) {
    private fun cls(name: String) = loader.loadClass(name)
    private fun method(owner: String, name: String, count: Int, first: String? = null, second: String? = null, third: String? = null): Method =
        cls(owner).declaredMethods.single {
            (it.name == name || it.name.startsWith("$name-")) &&
                (!it.name.endsWith("\$default") || name.endsWith("\$default")) &&
                it.parameterTypes.size == count &&
                (first == null || it.parameterTypes.first().name == first) &&
                (second == null || it.parameterTypes.getOrNull(1)?.name == second) &&
                (third == null || it.parameterTypes.getOrNull(2)?.name == third)
        }.apply { isAccessible = true }
    private fun singleton(owner: String): Any {
        val type = cls(owner)
        return (type.declaredFields.firstOrNull { it.name == "INSTANCE" }
            ?: type.getDeclaredField("Companion")).apply { isAccessible = true }.get(null)
    }
    private fun getter(owner: String, name: String) = method(owner, name, 0)
    private fun call(m: Method, receiver: Any? = null, vararg args: Any?): Any? =
        try { m.invoke(receiver, *args) } catch (e: InvocationTargetException) { throw e.targetException }

    val unit: Any = singleton("kotlin.Unit")
    private val base = singleton(HostClasses.Compose.MODIFIER)
    private val material = singleton(HostClasses.Compose.MATERIAL_THEME)
    private val arrangement = singleton(HostClasses.Compose.ARRANGEMENT)
    private val alignment = singleton(HostClasses.Compose.ALIGNMENT)
    private val top = getter(HostClasses.Compose.ARRANGEMENT, "getTop").invoke(arrangement)
    private val start = getter(HostClasses.Compose.ARRANGEMENT, "getStart").invoke(arrangement)
    private val alignStart = alignment.javaClass.getMethod("getStart").invoke(alignment)
    private val center = alignment.javaClass.getMethod("getCenterVertically").invoke(alignment)
    private val startGroup = cls(HostClasses.Compose.COMPOSER).getMethod("startReplaceableGroup", Int::class.javaPrimitiveType)
    private val endGroup = cls(HostClasses.Compose.COMPOSER).getMethod("endReplaceableGroup")
    private val row = method(HostClasses.Compose.ROW_KT, "Row", 7)
    private val column = method(HostClasses.Compose.COLUMN_KT, "Column", 7)
    private val spacer = method(HostClasses.Compose.SPACER_KT, "Spacer", 3)
    private val width = method(HostClasses.Compose.SIZE_KT, "width", 2)
    private val height = method(HostClasses.Compose.SIZE_KT, "height", 2)
    private val size = method(HostClasses.Compose.SIZE_KT, "size", 2, second = "float")
    private val fill = method(HostClasses.Compose.SIZE_KT, "fillMaxWidth\$default", 4)
    private val pad = method(HostClasses.Compose.PADDING_KT, "padding", 5)
    private val clip = method(HostClasses.Compose.CLIP_KT, "clip", 2)
    private val border = method(HostClasses.Compose.BORDER_KT, "border", 4, HostClasses.Compose.MODIFIER, third = "long")
    private val background = method(HostClasses.Compose.BACKGROUND_KT, "background-bw27NRU\$default", 5)
    private val clickable = method(HostClasses.Compose.CLICKABLE_KT, "clickable-O2vRcR0\$default", 9)
    private val shape = method(HostClasses.Host.SHAPE_KT, "getRoundedShape", 0)
    private val divider = method(HostClasses.Host.DIVIDER_KT, "SimpleDivider", 5)
    private val udpInt = method(HostClasses.Host.UNIT_EXT_KT, "getUdp", 1, "int")
    private val udpDouble = method(HostClasses.Host.UNIT_EXT_KT, "getUdp", 1, "double")
    private val scrollState = method("androidx.compose.foundation.ScrollKt", "rememberScrollState", 4)
    private val scroll = method("androidx.compose.foundation.ScrollKt", "verticalScroll\$default", 7)
    private val navPadding = method("androidx.compose.foundation.layout.WindowInsetsPadding_androidKt", "navigationBarsPadding", 1)
    private val weight = cls("androidx.compose.foundation.layout.RowScope").getMethod(
        "weight", cls(HostClasses.Compose.MODIFIER), Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    private val colors = method(HostClasses.Compose.MATERIAL_THEME, "getColorScheme", 2)
    private val typography = method(HostClasses.Compose.MATERIAL_THEME, "getTypography", 2)
    private val labelStyle = getter("androidx.compose.material3.Typography", "getLabelLarge")
    private val cancelForeground = method(HostClasses.Host.THEME_KT, "getOnBackgroundVariant", 1)
    private val cancelStyle = getter("androidx.compose.material3.Typography", "getBodyLarge")
    private val foreground = getter("androidx.compose.material3.ColorScheme", "getOnBackground")
    private val arrowColor = getter("androidx.compose.material3.ColorScheme", "getSurfaceContainerHighest")
    private val borderColor = method(HostClasses.Host.THEME_KT, "getBorderVariant", 1)
    private val pageColor = method(HostClasses.Host.THEME_KT, "getBackgroundAuto", 1)
    private val icon = method(HostClasses.Compose.ICON_KT, "Icon", 7, HostClasses.Compose.IMAGE_VECTOR)
    private val text = method(HostClasses.Compose.TEXT_KT, "Text", 22, "java.lang.String", second = HostClasses.Compose.MODIFIER)
    private val outlined = singleton("androidx.compose.material.icons.Icons\$Outlined")
    private val vectors = listOf("Image", "Link", "AutoAwesome", "Download", "RestartAlt", "DeleteOutline", "Build", "Cancel")
        .associateWith { name ->

            val m = runCatching { method("androidx.compose.material.icons.outlined.${name}Kt", "get$name", 1) }
                .getOrElse { method("androidx.compose.material.icons.outlined.ImageKt", "getImage", 1) }
            call(m, null, outlined)!!
        }
    private val nextIcon = call(method(HostClasses.Compose.NAVIGATE_NEXT_ICON, "getNavigateNext", 1),
        null, singleton("androidx.compose.material.icons.Icons\$AutoMirrored\$Filled"))!!
    private val hide = cls("androidx.compose.material3.SheetState").getMethod("hide", cls("kotlin.coroutines.Continuation"))
    private val isVisible = getter("androidx.compose.material3.SheetState", "isVisible")
    private val launch = method("kotlinx.coroutines.BuildersKt", "launch\$default", 6)
    private val completion = cls("kotlinx.coroutines.Job").getMethod("invokeOnCompletion", cls(HostClasses.Kotlin.FUNCTION1))

    private fun function(arity: Int, name: String, block: (Array<Any?>) -> Any?): Any =
        Proxy.newProxyInstance(loader, arrayOf(cls("kotlin.jvm.functions.Function$arity"))) { proxy, m, args ->
            when (m.name) {
                "invoke" -> block(args ?: emptyArray())
                "toString" -> "ReaMicro-$name"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }

    private fun udp(value: Int) = call(udpInt, null, value) as Float
    private fun padding(mod: Any, left: Int, top: Int, right: Int, bottom: Int) =
        call(pad, null, mod, udp(left), udp(top), udp(right), udp(bottom))!!
    private fun fillWidth(mod: Any) = call(fill, null, mod, 0f, 1, null)!!
    private fun gap(value: Int, composer: Any, vertical: Boolean = false) {
        call(spacer, null, call(if (vertical) height else width, null, base, udp(value)), composer, 0)
    }
    private fun contentColumn(mod: Any, composer: Any, name: String, block: (Any) -> Unit) {
        val content = function(3, name) { args -> block(args[1]!!); unit }
        call(column, null, mod, top, alignStart, content, composer, 0, 0)
    }
    private fun card(composer: Any, block: (Any) -> Unit) {
        val scheme = call(colors, material, composer, 0)!!
        val rounded = call(shape)!!
        var mod = call(clip, null, fillWidth(base), rounded)!!
        mod = call(border, null, mod, call(udpDouble, null, .8), call(borderColor, null, scheme), rounded)!!
        mod = call(background, null, mod, call(pageColor, null, scheme), null, 2, null)!!
        contentColumn(mod, composer, "ImageActionCard", block)
    }
    private fun actionRow(action: BookImageSheetActions.Action, composer: Any, onAction: () -> Unit) {
        val click = function(0, action.id.name) { onAction(); unit }
        var mod = call(clickable, null, fillWidth(base), null, null, false, null, null, click, 28, null)!!
        mod = padding(mod, 18, 16, 12, 16)
        val content = function(3, "ImageActionRow") { args ->
            val rowScope = args[0]!!
            val c = args[1]!!
            val scheme = call(colors, material, c, 0)!!
            val fg = if (action.id == BookImageSheetActions.Id.Cancel) call(cancelForeground, null, scheme) as Long else call(foreground, scheme) as Long
            call(icon, null, vectors.getValue(action.icon), null, call(size, null, base, udp(20)), fg, c, 0, 0)
            gap(16, c)
            val weighted = call(weight, rowScope, base, 1f, true)
            val type = call(typography, material, c, 0)!!
            val style = call(if (action.id == BookImageSheetActions.Id.Cancel) cancelStyle else labelStyle, type)

            call(text, null, action.label, weighted, fg, null, 0L, null, null, null, 0L, null, null,
                0L, 0, false, 0, 0, null, style, c, 0, 0, 131064)

            call(icon, null, nextIcon, null, padding(base, 0, 2, 0, 0), call(arrowColor, scheme), c, 0, 0)
            unit
        }
        call(row, null, mod, start, center, content, composer, 0, 0)
    }

    fun render(composer: Any, actions: List<BookImageSheetActions.Action>, onAction: (BookImageSheetActions.Id) -> Unit) {
        call(startGroup, composer, 0x524d4901)
        try {
            val state = call(scrollState, null, 0, composer, 0, 1)
            var mod = call(scroll, null, fillWidth(base), state, false, null, false, 14, null)!!
            mod = call(navPadding, null, padding(mod, 20, 0, 20, 16))!!
            contentColumn(mod, composer, "UnifiedImageSheet") { c ->
                card(c) { inner ->
                    val main = actions.filterNot { it.id == BookImageSheetActions.Id.Cancel }
                    main.forEachIndexed { index, action ->
                        actionRow(action, inner) { onAction(action.id) }
                        if (index != main.lastIndex) {
                            call(divider, null, padding(base, 54, 0, 0, 0), 0L, inner, 0, 2)
                        }
                    }
                }
                gap(16, c, vertical = true)
                card(c) { inner ->
                    actionRow(actions.last(), inner) { onAction(BookImageSheetActions.Id.Cancel) }
                }
            }
        } finally { call(endGroup, composer) }
    }

    fun hide(scope: Any, sheetState: Any, finished: (Boolean, Throwable?) -> Unit) {
        val suspendHide = function(2, "HideNativeImageSheet") { args -> call(hide, sheetState, args[1]) }
        val job = call(launch, null, scope, null, null, suspendHide, 3, null)!!
        call(completion, job, function(1, "AfterNativeImageSheetHidden") { args ->
            val error = args.firstOrNull() as? Throwable
            finished(error == null && call(isVisible, sheetState) == false, error)
            unit
        })
    }
}
