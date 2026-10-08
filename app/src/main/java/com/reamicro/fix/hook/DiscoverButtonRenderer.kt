package com.reamicro.fix.hook

import com.reamicro.fix.hook.discover.DiscoverIcon
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.xposed.XposedBridge
import java.lang.reflect.Method

@Volatile private var cachedDiscoverIcon: DiscoverIcon? = null

internal fun renderDiscoverButton(
    classLoader: ClassLoader,
    rowScope: Any,
    composer: Any,
    onHostRowClick: () -> Unit,
    onDiscoverClick: () -> Unit,
) {
    val hook = ReaMicroSettingsHook.activeInstanceOrNull()
    if (hook == null) {
        XposedBridge.log("$LOG_PREFIX discover button skipped: settings hook instance is NULL")
        return
    }
    runCatching {
        with(hook) { renderCommunityRow(rowScope, composer, onHostRowClick, onDiscoverClick) }
    }.onFailure {
        XposedBridge.logAlways("$LOG_PREFIX discover button failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.renderCommunityRow(
    rowScope: Any,
    composer: Any,
    onHostRowClick: () -> Unit,
    onDiscoverClick: () -> Unit,
) {
    val icon: Any? = runCatching { (cachedDiscoverIcon ?: DiscoverIcon(classLoader).also { cachedDiscoverIcon = it }).imageVectorOrNull() }.getOrNull()

    val leftSource = sharedInteractionSource(CELL_LEFT)
    val rightSource = sharedInteractionSource(CELL_RIGHT)

    renderCellArrow(composer, leftSource, "ReaMicroLeftCellArrow", CELL_LEFT_ARROW_END_PADDING_DP, onHostRowClick)

    renderVerticalDivider(composer)

    if (icon != null) renderDiscoverIcon(icon, composer, rightSource, onDiscoverClick)
    renderDiscoverLabel(
        DISCOVER_LABEL,
        textNodeCellHeight(
            paddingSides(
                discoverClickable(clickableBase(), "ReaMicroDiscoverLabel", composer, rightSource, onDiscoverClick),
                start = 0,
                top = CELL_VERTICAL_PADDING_DP,
                end = 0,
                bottom = CELL_VERTICAL_PADDING_DP,
            ),
        ),
        composer,
    )
    renderDiscoverFiller(rowScope, composer, rightSource, onDiscoverClick)

    renderCellArrow(composer, rightSource, "ReaMicroDiscoverArrow", CELL_RIGHT_ARROW_END_PADDING_DP, onDiscoverClick)
}

private fun ReaMicroSettingsHook.renderCellArrow(
    composer: Any,
    interactionSource: Any?,
    clickName: String,
    trailingPaddingDp: Int,
    onClick: () -> Unit,
) {
    val arrow = navigateNextImageVector() ?: return
    val modifier = paddingSides(
        discoverClickable(clickableBase(), clickName, composer, interactionSource, onClick),
        start = 0,
        top = CELL_VERTICAL_PADDING_DP,
        end = trailingPaddingDp,
        bottom = CELL_VERTICAL_PADDING_DP,
    )
    iconVectorMethod().invoke(
        null,
        arrow,
        null,
        modifier,
        colorScheme(composer).longMethod(SURFACE_CONTAINER_HIGHEST_METHOD),
        composer,
        ICON_CHANGED_MASK,
        ICON_DEFAULT_MASK,
    )
}

private fun ReaMicroSettingsHook.navigateNextImageVector(): Any? =
    runCatching {
        method(NAVIGATE_NEXT_ICON_CLASS, NAVIGATE_NEXT_METHOD, NAVIGATE_NEXT_PARAMETER_COUNT).invoke(
            null,
            staticObject(ICONS_AUTO_MIRRORED_FILLED_OBJECT, "INSTANCE"),
        )
    }.getOrNull()

private var cachedIconVectorMethod: Method? = null

private fun ReaMicroSettingsHook.iconVectorMethod(): Method =
    cachedIconVectorMethod ?: synchronized(DiscoverImageMethodLock) {
        cachedIconVectorMethod ?: cls(ICON_KT_CLASS).declaredMethods.firstOrNull {
            it.name == ICON_METHOD &&
                it.parameterTypes.size == ICON_PARAMETER_COUNT &&
                it.parameterTypes.firstOrNull()?.name == IMAGE_VECTOR_CLASS
        }?.apply { isAccessible = true }
            ?.also { cachedIconVectorMethod = it }
            ?: error("$ICON_KT_CLASS.$ICON_METHOD ImageVector overload not found")
    }

private fun ReaMicroSettingsHook.renderVerticalDivider(composer: Any) {
    val width = method(SIZE_KT_CLASS, WIDTH_METHOD, SIZE_PARAMETER_COUNT)
        .invoke(null, modifierInstance(), udp(DIVIDER_WIDTH_DP))
    val height = method(SIZE_KT_CLASS, HEIGHT_METHOD, SIZE_PARAMETER_COUNT)
        .invoke(null, width, udp(DIVIDER_HEIGHT_DP))
    val colored = method(BACKGROUND_KT_CLASS, BACKGROUND_DEFAULT_METHOD, BACKGROUND_PARAMETER_COUNT).invoke(
        null,
        height,
        borderVariant(composer),
        null,
        BACKGROUND_SHAPE_DEFAULT_MASK,
        null,
    )
    method(BOX_KT_CLASS, BOX_METHOD, BOX_PARAMETER_COUNT).invoke(null, colored, composer, 0)
}

private fun ReaMicroSettingsHook.borderVariant(composer: Any): Long =
    method(THEME_KT_CLASS, BORDER_VARIANT_METHOD, 1).invoke(null, colorScheme(composer)) as Long

internal fun ReaMicroSettingsHook.leftCellIconModifier(composer: Any, onClick: () -> Unit): Any =
    sizedSquare(
        paddingSides(
            paddingSides(
                discoverClickable(
                    clickableBase(),
                    "ReaMicroLeftCellIcon",
                    composer,
                    sharedInteractionSource(CELL_LEFT),
                    onClick,
                ),
                start = CELL_OUTER_PADDING_DP,
                top = CELL_VERTICAL_PADDING_DP,
                end = 0,
                bottom = CELL_VERTICAL_PADDING_DP,
            ),
            start = 0,
            top = 0,
            end = ICON_LABEL_GAP_PADDING_DP,
            bottom = 0,
        ),
        DISCOVER_ICON_SIZE_DP,
    )

internal fun ReaMicroSettingsHook.leftCellLabelModifier(composer: Any, onClick: () -> Unit): Any =
    textNodeCellHeight(
        paddingSides(
            discoverClickable(
                clickableBase(),
                "ReaMicroLeftCellLabel",
                composer,
                sharedInteractionSource(CELL_LEFT),
                onClick,
            ),
            start = 0,
            top = CELL_VERTICAL_PADDING_DP,
            end = 0,
            bottom = CELL_VERTICAL_PADDING_DP,
        ),
    )

internal fun ReaMicroSettingsHook.leftCellSpacerModifier(
    rowScope: Any,
    composer: Any,
    onClick: () -> Unit,
): Any {
    val weighted = method(ROW_SCOPE_INSTANCE_CLASS, ROW_WEIGHT_METHOD, ROW_WEIGHT_PARAMETER_COUNT)
        .invoke(rowScope, modifierInstance(), 1f, true)
    val clickable = discoverClickable(
        weighted,
        "ReaMicroLeftCellSpacer",
        composer,
        sharedInteractionSource(CELL_LEFT),
        onClick,
    )
    return method(SIZE_KT_CLASS, HEIGHT_METHOD, SIZE_PARAMETER_COUNT).invoke(
        null,
        paddingSides(
            clickable,
            start = 0,
            top = CELL_VERTICAL_PADDING_DP,
            end = 0,
            bottom = CELL_VERTICAL_PADDING_DP,
        ),
        udp(DISCOVER_ICON_SIZE_DP),
    )
}

private fun ReaMicroSettingsHook.sizedSquare(base: Any, dp: Int): Any =
    method(SIZE_KT_CLASS, SIZE_METHOD, SIZE_PARAMETER_COUNT).invoke(null, base, udp(dp))

private fun ReaMicroSettingsHook.textNodeCellHeight(base: Any): Any {
    val fixed = method(SIZE_KT_CLASS, HEIGHT_METHOD, SIZE_PARAMETER_COUNT)
        .invoke(null, base, udp(DISCOVER_ICON_SIZE_DP))
    return method(SIZE_KT_CLASS, WRAP_CONTENT_HEIGHT_METHOD, WRAP_CONTENT_HEIGHT_PARAMETER_COUNT)
        .invoke(null, fixed, alignmentCenterVertically(), false)
}

internal fun ReaMicroSettingsHook.fillMaxWidthModifier(base: Any): Any =
    method(SIZE_KT_CLASS, FILL_MAX_WIDTH_DEFAULT_METHOD, FILL_MAX_WIDTH_DEFAULT_PARAMETER_COUNT)
        .invoke(null, base, 0f, FILL_MAX_WIDTH_DEFAULT_MASK, null)

private fun ReaMicroSettingsHook.renderDiscoverIcon(
    icon: Any,
    composer: Any,
    interactionSource: Any?,
    onDiscoverClick: () -> Unit,
) {

    val clickable = discoverClickable(clickableBase(), "ReaMicroDiscoverIcon", composer, interactionSource, onDiscoverClick)
    val padded = paddingSides(
        clickable,
        start = DIVIDER_SIDE_PADDING_DP,
        top = CELL_VERTICAL_PADDING_DP,
        end = ICON_LABEL_GAP_PADDING_DP,
        bottom = CELL_VERTICAL_PADDING_DP,
    )
    val sized = method(SIZE_KT_CLASS, SIZE_METHOD, SIZE_PARAMETER_COUNT).invoke(null, padded, udp(DISCOVER_ICON_SIZE_DP))

    imageVectorMethod().invoke(null, icon, "发现", sized, alignmentCenter(), hostContentScale(),
        1f, null, composer, IMAGE_CHANGED_MASK, IMAGE_CHANGED2_MASK)
}

private fun ReaMicroSettingsHook.hostContentScale(): Any =
    cachedHostContentScale ?: synchronized(DiscoverImageMethodLock) {
        cachedHostContentScale ?: staticObject(CONTENT_SCALE_COMPANION_CLASS, CONTENT_SCALE_CROP_FIELD)
            .also { cachedHostContentScale = it }
    }

@Volatile
private var cachedHostContentScale: Any? = null

private var cachedImageVectorMethod: Method? = null

private fun ReaMicroSettingsHook.imageVectorMethod(): Method =
    cachedImageVectorMethod ?: synchronized(DiscoverImageMethodLock) {
        cachedImageVectorMethod ?: cls(IMAGE_KT_CLASS).declaredMethods.firstOrNull {
            it.name == IMAGE_METHOD &&
                it.parameterTypes.size == IMAGE_PARAMETER_COUNT &&
                it.parameterTypes.firstOrNull()?.name == IMAGE_VECTOR_CLASS
        }?.apply { isAccessible = true }
            ?.also { cachedImageVectorMethod = it }
            ?: error("$IMAGE_KT_CLASS.$IMAGE_METHOD ImageVector overload not found")
    }

private val DiscoverImageMethodLock = Any()

private fun ReaMicroSettingsHook.renderDiscoverLabel(text: String, modifier: Any, composer: Any) {
    method(TEXT_KT_CLASS, TEXT_METHOD, TEXT_PARAMETER_COUNT).invoke(
        null,
        text,
        modifier,
        colorScheme(composer).longMethod("getOnBackground"),
        null,
        0L,
        null,
        null,
        null,
        0L,
        null,
        null,
        0L,
        0,
        false,
        1,
        1,
        null,
        typography(composer).method0("getTitleSmall"),
        composer,
        0,
        0,
        DISCOVER_LABEL_MASK,
    )
}

private fun ReaMicroSettingsHook.renderDiscoverFiller(
    rowScope: Any,
    composer: Any,
    interactionSource: Any?,
    onDiscoverClick: () -> Unit,
) {
    val weighted = method(ROW_SCOPE_INSTANCE_CLASS, ROW_WEIGHT_METHOD, ROW_WEIGHT_PARAMETER_COUNT)
        .invoke(rowScope, modifierInstance(), 1f, true)
    val clickable = discoverClickable(weighted, "ReaMicroDiscoverFiller", composer, interactionSource, onDiscoverClick)
    val padded = paddingSides(
        clickable,
        start = 0,
        top = CELL_VERTICAL_PADDING_DP,
        end = 0,
        bottom = CELL_VERTICAL_PADDING_DP,
    )
    val sized = method(SIZE_KT_CLASS, HEIGHT_METHOD, SIZE_PARAMETER_COUNT)
        .invoke(null, padded, udp(DISCOVER_ICON_SIZE_DP))
    method(SPACER_KT_CLASS, SPACER_METHOD, SPACER_PARAMETER_COUNT).invoke(null, sized, composer, 0)
}

private fun ReaMicroSettingsHook.discoverClickable(
    base: Any,
    name: String,
    composer: Any,
    interactionSource: Any?,
    onClick: () -> Unit,
): Any =
    method(CLICKABLE_KT_CLASS, CLICKABLE_DEFAULT_METHOD, CLICKABLE_DEFAULT_PARAMETER_COUNT).invoke(
        null,
        base,
        interactionSource,
        localIndication(composer),
        false,
        null,
        null,
        functionProxy(name, FUNCTION0_CLASS) {
            onClick()
            targetUnit()
        },
        CLICKABLE_DEFAULT_MASK,
        null,
    )

private fun ReaMicroSettingsHook.localIndication(composer: Any): Any? {
    if (localIndicationUnavailable) return null
    return runCatching {
        val local = method(INDICATION_KT_CLASS, LOCAL_INDICATION_GETTER, 0).invoke(null)
        method(COMPOSER_CLASS, COMPOSER_CONSUME_METHOD, 1).invoke(composer, local)
    }.onFailure {

        localIndicationUnavailable = true
        XposedBridge.logAlways("$LOG_PREFIX local indication unavailable: ${it.message}")
    }.getOrNull()
}

@Volatile
private var localIndicationUnavailable = false

private fun ReaMicroSettingsHook.sharedInteractionSource(cell: Int): Any? {
    cachedDiscoverInteractionSources.getOrNull(cell)?.let { return it }
    if (discoverInteractionSourceUnavailable) return null
    return synchronized(DiscoverImageMethodLock) {
        cachedDiscoverInteractionSources.getOrNull(cell)?.let { return@synchronized it }
        if (discoverInteractionSourceUnavailable) return@synchronized null
        runCatching {
            method(
                INTERACTION_SOURCE_KT_CLASS,
                INTERACTION_SOURCE_FACTORY_METHOD,
                INTERACTION_SOURCE_FACTORY_PARAMETER_COUNT,
            ).invoke(null)
        }.onFailure {

            discoverInteractionSourceUnavailable = true
            XposedBridge.logAlways("$LOG_PREFIX shared interaction source unavailable: ${it.message}")
        }.getOrNull()?.also { cachedDiscoverInteractionSources[cell] = it }
    }
}

private val cachedDiscoverInteractionSources = arrayOfNulls<Any>(2)

@Volatile
private var discoverInteractionSourceUnavailable = false

private fun ReaMicroSettingsHook.clickableBase(): Any =
    paddingSides(modifierInstance(), start = 0, top = 0, end = 0, bottom = 0)

private fun ReaMicroSettingsHook.paddingSides(base: Any, start: Int, top: Int, end: Int, bottom: Int): Any =
    method(PADDING_KT_CLASS, PADDING_SIDES_METHOD, PADDING_SIDES_PARAMETER_COUNT).invoke(
        null,
        base,
        udp(start),
        udp(top),
        udp(end),
        udp(bottom),
    )

private const val DISCOVER_LABEL = "发现"

private const val DISCOVER_ICON_SIZE_DP = 24

private const val DIVIDER_SIDE_PADDING_DP = 12

private const val CELL_VERTICAL_PADDING_DP = 16

private const val CELL_OUTER_PADDING_DP = 16

private const val CELL_LEFT_ARROW_END_PADDING_DP = 12

private const val CELL_RIGHT_ARROW_END_PADDING_DP = 16

private const val CELL_LEFT = 0
private const val CELL_RIGHT = 1

private const val ICON_LABEL_GAP_PADDING_DP = 12

private const val DIVIDER_WIDTH_DP = 0.8

private const val DIVIDER_HEIGHT_DP = 56

private const val IMAGE_CHANGED_MASK = 0x30
private const val IMAGE_CHANGED2_MASK = 0x78

private const val ICON_CHANGED_MASK = 0x30

private const val ICON_DEFAULT_MASK = 0x0

private const val ROW_WEIGHT_PARAMETER_COUNT = 3

private const val IMAGE_PARAMETER_COUNT = 10
private const val TEXT_PARAMETER_COUNT = 22

private const val DISCOVER_LABEL_MASK = 73720

private const val ICON_PARAMETER_COUNT = 7

private const val SPACER_PARAMETER_COUNT = 3

private const val BOX_PARAMETER_COUNT = 3

private const val BACKGROUND_PARAMETER_COUNT = 5

private const val BACKGROUND_SHAPE_DEFAULT_MASK = 2

private const val CLICKABLE_DEFAULT_PARAMETER_COUNT = 9

private const val CLICKABLE_DEFAULT_MASK = 28

private const val INDICATION_KT_CLASS = "androidx.compose.foundation.IndicationKt"
private const val LOCAL_INDICATION_GETTER = "getLocalIndication"

private const val COMPOSER_CONSUME_METHOD = "consume"

private const val INTERACTION_SOURCE_KT_CLASS = "androidx.compose.foundation.interaction.InteractionSourceKt"
private const val INTERACTION_SOURCE_FACTORY_METHOD = "MutableInteractionSource"
private const val INTERACTION_SOURCE_FACTORY_PARAMETER_COUNT = 0

private const val SIZE_PARAMETER_COUNT = 2

private const val NAVIGATE_NEXT_METHOD = "getNavigateNext"
private const val NAVIGATE_NEXT_PARAMETER_COUNT = 1

private const val ICONS_AUTO_MIRRORED_FILLED_OBJECT = "androidx.compose.material.icons.Icons\$AutoMirrored\$Filled"

private const val SURFACE_CONTAINER_HIGHEST_METHOD = "getSurfaceContainerHighest-0d7_KjU"

private const val IMAGE_KT_CLASS = "androidx.compose.foundation.ImageKt"
private const val IMAGE_METHOD = "Image"

private const val ICON_KT_CLASS = "androidx.compose.material3.IconKt"
private const val ICON_METHOD = "Icon-ww6aTOc"

private const val IMAGE_VECTOR_CLASS = "androidx.compose.ui.graphics.vector.ImageVector"
private const val ROW_SCOPE_INSTANCE_CLASS = "androidx.compose.foundation.layout.RowScopeInstance"
private const val ROW_WEIGHT_METHOD = "weight"

private const val CONTENT_SCALE_COMPANION_CLASS = "androidx.compose.ui.layout.ContentScale\$Companion"

private const val CONTENT_SCALE_CROP_FIELD = "Crop"

private const val PADDING_SIDES_METHOD = "padding-qDBjuR0"
private const val PADDING_SIDES_PARAMETER_COUNT = 5

private const val SIZE_METHOD = "size-3ABfNKs"

private const val WRAP_CONTENT_HEIGHT_METHOD = "wrapContentHeight"
private const val WRAP_CONTENT_HEIGHT_PARAMETER_COUNT = 3

private const val FILL_MAX_WIDTH_DEFAULT_METHOD = "fillMaxWidth\$default"
private const val FILL_MAX_WIDTH_DEFAULT_PARAMETER_COUNT = 4
private const val FILL_MAX_WIDTH_DEFAULT_MASK = 1
