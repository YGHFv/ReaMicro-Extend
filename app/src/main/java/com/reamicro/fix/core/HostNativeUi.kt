package com.reamicro.fix.core

/** Calls the current host's verified Compose contract without emulating an older ABI. */
class HostNativeUi(private val loader: ClassLoader) {
    private val ui get() = requireNotNull(HostUiBindings.current) { "Native UI contract unavailable" }
        .also { check(it.loader === loader) }
    private fun boundValue(role: String, vararg args: Any?): Any = requireNotNull(ui.call(role, *args))
    fun dp(value: Int): Float = (ui.call("dpInt", value) as Number).toFloat()
    fun dp(value: Double): Float = (ui.call("dpDouble", value) as Number).toFloat()
    fun mutableState(value: Any?): Any = boundValue("state", value)
    fun lambda(key: Int, callback: Any): Any = ui.lambda(key, callback)
    fun theme(composer: Any, property: String): Any = boundValue(when (property) {
        "getColorScheme" -> "colorScheme"
        "getTypography" -> "typography"
        else -> error("Unknown theme property $property")
    }, composer)
    fun modifier(): Any = ui.value("modifier")
    fun top(): Any = ui.value("top")
    fun start(): Any = ui.value("start")
    fun center(): Any = ui.value("center")
    fun centerHorizontally(): Any = ui.alignment(false)
    fun centerVertically(): Any = ui.alignment(true)
    fun emptySlot(): Any = ui.emptySlot()
    fun fillWidth(base: Any): Any = boundValue("fillWidth", base, 1f)
    fun fillSize(base: Any): Any = boundValue("fillSize", base)
    fun height(base: Any, value: Float): Any = boundValue("height", base, value)
    fun width(base: Any, value: Float): Any = boundValue("width", base, value)
    fun padding(base: Any, values: Any): Any = boundValue("paddingValues", base, values)
    fun padding(base: Any, start: Float, top: Float, end: Float, bottom: Float): Any =
        boundValue("padding", base, start, top, end, bottom)
    fun background(base: Any, color: Long): Any = boundValue("background", base, color, ui.value("rectangle"))
    fun spacedBy(value: Float): Any = ui.spacing(value)
    fun clickable(base: Any, callback: Any): Any = boundValue("clickable", base, true, null, null, callback, 6)
    fun combinedClickable(base: Any, onLongClick: Any, onClick: Any): Any =
        boundValue("combinedClickable", base, null, null, onLongClick, onClick, 0)
    fun text(
        text: String,
        composer: Any,
        color: Long,
        style: Any?,
        defaultMask: Int,
        modifier: Any? = null,
        fontFamily: Any? = null,
        textAlign: Any? = null,
        overflow: Int = 0,
        maxLines: Int = 0,
        minLines: Int = 0,
    ) {
        // R8 removes unused parameters, while the Kotlin default-mask bits keep their source positions.
        ui.call("text", text, modifier, color, 0L, null, fontFamily, 0L, null, textAlign,
            0L, overflow, false, maxLines, minLines, null, style, composer, 0, 0, defaultMask)
    }
    fun listItem(
        headline: Any,
        modifier: Any,
        composer: Any,
        colors: Any,
        defaultMask: Int,
        supporting: Any? = null,
        trailing: Any? = null,
    ) {
        ui.call("listItem", headline, modifier, supporting, null, trailing, colors,
            0f, composer, 196614, defaultMask)
    }
    fun listColors(composer: Any): Any = boundValue("listColors", ui.value("listColorBase"), composer)
    fun settingsArrow(): Any = ui.value("settingsArrow")
    fun settingsEntry(headline: Any, onClick: Any, composer: Any) {
        val shape = ui.value("settingsShape")
        val scheme = theme(composer, "getColorScheme")
        val border = ui.call("borderVariant", scheme) as Long
        val clipped = boundValue("clip", modifier(), shape)
        val outlined = boundValue("border", clipped, dp(ui.decimal("settingsBorderWidth")), border, shape)
        val clickable = boundValue("clickable", outlined, false, null, null, onClick, ui.number("settingsClickMask"))
        val modifier = fillWidth(height(clickable, dp(ui.number("settingsHeight"))))
        val colors = boundValue("listColors", ui.call("backgroundAuto", scheme), composer)
        ui.call("listItem", headline, modifier, null, null, settingsArrow(), colors,
            dp(ui.number("settingsElevation")), composer, ui.number("settingsListFlags"), ui.number("settingsListMask"))
    }
    fun settingsSwitch(checked: Boolean, onChanged: Any, composer: Any) {
        val content = ui.constructor("settingsSwitch").newInstance(onChanged, checked)
        ui.method("settingsSwitchBody").invoke(content, composer, 0)
    }
    fun switchColors(thumb: Long, track: Long, border: Long, composer: Any): Any =
        boundValue("switchColors", thumb, track, border, composer)
    fun lazyColumn(modifier: Any, arrangement: Any, content: Any, composer: Any) {
        ui.call("lazyColumn", modifier, null, null, arrangement, null, null, false, null, content, composer, 0, 494)
    }
    fun lazyRow(modifier: Any, content: Any, composer: Any) {
        ui.call("lazyRow", modifier, null, null, null, null, null, false, null, content, composer, 0, 254)
    }
    fun weight(scope: Any, base: Any, value: Float = 1f): Any = requireNotNull(ui.instance("weight", scope, base, value))
    fun spacer(modifier: Any, composer: Any) { ui.call("box", modifier, composer, 0) }
    fun emptyBox(modifier: Any, composer: Any) { ui.call("box", modifier, composer, 0) }
    fun addItem(scope: Any, key: Any?, content: Any) { ui.instance("item", scope, key, null, content) }
    fun scaffold(topBar: Any, background: Long, content: Any, composer: Any) {
        ui.call("scaffold", null, topBar, null, null, null, 0, background, 0L, null, content, composer, 805306416, 445)
    }
    fun backHandler(enabled: Boolean, callback: Any, composer: Any) { ui.call("back", enabled, callback, composer, 0, 0) }
    fun divider(base: Any, composer: Any) { ui.call("divider", base, 0L, composer, 0, 2) }
    fun card(base: Any, color: Long, border: Long, width: Float): Any {
        val shape = ui.value("settingsShape")
        return fillWidth(boundValue("background", ui.call("border", ui.call("clip", base, shape), width, border, shape), color, ui.value("rectangle")))
    }
    fun column(modifier: Any, arrangement: Any, alignment: Any, content: Any, composer: Any) {
        ui.node(false, modifier, arrangement, alignment, content, composer)
    }
    fun row(modifier: Any, content: Any, composer: Any, arrangement: Any? = null, alignment: Any? = null) {
        ui.node(true, modifier, arrangement, alignment, content, composer)
    }
}
