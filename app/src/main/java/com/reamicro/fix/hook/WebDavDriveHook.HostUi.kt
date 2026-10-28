package com.reamicro.fix.hook

import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.B2NativeUi
import com.reamicro.fix.core.HostSymbols

import com.reamicro.fix.core.hostNameMatches
import com.reamicro.fix.core.typeMatches

import com.reamicro.fix.notification.setReaMicroSmallIcon

import android.app.Notification
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.View
import android.widget.Toast
import com.reamicro.fix.R
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.xposed.XposedBridge
import java.lang.reflect.Method
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.logging.logWebDav

internal fun WebDavDriveHook.renderOnlineCompletionUpdateRowFromLocalSheet(composer: Any?) {
    if (onlineCompletionUpdateRowInjecting.get() == true) return
    if (onlineCompletionCombinedGroupRowHooked) return
    val book = onlineCompletionLocalSheetBook.get()
        ?: onlineCompletionLastLocalSheetBookRef?.get()
        ?: run {
        logWebDav("online completion update row skipped: no local sheet book composer=${composer != null}")
        return
    }
    val info = onlineImportedBookSourceInfo(book) ?: run {
        logWebDav(
            "online completion update row skipped title=${book.callString("getTitle")} " +
                "backupId=${bookBackupIdOf(book)} backupCode=${book.callString("getBackupCode")} " +
                "uri=${book.callString("getUri")} publisher=${book.callString("getPublisher")}",
        )
        return
    }
    composer ?: run {
        logWebDav("online completion update row skipped title=${book.callString("getTitle")}: no composer")
        return
    }
    onlineCompletionUpdateRowInjecting.set(true)
    runCatching {
        renderOnlineCompletionUpdateDivider(composer)
        renderOnlineCompletionUpdateRow(book, info, composer)
        logWebDav("online completion update row rendered title=${book.callString("getTitle")} source=${info.sourceName}")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX online completion update row render failed: ${it.stackTraceToString()}")
    }
    onlineCompletionUpdateRowInjecting.remove()
}

internal fun WebDavDriveHook.renderOnlineCompletionUpdateRow(book: Any, info: OnlineImportedBookSourceInfo, composer: Any) {
    val modifier = paddingModifier(
        clickableModifier(modifierInstance(), "OnlineCompletionChapterUpdate") {
            startOnlineCompletionChapterUpdate(book, info)
        },
        start = 18,
        top = 16,
        end = 12,
        bottom = 16,
    )
    val content = functionProxy("OnlineCompletionUpdateRowContent", FUNCTION3_CLASS) { args ->
        val rowScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        val innerComposer = args.getOrNull(1) ?: return@functionProxy targetUnit()
        onlineCompletionUpdateImageVector()?.let { image ->
            renderOnlineCompletionIcon(
                image = image,
                modifier = sizeModifier(
                    paddingModifier(
                        modifierInstance(),
                        start = 0,
                        top = 0,
                        end = 16,
                        bottom = 0,
                    ),
                    20,
                ),
                tint = colorScheme(innerComposer).longMethod("getOnBackground"),
                composer = innerComposer,
            )
        }
        renderOnlineCompletionPrimaryText(
            text = "更新",
            modifier = rowWeightModifier(rowScope, modifierInstance()),
            composer = innerComposer,
        )
        renderOnlineCompletionSecondaryText(
            text = info.sourceName.ifBlank { "未知源" },
            composer = innerComposer,
        )
        navigateNextImageVector()?.let { image ->
            renderOnlineCompletionIcon(
                image = image,
                modifier = paddingModifier(
                    modifierInstance(),
                    start = 0,
                    top = 2,
                    end = 0,
                    bottom = 0,
                ),
                tint = colorScheme(innerComposer).longMethod("getSurfaceContainerHighest"),
                composer = innerComposer,
            )
        }
        targetUnit()
    }
    method(ROW_KT_CLASS, ROW_METHOD, 7).invoke(
        null,
        modifier,
        arrangementStart(),
        alignmentCenterVertically(),
        content,
        composer,
        384,
        0,
    )
}

internal fun WebDavDriveHook.renderOnlineCompletionUpdateGroupActionRow(
    book: Any,
    info: OnlineImportedBookSourceInfo,
    groupIcon: Any,
    groupClick: Any,
    composer: Any,
) {
    val updateClick = functionProxy("OnlineCompletionChapterUpdateActionCell", FUNCTION0_CLASS) {
        startOnlineCompletionChapterUpdate(book, info)
        targetUnit()
    }
    val content = functionProxy("OnlineCompletionUpdateGroupRowContent", FUNCTION3_CLASS) { args ->
        val rowScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        val innerComposer = args.getOrNull(1) ?: return@functionProxy targetUnit()
        val updateIcon = onlineCompletionUpdateImageVector() ?: groupIcon
        renderBookLocalActionCell(
            modifier = rowWeightModifier(rowScope, modifierInstance()),
            image = updateIcon,
            title = "更新",
            onClick = updateClick,
            composer = innerComposer,
        )
        renderBookLocalActionDivider(innerComposer)
        renderBookLocalActionCell(
            modifier = rowWeightModifier(rowScope, modifierInstance()),
            image = groupIcon,
            title = "分组",
            onClick = groupClick,
            composer = innerComposer,
        )
        targetUnit()
    }
    if (com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4") {
        com.reamicro.fix.core.B2NativeUi(classLoader).row(fillMaxWidthModifier(), content, composer)
        return
    }
    method(ROW_KT_CLASS, ROW_METHOD, 7).invoke(
        null,
        fillMaxWidthModifier(),
        arrangementStart(),
        alignmentCenterVertically(),
        content,
        composer,
        384,
        0,
    )
}

internal fun WebDavDriveHook.renderBookLocalActionDivider(composer: Any) {
    runCatching {
        val color = composeColorToArgb(borderVariantColor(composer))
        val factory = functionProxy("OnlineCompletionUpdateGroupDivider", FUNCTION1_CLASS) { args ->
            View((args?.getOrNull(0) as? Context) ?: activityProvider() ?: error("No context for divider")).apply {
                setBackgroundColor(color)
            }
        }
        val update = functionProxy("OnlineCompletionUpdateGroupDividerUpdate", FUNCTION1_CLASS) { args ->
            (args?.getOrNull(0) as? View)?.setBackgroundColor(color)
            targetUnit()
        }
        androidViews.render(factory, heightModifier(widthModifier(modifierInstance(), 0.8f), 56f), composer, update)
    }.onFailure {
        logWebDav("failed to render update/group divider: ${it.message}")
    }
}

internal fun WebDavDriveHook.renderBookLocalActionCell(
    modifier: Any,
    image: Any,
    title: String,
    onClick: Any,
    composer: Any,
) {
    if (com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4") {
        method(BOOK_LOCAL_SHEET_CLASS, BOOK_META_ACTION_ROW_METHOD, 6).invoke(
            null, modifier, image, title, onClick, composer, 0,
        )
        return
    }
    method(BOOK_LOCAL_SHEET_CLASS, BOOK_ACTION_CELL_METHOD, 7).invoke(
        null,
        modifier,
        image,
        title,
        onClick,
        composer,
        0,
        0,
    )
}

internal fun WebDavDriveHook.renderOnlineCompletionPrimaryText(text: String, modifier: Any?, composer: Any) {
    method(MATERIAL3_TEXT_CLASS, MATERIAL3_TEXT_METHOD, 22).invoke(
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
        0,
        0,
        null,
        typography(composer).method0("getLabelLarge"),
        composer,
        0,
        0,
        TEXT_DEFAULT_MASK_WITH_MODIFIER,
    )
}

internal fun WebDavDriveHook.renderOnlineCompletionSecondaryText(text: String, composer: Any) {
    method(MATERIAL3_TEXT_CLASS, MATERIAL3_TEXT_METHOD, 22).invoke(
        null,
        text,
        null,
        themeOnBackgroundVariant(composer),
        null,
        0L,
        null,
        null,
        null,
        0L,
        null,
        null,
        0L,
        textOverflowEllipsis(),
        false,
        1,
        0,
        null,
        typography(composer).method0("getBodyMedium"),
        composer,
        0,
        24960,
        TEXT_SECONDARY_SINGLE_LINE_MASK,
    )
}

internal fun WebDavDriveHook.renderOnlineCompletionIcon(image: Any, modifier: Any, tint: Long, composer: Any) {
    iconImageVectorMethod().invoke(
        null,
        image,
        null,
        modifier,
        tint,
        composer,
        48,
        0,
    )
}

internal fun WebDavDriveHook.iconImageVectorMethod(): Method =
    synchronized(methodCache) {
        methodCache.getOrPut("$ICON_KT_CLASS#$ICON_METHOD/ImageVector") {
            cls(ICON_KT_CLASS).declaredMethods.firstOrNull {
                it.hostNameMatches(ICON_METHOD) &&
                    it.parameterTypes.size == 7 &&
                    typeMatches(it.parameterTypes.firstOrNull(), IMAGE_VECTOR_CLASS)
            }?.apply { isAccessible = true }
                ?: error("$ICON_KT_CLASS.$ICON_METHOD ImageVector overload not found")
        }
    }

internal fun WebDavDriveHook.renderOnlineCompletionUpdateDivider(composer: Any) {
    simpleDividerMethod().invoke(
        null,
        method(PADDING_KT_CLASS, PADDING_ABSOLUTE_DEFAULT_METHOD, 7).invoke(
            null,
            modifierInstance(),
            udp(54),
            0f,
            0f,
            0f,
            14,
            null,
        ),
        0L,
        composer,
        0,
        2,
    )
}

internal fun WebDavDriveHook.rowWeightModifier(rowScope: Any, modifier: Any): Any =
    if (com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4") {
        rowScope.javaClass.methods.first {
            it.hostNameMatches("weight") && it.parameterTypes.size == 2
        }.invoke(rowScope, modifier, 1f)
    } else rowScope.javaClass.methods.first {
        it.hostNameMatches("weight") && it.parameterTypes.size == 3
    }.invoke(rowScope, modifier, 1f, true)

internal fun WebDavDriveHook.sizeModifier(baseModifier: Any, size: Int): Any =
    method(SIZE_KT_CLASS, SIZE_METHOD, 2).invoke(null, baseModifier, udp(size))

internal fun WebDavDriveHook.widthModifier(baseModifier: Any, widthDp: Float): Any =
    if (com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4") {
        com.reamicro.fix.core.B2NativeUi(classLoader).width(baseModifier, udp(widthDp))
    } else cls(SIZE_KT_CLASS).declaredMethods.first {
        it.name.startsWith("width") &&
            !it.name.contains("fill", ignoreCase = true) &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[1] == java.lang.Float.TYPE
    }.apply { isAccessible = true }.invoke(null, baseModifier, udp(widthDp))

internal fun WebDavDriveHook.heightModifier(baseModifier: Any, heightDp: Float): Any =
    if (com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4") {
        com.reamicro.fix.core.B2NativeUi(classLoader).height(baseModifier, udp(heightDp))
    } else cls(SIZE_KT_CLASS).declaredMethods.first {
        it.name.startsWith("height") &&
            !it.name.contains("fill", ignoreCase = true) &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[1] == java.lang.Float.TYPE
    }.apply { isAccessible = true }.invoke(null, baseModifier, udp(heightDp))

internal fun WebDavDriveHook.backgroundModifier(baseModifier: Any, color: Long): Any {
    val method = cls(BACKGROUND_KT_CLASS).declaredMethods.first {
        it.name.contains("background", ignoreCase = true) && it.parameterTypes.size >= 3
    }.apply { isAccessible = true }
    return if (method.parameterTypes.size == 3) {
        method.invoke(null, baseModifier, color, null)
    } else {
        method.invoke(null, baseModifier, color, null, 2, null)
    }
}

internal fun WebDavDriveHook.paddingModifier(
    baseModifier: Any,
    start: Int,
    top: Int,
    end: Int,
    bottom: Int,
): Any =
    // B4 的 PaddingKt 没有保留 padding-qDBjuR0 门面名，改走已核实的原生 ABI，避免注入时抛异常被吞。
    if (HostSymbols.abiProfileId == "2.3.3-b4") {
        val ui = B2NativeUi(classLoader)
        runCatching { ui.padding(baseModifier, ui.dp(start), ui.dp(top), ui.dp(end), ui.dp(bottom)) }
            .getOrNull() ?: baseModifier
    } else {
        method(PADDING_KT_CLASS, PADDING_METHOD, 5).invoke(
            null,
            baseModifier,
            udp(start),
            udp(top),
            udp(end),
            udp(bottom),
        )
    }

internal fun WebDavDriveHook.clickableModifier(baseModifier: Any, name: String, onClick: () -> Unit): Any =
    method(CLICKABLE_KT_CLASS, CLICKABLE_DEFAULT_METHOD, 9).invoke(
        null,
        baseModifier,
        null,
        null,
        false,
        null,
        null,
        functionProxy(name, FUNCTION0_CLASS) {
            onClick()
            targetUnit()
        },
        28,
        null,
    )

internal fun WebDavDriveHook.arrangementStart(): Any =
    staticObject(ARRANGEMENT_CLASS, "INSTANCE").method0("getStart")

internal fun WebDavDriveHook.alignmentCenterVertically(): Any =
    staticObject(ALIGNMENT_CLASS, "INSTANCE").method0("getCenterVertically")

internal fun WebDavDriveHook.themeOnBackgroundVariant(composer: Any): Long =
    method(THEME_KT_CLASS, "getOnBackgroundVariant", 1).invoke(null, colorScheme(composer)) as Long

internal fun WebDavDriveHook.borderVariantColor(composer: Any): Long =
    method(THEME_KT_CLASS, "getBorderVariant", 1).invoke(null, colorScheme(composer)) as Long

private var onlineCompletionRefreshVectorCache: Any? = null

internal fun WebDavDriveHook.onlineCompletionUpdateImageVector(): Any? =
    listOf(
        Triple("androidx.compose.material.icons.filled.RefreshKt", "getRefresh", ICONS_FILLED_CLASS),
        Triple("androidx.compose.material.icons.filled.SyncKt", "getSync", ICONS_FILLED_CLASS),
        Triple(EDIT_ICON_CLASS, "getEdit", ICONS_OUTLINED_CLASS),
    ).firstNotNullOfOrNull { (className, methodName, holderClass) ->
        runCatching {
            method(className, methodName, 1).invoke(null, staticObject(holderClass, "INSTANCE"))
        }.getOrNull()
    } ?: onlineCompletionRefreshVectorCache ?: run {
        // B4 混淆了 material icons 门面，回退到与原生图标同构造器的矢量图。
        if (com.reamicro.fix.core.HostSymbols.abiProfileId != "2.3.3-b4") return@run null
        runCatching { B4WebDavVector(this).buildRefresh() }.getOrNull()?.also {
            onlineCompletionRefreshVectorCache = it
        }
    }

internal fun WebDavDriveHook.navigateNextImageVector(): Any? =
    runCatching {
        method(NAVIGATE_NEXT_ICON_CLASS, "getNavigateNext", 1).invoke(
            null,
            staticObject(ICONS_AUTO_MIRRORED_FILLED_CLASS, "INSTANCE"),
        )
    }.getOrNull()

internal fun WebDavDriveHook.colorScheme(composer: Any): Any {
    if (com.reamicro.fix.core.HostNativeUiAbi.supports(com.reamicro.fix.core.HostSymbols.profileId)) {
        return B2NativeUi(classLoader).theme(composer, "getColorScheme")
    }
    val materialTheme = staticObject(MATERIAL_THEME_CLASS, "INSTANCE")
    val stable = staticInt(MATERIAL_THEME_CLASS, "\$stable")
    return method(MATERIAL_THEME_CLASS, "getColorScheme", 2).invoke(materialTheme, composer, stable)
}

internal fun WebDavDriveHook.typography(composer: Any): Any {
    if (com.reamicro.fix.core.HostNativeUiAbi.supports(com.reamicro.fix.core.HostSymbols.profileId)) {
        return B2NativeUi(classLoader).theme(composer, "getTypography")
    }
    val materialTheme = staticObject(MATERIAL_THEME_CLASS, "INSTANCE")
    val stable = staticInt(MATERIAL_THEME_CLASS, "\$stable")
    return method(MATERIAL_THEME_CLASS, "getTypography", 2).invoke(materialTheme, composer, stable)
}

internal fun WebDavDriveHook.modifierInstance(): Any =
    if (HostSymbols.abiProfileId == "2.3.3-b4") B2NativeUi(classLoader).modifier()
    else staticObject(MODIFIER_CLASS, "INSTANCE")

internal fun WebDavDriveHook.showToast(message: String) {
    val activity = activityProvider()
    if (activity != null) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
        return
    }
    Handler(Looper.getMainLooper()).post {
        currentContext()?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }
}

internal fun WebDavDriveHook.fillMaxSizeModifier(): Any {
    if (HostSymbols.abiProfileId == "2.3.3-b4") {
        val ui = B2NativeUi(classLoader)
        return ui.fillSize(ui.modifier())
    }
    val modifier = emptyModifier()
    return runCatching {
        cls(SIZE_KT_CLASS).declaredMethods.first {
        it.hostNameMatches(FILL_MAX_SIZE_METHOD) && it.parameterTypes.size == 2
        }.apply { isAccessible = true }.invoke(null, modifier, 1f)
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX failed to apply fillMaxSize modifier: ${it.stackTraceToString()}")
        modifier
    }
}

internal fun WebDavDriveHook.fillMaxWidthModifier(): Any {
    if (HostSymbols.abiProfileId == "2.3.3-b4") {
        val ui = B2NativeUi(classLoader)
        return ui.fillWidth(ui.modifier())
    }
    val modifier = emptyModifier()
    return runCatching {
        cls(SIZE_KT_CLASS).declaredMethods.first {
            it.hostNameMatches(FILL_MAX_WIDTH_METHOD) && it.parameterTypes.size == 2
        }.apply { isAccessible = true }.invoke(null, modifier, 1f)
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX failed to apply fillMaxWidth modifier: ${it.stackTraceToString()}")
        modifier
    }
}

internal fun WebDavDriveHook.startPaddingModifier(startDp: Int): Any {
    val modifier = emptyModifier()
    return runCatching {
        cls(PADDING_KT_CLASS).declaredMethods.first {
            it.hostNameMatches(PADDING_ABSOLUTE_DEFAULT_METHOD) && it.parameterTypes.size == 7
        }.apply { isAccessible = true }.invoke(
            null,
            modifier,
            udp(startDp),
            0f,
            0f,
            0f,
            14,
            null,
        )
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX failed to apply start padding modifier: ${it.stackTraceToString()}")
        modifier
    }
}

internal fun WebDavDriveHook.udp(value: Int): Float =
    runCatching {
        cls(UNIT_EXT_KT_CLASS).declaredMethods.first {
            it.hostNameMatches(UDP_METHOD) && it.parameterTypes.size == 1 && it.parameterTypes[0] == java.lang.Integer.TYPE
        }.apply { isAccessible = true }.invoke(null, value) as Float
    }.getOrDefault(value.toFloat())

internal fun WebDavDriveHook.udp(value: Float): Float = value

internal fun WebDavDriveHook.emptyModifier(): Any {
    if (HostSymbols.abiProfileId == "2.3.3-b4") return B2NativeUi(classLoader).modifier()
    val modifierClass = cls(MODIFIER_CLASS)
    return runCatching {
        modifierClass.hostField("INSTANCE").apply { isAccessible = true }.get(null)
    }.recoverCatching {
        modifierClass.getField("Companion").apply { isAccessible = true }.get(null)
    }.recoverCatching {
        cls("$MODIFIER_CLASS\$Companion").getDeclaredField("\$\$INSTANCE")
            .apply { isAccessible = true }
            .get(null)
    }.getOrThrow()
}

internal fun WebDavDriveHook.onlineCompletionRenderTypeForSource(source: OnlineSourceEntry, title: String): Int {
    val sourceId = source.id.ifBlank { source.name }
    val type = onlineCompletionRenderTypesBySource[sourceId] ?: synchronized(onlineCompletionRenderTypesBySource) {
        onlineCompletionRenderTypesBySource[sourceId] ?: run {
            var candidate = ONLINE_COMPLETION_RENDER_TYPE_BASE +
                ((sourceId.hashCode() and Int.MAX_VALUE) % ONLINE_COMPLETION_RENDER_TYPE_BUCKETS)
            while (true) {
                val existing = onlineCompletionRenderSourceByType[candidate]
                if (existing == null || existing == sourceId) {
                    onlineCompletionRenderSourceByType[candidate] = sourceId
                    onlineCompletionRenderTypesBySource[sourceId] = candidate
                    break
                }
                candidate += 1
                if (candidate >= ONLINE_COMPLETION_RENDER_TYPE_BASE + ONLINE_COMPLETION_RENDER_TYPE_BUCKETS) {
                    candidate = ONLINE_COMPLETION_RENDER_TYPE_BASE
                }
            }
            candidate
        }
    }
    onlineCompletionRenderTitles[type] = title.ifBlank { ONLINE_COMPLETION_TITLE }
    return type
}

internal fun WebDavDriveHook.isOnlineCompletionRenderType(type: Int): Boolean =
    onlineCompletionRenderTitles.containsKey(type)

internal fun cloudPathOf(value: Any?): String = value?.callString("getPath").orEmpty()

internal fun setOnlineCompletionSmallIcon(builder: Notification.Builder) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        runCatching {
            builder.setReaMicroSmallIcon()
        }.onSuccess {
            return
        }.onFailure {
            logWebDav("online completion module notification icon fallback: ${it.message}")
        }
    }
    @Suppress("DEPRECATION")
    builder.setSmallIcon(android.R.drawable.stat_sys_download)
}

internal fun roundedDrawable(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
    }

internal fun WebDavDriveHook.composeColorToArgb(color: Long): Int =
    composeInterop.colorToArgb(color)

internal fun WebDavDriveHook.withAlpha(color: Int, alpha: Int): Int =
    (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

internal fun WebDavDriveHook.solidColor(argb: Long): Any {
    val colorKt = cls(COLOR_KT_CLASS)
    val colorMethod = colorKt.declaredMethods.firstOrNull {
        it.hostNameMatches("Color") && it.parameterTypes.size == 1 && it.parameterTypes[0] == java.lang.Long.TYPE
    } ?: colorKt.declaredMethods.first {
        it.hostNameMatches("Color") && it.parameterTypes.size == 1 && it.parameterTypes[0] == java.lang.Integer.TYPE
    }
    colorMethod.isAccessible = true
    val color = if (colorMethod.parameterTypes[0] == java.lang.Long.TYPE) {
        colorMethod.invoke(null, argb) as Long
    } else {
        colorMethod.invoke(null, argb.toInt()) as Long
    }
    return cls(SOLID_COLOR_CLASS).getDeclaredConstructor(
        java.lang.Long.TYPE,
        cls(DEFAULT_CONSTRUCTOR_MARKER_CLASS),
    ).apply { isAccessible = true }.newInstance(color, null)
}
