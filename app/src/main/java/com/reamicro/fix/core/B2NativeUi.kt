package com.reamicro.fix.core

import com.reamicro.fix.xposed.XposedHelpers
import java.lang.reflect.Method

/** 保留 B2 调用接口，由逐项核实的原生 ABI 分派 B2/B4，不推断参数顺序。 */
class B2NativeUi(private val loader: ClassLoader) {
    private val methods = HashMap<String, Method>()

    private fun type(role: String): Class<*> {
        check(HostNativeUiAbi.supports(HostSymbols.profileId)) { "Unverified native UI host" }
        return loader.hostClass("ReaMicroHost.$role")
    }

    private fun static(role: String, name: String, vararg args: Any?): Any? {
        val key = "$role#$name/${args.size}"
        val method = methods.getOrPut(key) {
            val actual = HostNativeUiAbi.method(HostSymbols.profileId, role, name)
            type(role).declaredMethods.single { it.name == actual && it.parameterCount == args.size }
                .apply { isAccessible = true }
        }
        return method.invoke(null, *args)
    }

    private fun field(role: String, name: String): Any =
        requireNotNull(type(role).getDeclaredField(name).apply { isAccessible = true }.get(null))

    fun dp(value: Int): Float = (static("Unit", "g", value) as Number).toFloat()
    fun dp(value: Double): Float = (static("Unit", "e", value) as Number).toFloat()

    fun mutableState(value: Any?): Any = requireNotNull(static("StateFactory", "d", value))

    fun lambda(key: Int, callback: Any): Any =
        type("ComposableLambda").getDeclaredConstructor(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Any::class.java)
            .newInstance(key, true, callback)

    fun theme(composer: Any, property: String): Any {
        val local = loader.hostClass("androidx.compose.material3.MaterialThemeKt").hostField("_localMaterialTheme").get(null)
        val values = XposedHelpers.callMethod(composer, "consume", local)
        return requireNotNull(XposedHelpers.callMethod(values, property))
    }

    fun modifier(): Any = field("Modifier", "b")
    fun top(): Any = field("Arrangement", "c")
    fun start(): Any = field("Alignment", HostNativeUiAbi.alignment(HostSymbols.profileId, "start"))
    fun center(): Any = field("Alignment", HostNativeUiAbi.alignment(HostSymbols.profileId, "center"))
    fun centerHorizontally(): Any = field(
        "Alignment",
        if (HostSymbols.profileId == "2.3.3-b4") "o" else HostNativeUiAbi.alignment(HostSymbols.profileId, "center"),
    )
    fun centerVertically(): Any = field("Alignment", HostNativeUiAbi.alignment(HostSymbols.profileId, "centerVertically"))
    fun emptySlot(): Any = field("EmptySlot", "a")
    fun fillWidth(base: Any): Any = requireNotNull(static("Size", "d", base, 1f))
    fun fillSize(base: Any): Any = requireNotNull(static("Size", "c", base))
    fun height(base: Any, value: Float): Any = requireNotNull(static("Size", "f", base, value))
    fun width(base: Any, value: Float): Any = requireNotNull(static("Size", "n", base, value))
    fun padding(base: Any, values: Any): Any = requireNotNull(static("Padding", "j", base, values))
    fun padding(base: Any, start: Float, top: Float, end: Float, bottom: Float): Any =
        requireNotNull(static("Padding", "n", base, start, top, end, bottom))
    fun background(base: Any, color: Long): Any =
        requireNotNull(static("Background", "e", base, color, field("RectangleShape", "a")))

    fun spacedBy(value: Float): Any {
        val aligner = type("SpacingAlignment").getDeclaredConstructor(Byte::class.javaPrimitiveType).newInstance(16.toByte())
        return type("SpacedArrangement").declaredConstructors.single { it.parameterCount == 3 }
            .newInstance(value, true, aligner)
    }

    fun clickable(base: Any, callback: Any): Any =
        requireNotNull(static("Clickable", "h", base, true, null, null, callback, 6))

    fun combinedClickable(base: Any, onLongClick: Any, onClick: Any): Any =
        requireNotNull(static("Clickable", "combined", base, null, null, onLongClick, onClick, 0))

    fun text(legacy: Array<out Any?>) {
        val translated = HostUiArguments.b2Text(legacy)
        static("Text", "b", *translated)
    }

    fun listItem(legacy: Array<out Any?>) {
        // overlineContent 与 shadowElevation 被内联；调用方均未显式提供这两项。
        require(legacy.size == 12 && legacy[2] == null && legacy[8] == 0f)
        static("ListItem", "a", *legacy.filterIndexed { index, _ -> index != 2 && index != 8 }.toTypedArray())
    }

    fun listColors(composer: Any): Any = requireNotNull(static("ListItemColors", "a",
        if (HostSymbols.profileId == "2.3.3-b4") loader.hostClass("androidx.compose.ui.graphics.Color")
            .hostField("Transparent").getLong(null) else 0L, composer))

    private val settingsSwitchClass by lazy { type("SettingsSwitch") }
    private val settingsSwitchConstructor by lazy {
        settingsSwitchClass.getDeclaredConstructor(
            loader.hostClass("kotlin.jvm.functions.Function1"), Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
    }
    private val settingsSwitchBranch by lazy {
        settingsSwitchClass.getDeclaredField("a").apply { isAccessible = true }
    }
    private val settingsSwitchContent by lazy {
        settingsSwitchClass.getDeclaredMethod("C", Any::class.java, Any::class.java)
            .apply { isAccessible = true }
    }

    fun settingsSwitch(checked: Boolean, onChanged: Any, composer: Any) {
        // 复用阅微设置的缩放、主题颜色及动画，拒绝进入另一听书分支。
        val content = settingsSwitchConstructor.newInstance(onChanged, checked)
        check(settingsSwitchBranch.getByte(content).toInt() == 1) { "Unverified native settings switch branch" }
        settingsSwitchContent.invoke(content, composer, 0)
    }

    fun switchColors(thumb: Long, track: Long, border: Long, composer: Any): Any =
        requireNotNull(static("SwitchColors", "a", thumb, track, border, composer))

    fun switch(legacy: Array<out Any?>) {
        require(legacy.size == 10 && legacy[3] == null && legacy[6] == null)
        static("Switch", "a", *legacy.filterIndexed { index, _ -> index != 3 && index != 6 }.toTypedArray())
    }

    fun lazyColumn(modifier: Any, arrangement: Any, content: Any, composer: Any) {
        static("LazyDsl", "a", modifier, null, null, arrangement, null, null, false, null, content, composer, 0, 494)
    }

    fun lazyRow(modifier: Any, content: Any, composer: Any) {
        check(HostSymbols.profileId == "2.3.3-b4") { "Unverified native lazy row host" }
        static("LazyDsl", "b", modifier, null, null, null, null, null, false, null, content, composer, 0, 254)
    }

    fun weight(scope: Any, base: Any, value: Float = 1f): Any {
        check(HostSymbols.profileId == "2.3.3-b4") { "Unverified native row weight host" }
        return requireNotNull(type("RowScopeInstance")
            .getDeclaredMethod("b", loader.hostClass("androidx.compose.ui.Modifier"), Float::class.javaPrimitiveType)
            .invoke(scope, base, value))
    }

    fun spacer(modifier: Any, composer: Any) {
        check(HostSymbols.profileId == "2.3.3-b4") { "Unverified native spacer host" }
        loader.hostClass("ReaMicroHost.Spacer")
            .getDeclaredMethod("b", loader.hostClass("androidx.compose.runtime.Composer"),
                loader.hostClass("androidx.compose.ui.Modifier"))
            .invoke(null, composer, modifier)
    }

    fun emptyBox(modifier: Any, composer: Any) {
        loader.hostClass("androidx.compose.foundation.layout.BoxKt")
            .hostMethod("Box", loader.hostClass("androidx.compose.ui.Modifier"),
                loader.hostClass("androidx.compose.runtime.Composer"),
                Int::class.javaPrimitiveType).invoke(null, modifier, composer, 0)
    }

    fun addItem(scope: Any, key: Any?, content: Any) {
        val name = if (HostSymbols.profileId == "2.3.3-b4") "n" else "m"
        val method = type("LazyListScope").declaredMethods.single { it.name == name && it.parameterCount == 3 }
        method.invoke(scope, key, null, content)
    }

    fun scaffold(topBar: Any, background: Long, content: Any, composer: Any) {
        static("Scaffold", "J", null, topBar, null, null, null, 0, background, 0L, null, content, composer, 805306416, 445)
    }

    fun backHandler(enabled: Boolean, callback: Any, composer: Any) {
        static("BackHandler", "a", enabled, callback, composer, 0, 0)
    }

    fun aboutEntry(callback: Any, composer: Any) {
        static("AboutEntry", "a", callback, composer, 0)
    }

    fun divider(base: Any, composer: Any) {
        static("Divider", "c", base, 0L, composer, 0, 2)
    }

    fun card(base: Any, color: Long, border: Long, width: Float): Any {
        val shape = field("CardShape", "b")
        val clipped = requireNotNull(static("Clip", "n", base, shape))
        val bordered = requireNotNull(static("Border", "a", clipped, width, border, shape))
        return fillWidth(requireNotNull(static("Background", "e", bordered, color, field("RectangleShape", "a"))))
    }

    fun column(modifier: Any, arrangement: Any, alignment: Any, content: Any, composer: Any) {
        val policy = requireNotNull(static("ColumnPolicy", "a", arrangement, alignment, composer, 0))
        XposedHelpers.callMethod(composer, "b0", 0x524d7031)
        val hash = java.lang.Long.hashCode(composer.javaClass.getDeclaredField("T").getLong(composer))
        val locals = XposedHelpers.callMethod(composer, "l")
        val materialized = requireNotNull(static("Materialize", "h", composer, modifier))
        XposedHelpers.callMethod(composer, "f0")
        if (composer.javaClass.getDeclaredField("S").getBoolean(composer)) {
            XposedHelpers.callMethod(composer, "k", field("LayoutNode", "c0"))
        } else {
            XposedHelpers.callMethod(composer, "o0")
        }
        static("Updater", "d", field("NodeSetters", "g"), composer, policy)
        static("Updater", "d", field("NodeSetters", "f"), composer, locals)
        static("Updater", "d", field("NodeSetters", "h"), composer, hash)
        static("Updater", "c", composer)
        static("Updater", "d", field("NodeSetters", "e"), composer, materialized)
        invokeLayoutContent(content, field("ColumnScope", "a"), composer)
        XposedHelpers.callMethod(composer, "p", true)
        XposedHelpers.callMethod(composer, "p", false)
    }

    fun row(modifier: Any, content: Any, composer: Any, arrangement: Any? = null, alignment: Any? = null) {
        check(HostSymbols.profileId == "2.3.3-b4") { "Unverified native row host" }
        val policy = loader.hostClass("androidx.compose.foundation.layout.RowKt")
            .hostMethod("rowMeasurePolicy", type("ArrangementHorizontal"), type("AlignmentVertical"),
                loader.hostClass("androidx.compose.runtime.Composer"), Int::class.javaPrimitiveType)
            .invoke(null, arrangement ?: field("Arrangement", "a"), alignment ?: centerVertically(), composer, 48)
        // 复用宿主 Row 的节点协议；两列使用真实 weight modifier，不借用置顶/想法回调。
        XposedHelpers.callMethod(composer, "b0", 0x524d7032)
        val hash = java.lang.Long.hashCode(composer.javaClass.getDeclaredField("T").getLong(composer))
        val locals = XposedHelpers.callMethod(composer, "l")
        val materialized = requireNotNull(static("Materialize", "h", composer, modifier))
        XposedHelpers.callMethod(composer, "f0")
        if (composer.javaClass.getDeclaredField("S").getBoolean(composer)) {
            XposedHelpers.callMethod(composer, "k", field("LayoutNode", "c0"))
        } else {
            XposedHelpers.callMethod(composer, "o0")
        }
        static("Updater", "d", field("NodeSetters", "g"), composer, policy)
        static("Updater", "d", field("NodeSetters", "f"), composer, locals)
        static("Updater", "d", field("NodeSetters", "h"), composer, hash)
        static("Updater", "c", composer)
        static("Updater", "d", field("NodeSetters", "e"), composer, materialized)
        invokeLayoutContent(content, field("RowScopeInstance", "a"), composer)
        XposedHelpers.callMethod(composer, "p", true)
        XposedHelpers.callMethod(composer, "p", false)
    }

    private fun invokeLayoutContent(content: Any, scope: Any, composer: Any) {
        if (HostSymbols.profileId == "2.3.3-b4") {
            // 原生 ComposableLambda 同时实现多个 Function 接口，invoke 必须按 Function3 的 r 分派。
            loader.hostClass("kotlin.jvm.functions.Function3").hostMethod(
                "invoke", Any::class.java, Any::class.java, Any::class.java,
            ).invoke(content, scope, composer, 0)
        } else XposedHelpers.callMethod(content, "invoke", scope, composer, 0)
    }
}
