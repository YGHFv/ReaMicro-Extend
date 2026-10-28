package com.reamicro.fix.hook

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.core.ComposeInterop
import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostNameMatches
import com.reamicro.fix.core.typeMatches
import com.reamicro.fix.settings.FontSettingsSnapshot
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Method
import java.util.Locale

class FileEditCompletionHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
    private val fontSettingsProvider: () -> FontSettingsSnapshot = { FontSettingsSnapshot() },
) {

    private val composeInterop = ComposeInterop(
        classLoader = classLoader,
        resolveClass = ::cls,
        unitInstance = ::targetUnit,
        logPrefix = LOG_PREFIX,
    )

    /** 混淆宿主只核实过原生 ABI 门面，明文反射门面在这些宿主里根本不存在。 */
    private val nativeUi by lazy { com.reamicro.fix.core.B2NativeUi(classLoader) }
    private val nativeCardLogged = java.util.concurrent.atomic.AtomicBoolean()

    private val methodCache = HashMap<String, Method>()
    private val activityResultHookedClasses = HashSet<String>()
    private val injectingRow = ThreadLocal.withInitial { false }

    fun install() {

        runCatching {
            XposedBridge.hookAllConstructors(cls(HostClasses.Host.BOOKSHELF_REPOSITORY), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    ReaMicroBookMetadataSync.rememberBookshelfRepository(param.thisObject)
                }
            })
        }.onFailure { XposedBridge.log("$LOG_PREFIX bookshelf capture failed: $it") }
        hookBookDetailsTitleAuthorItem()
        hookBookDetailsSyncSizeItem()
        hookActivityResultFor(Activity::class.java)
    }

    private fun hookBookDetailsTitleAuthorItem() {
        runCatching {
            val itemsClass = cls(BOOK_OVERVIEW_ITEMS_CLASS)
            val methods = itemsClass.declaredMethods.filter { method ->
                method.hostNameMatches(BOOK_TITLE_AUTHOR_ITEM_METHOD) &&
                    method.parameterTypes.size == 6 &&
                    typeMatches(method.parameterTypes.getOrNull(1), BOOK_CLASS)
            }
            if (methods.isEmpty()) error("BookTitleAuthorItem composable not found")
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (injectingRow.get() == true) return
                        if (!settingsProvider().canUseFileEdit) return
                        val lazyItemScope = param.args?.getOrNull(0) ?: return
                        val book = param.args?.getOrNull(1) ?: return
                        val onEditTitle = param.args?.getOrNull(2) ?: return
                        val onEditAuthor = param.args?.getOrNull(3) ?: return
                        val composer = param.args?.getOrNull(4) ?: return
                        injectingRow.set(true)
                        val rendered = runCatching {
                            renderBookTitleAuthorIdentifierCard(lazyItemScope, book, onEditTitle, onEditAuthor, composer)
                        }.onSuccess {
                            param.result = targetUnit()
                        }.onFailure {
                            XposedBridge.log("$LOG_PREFIX book identifier row render failed: ${it.stackTraceToString()}")
                        }
                        injectingRow.set(false)
                        rendered.getOrNull()
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX book identifier BookTitleAuthorItem hook installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX book identifier BookTitleAuthorItem hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun renderBookTitleAuthorIdentifierCard(
        lazyItemScope: Any,
        book: Any,
        onEditTitle: Any,
        onEditAuthor: Any,
        composer: Any,
    ) {
        val displayText = bookIdentifierDisplayText(book)
        renderDetailsCard(lazyItemScope, composer, "BookTitleAuthorIdentifierCard") { innerComposer ->
            renderDetailsActionRow(
                label = "\u4e66\u540d",
                value = callString(book, "getTitle"),
                composer = innerComposer,
                onClickName = "EditBookTitle",
                onClick = { invokeFunction0(onEditTitle) },
                trailing = editImageVector(),
                nativeTrailingGlyph = "\u270E",
            )
            renderDetailsDivider(innerComposer)
            renderDetailsActionRow(
                label = "\u4f5c\u8005",
                value = callString(book, "getAuthor"),
                composer = innerComposer,
                onClickName = "EditBookAuthor",
                onClick = { invokeFunction0(onEditAuthor) },
                trailing = editImageVector(),
                nativeTrailingGlyph = "\u270E",
            )
            if (displayText.isNotBlank()) {
                renderDetailsDivider(innerComposer)
                renderDetailsActionRow(
                    label = "\u6807\u8bc6",
                    value = displayText,
                    composer = innerComposer,
                    onClickName = "CopyBookIdentifier",
                    onClick = { copyBookIdentifiers(book) },
                    trailing = contentCopyImageVector(),
                    valueWeight = true,
                    middleEllipsis = true,
                    nativeTrailingGlyph = "\u29C9",
                )
            }
            targetUnit()
        }
    }

    private fun hookBookDetailsSyncSizeItem() {
        runCatching {
            val itemsClass = cls(BOOK_OVERVIEW_ITEMS_CLASS)
            val methods = itemsClass.declaredMethods.filter { method ->
                method.hostNameMatches(BOOK_SYNC_SIZE_ITEM_METHOD) &&
                    method.parameterTypes.size == 5 &&
                    typeMatches(method.parameterTypes.getOrNull(1), BOOK_CLASS)
            }
            if (methods.isEmpty()) error("BookSyncSizeItem composable not found")
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (injectingRow.get() == true) return
                        if (!settingsProvider().canUseFileEdit) return
                        val lazyItemScope = param.args?.getOrNull(0) ?: return
                        val book = param.args?.getOrNull(1) ?: return
                        val onOpenSync = param.args?.getOrNull(2) ?: return
                        val composer = param.args?.getOrNull(3) ?: return
                        injectingRow.set(true)
                        val rendered = runCatching {
                            renderFileEditSyncDetailsCard(lazyItemScope, book, onOpenSync, composer)
                        }.onSuccess {
                            param.result = targetUnit()
                        }.onFailure {
                            XposedBridge.log("$LOG_PREFIX file edit details row render failed: ${it.stackTraceToString()}")
                        }
                        injectingRow.set(false)
                        rendered.getOrNull()
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX file edit BookSyncSizeItem hook installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX file edit BookSyncSizeItem hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun renderFileEditSyncDetailsCard(lazyItemScope: Any, book: Any, onOpenSync: Any, composer: Any) {
        renderDetailsCard(lazyItemScope, composer, "FileEditSyncDetailsCard") { innerComposer ->
            renderDetailsActionRow(
                label = "\u7f16\u8f91",
                value = "\u56fe\u4e66\u7ed3\u6784",
                composer = innerComposer,
                onClickName = "OpenFileEditDetails",
                onClick = { openFileEditor(book) },
                trailing = navigateNextImageVector(),
                nativeTrailingGlyph = "\u276F",
            )
            renderDetailsDivider(innerComposer)
            renderDetailsActionRow(
                label = "\u540c\u6b65",
                value = syncSizeValueText(book),
                composer = innerComposer,
                onClickName = "OpenBookSync",
                onClick = { invokeFunction0(onOpenSync) },
                afterValueIcon = syncStorageImageVector(book),
                secondaryValue = backupTypeName(book),
                trailing = navigateNextImageVector(),
                nativeTrailingGlyph = "\u276F",
            )
            targetUnit()
        }
    }

    private fun bookIdentifierDisplayText(book: Any): String =
        middleEllipsizeIdentifier(bookIdentifierRawText(book))

    private fun middleEllipsizeIdentifier(identifier: String): String {
        if (identifier.length <= IDENTIFIER_DISPLAY_MAX_CHARS) return identifier
        return identifier.take(IDENTIFIER_DISPLAY_EDGE_CHARS) +
            "..." +
            identifier.takeLast(IDENTIFIER_DISPLAY_EDGE_CHARS)
    }

    private fun bookIdentifierRawText(book: Any): String =
        BookIdentifierText.display(callString(book, "getUuid"))

    private fun syncSizeValueText(book: Any): String =
        formatFileSize(callLong(book, "getSize")).replace(" ", "")

    private fun backupTypeName(book: Any): String =
        if (com.reamicro.fix.core.HostSymbols.profileId == "2.3.3-b4") {
            // 与 B4 BookSyncSizeItem 的存储类型参数保持一致，旧 BackupType 门面已被内联。
            when (callInt(book, "getBackupType")) {
                1 -> "百度网盘"
                2 -> "115网盘"
                4 -> "阿里云盘"
                else -> "本地存储"
            }
        } else runCatching {
            method(BACKUP_TYPE_CLASS, "getName", 1).invoke(
                staticObject(BACKUP_TYPE_CLASS, "INSTANCE"),
                Integer.valueOf(callInt(book, "getBackupType")),
            )?.toString().orEmpty()
        }.getOrDefault("").ifBlank { "\u672c\u5730\u5b58\u50a8" }

    private fun copyBookIdentifiers(book: Any) {
        val activity = activityProvider() ?: return
        val text = bookIdentifierRawText(book)
        if (text.isBlank()) return
        val copy = {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("\u56fe\u4e66\u6807\u8bc6", text))
            Toast.makeText(activity, "\u5df2\u590d\u5236\u6807\u8bc6", Toast.LENGTH_SHORT).show()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) copy() else activity.runOnUiThread { copy() }
    }

    private fun invokeFunction0(callback: Any?) {
        runCatching { XposedHelpers.callMethod(callback, "invoke") }
            .onFailure { XposedBridge.log("$LOG_PREFIX callback invoke failed: ${it.stackTraceToString()}") }
    }

    private fun openFileEditor(book: Any) {
        val activity = activityProvider() ?: return
        val open = open@{
            if (!settingsProvider().canUseFileEdit) return@open
            val root = resolveBookRoot(activity, book)
            if (root == null) {
                Toast.makeText(activity, "\u672a\u627e\u5230\u56fe\u4e66\u6587\u4ef6\u76ee\u5f55", Toast.LENGTH_SHORT).show()
                return@open
            }
            XposedBridge.log("$LOG_PREFIX file edit open root=${root.absolutePath}")
            hookActivityResultFor(activity.javaClass)
            runCatching { EpubBookPanel(
                activity = activity,
                root = root,
                bookTitle = callString(book, "getTitle").ifBlank { "\u56fe\u4e66\u6587\u4ef6" },
                book = book,
                settingsProvider = settingsProvider,
                fontSettingsProvider = fontSettingsProvider,
            ).show() }.onFailure {
                XposedBridge.log("$LOG_PREFIX structure open failed: ${it.stackTraceToString()}")
                Toast.makeText(activity, "图书结构打开失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            open()
        } else {
            activity.runOnUiThread { open() }
        }
    }

    private fun hookActivityResultFor(startClass: Class<*>) {
        var current: Class<*>? = startClass
        while (current != null && Activity::class.java.isAssignableFrom(current)) {
            val target = current
            val shouldHook = synchronized(activityResultHookedClasses) {
                activityResultHookedClasses.add(target.name)
            }
            if (shouldHook) {
                runCatching {
                    val method = target.declaredMethods.firstOrNull {
                        it.name == "onActivityResult" &&
                            it.parameterTypes.size == 3 &&
                            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                            Intent::class.java.isAssignableFrom(it.parameterTypes[2])
                    } ?: return@runCatching
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            val requestCode = param.args?.getOrNull(0) as? Int ?: return
                            val resultCode = param.args?.getOrNull(1) as? Int ?: return
                            val data = param.args?.getOrNull(2) as? Intent
                            EpubBookPanel.dispatchActivityResult(activity, requestCode, resultCode, data)

                        }
                    })
                    XposedBridge.log("$LOG_PREFIX file edit ActivityResult hook installed: ${target.name}")
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX file edit ActivityResult hook failed (${target.name}): ${it.stackTraceToString()}")
                }
            }
            current = current.superclass
        }
    }

    private fun resolveBookRoot(activity: Activity, book: Any): File? {

        val uid = callLong(book, "getUid")
        val uuid = callString(book, "getUuid").trim()
        if (uid < 0L || uuid.isBlank() || uuid == "." || uuid == ".." ||
            uuid.any { it == '/' || it == '\\' }) return null
        val repository = ReaMicroBookMetadataSync.currentBookshelfRepository()
        val booksDir = runCatching {
            val storage = repository?.javaClass?.getMethod("getUserStorage")?.invoke(repository)
            storage?.javaClass?.getMethod("userBooksDir", java.lang.Long::class.java)
                ?.invoke(storage, java.lang.Long.valueOf(uid))?.toString()?.let(::File)
        }.getOrNull() ?: File(File(activity.filesDir, uid.toString()), "books")
        val parent = booksDir.canonicalFile
        val root = File(parent, uuid).canonicalFile
        if (root.parentFile != parent || !root.isDirectory) return null
        return root
    }

    private fun renderDetailsText(
        text: String,
        modifier: Any?,
        color: Long,
        composer: Any,
        overflow: Int = textOverflowEllipsis(),
    ) {
        method(TEXT_KT_CLASS, TEXT_METHOD, 22).invoke(
            null,
            text,
            modifier,
            color,
            null,
            0L,
            null,
            null,
            null,
            0L,
            null,
            null,
            0L,
            overflow,
            false,
            1,
            0,
            null,
            typography(composer).method0("getBodyLarge"),
            composer,
            0,
            24960,
            if (modifier == null) TEXT_SECONDARY_SINGLE_LINE_MASK else TEXT_SECONDARY_SINGLE_LINE_MASK_WITH_MODIFIER,
        )
    }

    private fun renderIcon(image: Any, modifier: Any, tint: Long, composer: Any) {
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

    private fun iconImageVectorMethod(): Method =
        synchronized(methodCache) {
            methodCache.getOrPut("$ICON_KT_CLASS#$ICON_METHOD/ImageVector") {
                cls(ICON_KT_CLASS).declaredMethods.firstOrNull {
                    it.name == ICON_METHOD &&
                        it.parameterTypes.size == 7 &&
                        it.parameterTypes.firstOrNull()?.name == IMAGE_VECTOR_CLASS
                }?.apply { isAccessible = true }
                    ?: error("$ICON_KT_CLASS.$ICON_METHOD ImageVector overload not found")
            }
        }

    private fun renderDetailsCard(
        lazyItemScope: Any,
        composer: Any,
        name: String,
        renderRows: (Any) -> Unit,
    ) {
        if (com.reamicro.fix.core.HostNativeUiAbi.supports(com.reamicro.fix.core.HostSymbols.profileId)) {
            renderNativeDetailsCard(composer, name, renderRows)
            return
        }
        val shape = roundedShape()
        val modifier = backgroundModifier(
            borderModifier(
                clipModifier(
                    lazyAnimateItemModifier(lazyItemScope, fillMaxWidthModifier(modifierInstance())),
                    shape,
                ),
                0.4f,
                colorScheme(composer).longMethod("getSurfaceContainerHighest"),
                shape,
            ),
            themeBackgroundAuto(composer),
        )
        val content = functionProxy(name, FUNCTION3_CLASS) { args ->
            val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
            renderRows(innerComposer)
            targetUnit()
        }
        method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(
            null,
            modifier,
            arrangementTop(),
            alignmentStart(),
            content,
            composer,
            384,
            0,
        )
    }

    /** 编辑与同步使用同一右侧箭头；同步的存储信息仍与取值并排，不降成左侧副标题。 */
    private fun renderB4SyncActionRow(
        label: String, value: String, storage: String?, storageIcon: Any?,
        composer: Any, name: String, onClick: () -> Unit,
    ) {
        val callback = functionProxy(name, FUNCTION0_CLASS) { onClick(); targetUnit() }
        val modifier = nativeUi.padding(
            nativeUi.clickable(nativeUi.fillWidth(nativeUi.modifier()), callback),
            nativeUi.dp(16), nativeUi.dp(15), nativeUi.dp(16), nativeUi.dp(15),
        )
        val content = functionProxy("${name}RowContent", FUNCTION3_CLASS) { args ->
            val scope = requireNotNull(args?.getOrNull(0))
            val inner = requireNotNull(args?.getOrNull(1))
            val scheme = nativeUi.theme(inner, "getColorScheme")
            nativeText(label, inner, nativeThemeOnBackgroundVariant(scheme), NATIVE_VALUE_STYLE)
            nativeUi.spacer(nativeUi.weight(scope, nativeUi.modifier()), inner)
            nativeText(value, inner, 0L, NATIVE_VALUE_STYLE)
            storageIcon?.let { image ->
                nativeUi.spacer(nativeUi.width(nativeUi.modifier(), nativeUi.dp(8)), inner)
                val sized = nativeUi.height(nativeUi.width(nativeUi.modifier(), nativeUi.dp(18)), nativeUi.dp(18))
                method("androidx.compose.foundation.ImageKt", "Image", 6)
                    .invoke(null, image, null, sized, inner, 48, 120)
            }
            storage?.takeIf { it.isNotBlank() }?.let {
                nativeUi.spacer(nativeUi.width(nativeUi.modifier(), nativeUi.dp(4)), inner)
                nativeText(it, inner, 0L, NATIVE_VALUE_STYLE)
            }
            renderB4DetailsArrow(scheme, inner)
            targetUnit()
        }
        nativeUi.row(modifier, content, composer)
    }

    private fun renderB4DetailsArrow(scheme: Any, composer: Any) {
        val arrow = navigateNextImageVector() ?: return
        // 与 B4 出版行完全一致：默认 24dp Icon、SurfaceContainerHighest、modifier 默认位 4。
        method(ICON_KT_CLASS, ICON_METHOD, 7).invoke(null, arrow, null, null,
            nativeLong(scheme, "getSurfaceContainerHighest"), composer, 48, 4)
    }

    /** 详情卡片走已核实的原生 ABI：混淆宿主不存在明文 Compose 门面，反射路径只适用于未混淆构建。 */
    private fun renderNativeDetailsCard(composer: Any, name: String, renderRows: (Any) -> Unit) {
        val scheme = nativeUi.theme(composer, "getColorScheme")
        val modifier = nativeUi.card(
            nativeUi.modifier(),
            nativeThemeBackgroundAuto(scheme),
            nativeThemeBorderVariant(scheme),
            nativeUi.dp(NATIVE_CARD_BORDER_DP),
        )
        val content = composeInterop.functionProxy(name, FUNCTION3_CLASS) { args ->
            val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
            renderRows(innerComposer)
            targetUnit()
        }
        nativeUi.column(modifier, nativeUi.top(), nativeUi.start(), content, composer)
        if (nativeCardLogged.compareAndSet(false, true)) {
            XposedBridge.logAlways("$LOG_PREFIX file edit details card rendered through native ABI")
        }
    }

    private fun nativeThemeBackgroundAuto(scheme: Any): Long =
        method(THEME_KT_CLASS, BACKGROUND_AUTO_METHOD, 1).invoke(null, scheme) as Long

    private fun nativeThemeBorderVariant(scheme: Any): Long =
        method(THEME_KT_CLASS, BORDER_VARIANT_METHOD, 1).invoke(null, scheme) as Long

    private fun nativeThemeOnBackgroundVariant(scheme: Any): Long =
        method(THEME_KT_CLASS, ON_BACKGROUND_VARIANT_METHOD, 1).invoke(null, scheme) as Long

    /** 原生条目：标题在左、取值在右，次要说明作为 supporting 文本，点击复用宿主水波纹。 */
    private fun renderNativeActionRow(
        label: String,
        value: String,
        secondary: String?,
        trailingGlyph: String?,
        composer: Any,
        onClickName: String,
        onClick: () -> Unit,
    ) {
        val headline = nativeUi.lambda(label.hashCode(),
            composeInterop.functionProxy("${onClickName}Headline", FUNCTION2_CLASS) { args ->
                val innerComposer = args?.getOrNull(0) ?: return@functionProxy targetUnit()
                nativeText(label, innerComposer,
                    nativeThemeOnBackgroundVariant(nativeUi.theme(innerComposer, "getColorScheme")),
                    NATIVE_HEADLINE_STYLE)
                targetUnit()
            })
        val trailing = nativeUi.lambda((label + value).hashCode() xor NATIVE_TRAILING_KEY_MASK,
            composeInterop.functionProxy("${onClickName}Trailing", FUNCTION2_CLASS) { args ->
                val innerComposer = args?.getOrNull(0) ?: return@functionProxy targetUnit()
                val trailingText = trailingGlyph?.let { "$value  $it" } ?: value
                nativeText(trailingText, innerComposer, 0L, NATIVE_VALUE_STYLE)
                targetUnit()
            })
        val supporting = secondary?.takeIf { it.isNotBlank() }?.let { text ->
            nativeUi.lambda((label + text).hashCode() xor NATIVE_SUPPORTING_KEY_MASK,
                composeInterop.functionProxy("${onClickName}Supporting", FUNCTION2_CLASS) { args ->
                    val innerComposer = args?.getOrNull(0) ?: return@functionProxy targetUnit()
                    nativeText(text, innerComposer, 0L, NATIVE_VALUE_STYLE)
                    targetUnit()
                })
        }
        val callback = composeInterop.functionProxy(onClickName, FUNCTION0_CLASS) {
            onClick()
            targetUnit()
        }
        val modifier = nativeUi.clickable(nativeUi.fillWidth(nativeUi.modifier()), callback)
        // 参数位次与设置页 ActionRow 保持完全一致，复用宿主已核实的 changed/mask 组合。
        var mask = 4 or 16 or 128 or 256
        if (supporting == null) mask = mask or 8
        nativeUi.listItem(arrayOf(headline, modifier, null, supporting, null, trailing,
            nativeUi.listColors(composer), 0f, 0f, composer, NATIVE_LIST_ITEM_CHANGED, mask))
    }

    private fun nativeText(value: String, composer: Any, color: Long, styleProperty: String) {
        val scheme = nativeUi.theme(composer, "getColorScheme")
        val typography = nativeUi.theme(composer, "getTypography")
        nativeUi.text(arrayOf(
            value, null,
            if (color == 0L) nativeLong(scheme, "getOnBackground") else color,
            null, 0L, null, null, null, 0L, null, null, 0L,
            0, false, 0, 0, null,
            runCatching { nativeAny(typography, styleProperty) }
                .getOrElse { nativeAny(typography, "getLabelLarge") },
            composer, 0, 0, NATIVE_TEXT_MASK,
        ))
    }

    /** 混淆宿主的 getter 名称同样被改写，必须走带符号解析的调用器，不能按明文名反射。 */
    private fun nativeAny(target: Any, name: String): Any =
        requireNotNull(XposedHelpers.callMethod(target, name))

    private fun nativeLong(target: Any, name: String): Long = nativeAny(target, name) as Long

    private fun renderDetailsActionRow(
        label: String,
        value: String,
        composer: Any,
        onClickName: String,
        onClick: () -> Unit,
        trailing: Any?,
        leadingValueIcon: Any? = null,
        afterValueIcon: Any? = null,
        secondaryValue: String? = null,
        valueWeight: Boolean = false,
        middleEllipsis: Boolean = false,
        nativeTrailingGlyph: String? = null,
    ) {
        if (com.reamicro.fix.core.HostSymbols.profileId == "2.3.3-b4" &&
            (secondaryValue != null || nativeTrailingGlyph == "\u276F")) {
            renderB4SyncActionRow(label, value, secondaryValue, afterValueIcon, composer, onClickName, onClick)
            return
        }
        if (com.reamicro.fix.core.HostNativeUiAbi.supports(com.reamicro.fix.core.HostSymbols.profileId)) {
            renderNativeActionRow(
                label = label,
                value = value,
                secondary = secondaryValue,
                trailingGlyph = nativeTrailingGlyph,
                composer = composer,
                onClickName = onClickName,
                onClick = onClick,
            )
            return
        }
        val modifier = fillMaxWidthModifier(
            paddingModifier(
                clickableModifier(modifierInstance(), onClickName, onClick),
                start = 16,
                top = 15,
                end = 12,
                bottom = 15,
            ),
        )
        val content = functionProxy("${onClickName}RowContent", FUNCTION3_CLASS) { args ->
            val rowScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            val innerComposer = args.getOrNull(1) ?: return@functionProxy targetUnit()
            renderDetailsText(
                text = label,
                modifier = null,
                color = themeOnBackgroundVariant(innerComposer),
                composer = innerComposer,
            )
            renderFixedWidthSpacer(width = 12, composer = innerComposer)
            if (!valueWeight) renderWeightedSpacer(rowScope, innerComposer)
            leadingValueIcon?.let { image ->
                renderIcon(
                    image = image,
                    modifier = sizeModifier(
                        paddingModifier(modifierInstance(), start = 0, top = 0, end = 4, bottom = 0),
                        18,
                    ),
                    tint = syncIconTint(innerComposer),
                    composer = innerComposer,
                )
            }
            renderDetailsText(
                text = value,
                modifier = if (valueWeight) rowWeightModifier(rowScope, modifierInstance()) else null,
                color = colorScheme(innerComposer).longMethod("getOnBackground"),
                overflow = if (middleEllipsis) textOverflowMiddleEllipsis() else textOverflowEllipsis(),
                composer = innerComposer,
            )
            afterValueIcon?.let { image ->
                renderFixedWidthSpacer(width = 8, composer = innerComposer)
                renderIcon(
                    image = image,
                    modifier = sizeModifier(modifierInstance(), 18),
                    tint = syncIconTint(innerComposer),
                    composer = innerComposer,
                )
            }
            secondaryValue?.takeIf { it.isNotBlank() }?.let { text ->
                renderFixedWidthSpacer(width = 4, composer = innerComposer)
                renderDetailsText(
                    text = text,
                    modifier = null,
                    color = colorScheme(innerComposer).longMethod("getOnBackground"),
                    composer = innerComposer,
                )
            }
            trailing?.let { image ->
                renderIcon(
                    image = image,
                    modifier = paddingModifier(
                        sizeModifier(modifierInstance(), 20),
                        start = 4,
                        top = 0,
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

    private fun renderDetailsDivider(composer: Any) {
        if (com.reamicro.fix.core.HostNativeUiAbi.supports(com.reamicro.fix.core.HostSymbols.profileId)) {
            nativeUi.divider(
                nativeUi.padding(nativeUi.modifier(), nativeUi.dp(NATIVE_DIVIDER_START_DP), 0f, 0f, 0f),
                composer,
            )
            return
        }
        simpleDividerMethod().invoke(
            null,
            paddingModifier(modifierInstance(), start = 16, top = 0, end = 16, bottom = 0),
            0L,
            composer,
            0,
            2,
        )
    }

    private fun rowWeightModifier(rowScope: Any, modifier: Any): Any =
        rowScope.javaClass.methods.first {
            it.name == "weight" && it.parameterTypes.size == 3
        }.invoke(rowScope, modifier, 1f, true)

    private fun renderWeightedSpacer(rowScope: Any, composer: Any) {
        method(SPACER_KT_CLASS, SPACER_METHOD, 3).invoke(
            null,
            rowWeightModifier(rowScope, startPaddingModifier(modifierInstance(), 12)),
            composer,
            0,
        )
    }

    private fun renderFixedWidthSpacer(width: Int, composer: Any) {
        method(SPACER_KT_CLASS, SPACER_METHOD, 3).invoke(
            null,
            widthModifier(modifierInstance(), width),
            composer,
            0,
        )
    }

    private fun startPaddingModifier(baseModifier: Any, start: Int): Any =
        method(PADDING_KT_CLASS, PADDING_ABSOLUTE_DEFAULT_METHOD, 7).invoke(
            null,
            baseModifier,
            udp(start),
            0f,
            0f,
            0f,
            14,
            null,
        )

    private fun widthModifier(baseModifier: Any, width: Int): Any =
        method(SIZE_KT_CLASS, WIDTH_METHOD, 2).invoke(null, baseModifier, udp(width))

    private fun sizeModifier(baseModifier: Any, size: Int): Any =
        method(SIZE_KT_CLASS, SIZE_METHOD, 2).invoke(null, baseModifier, udp(size))

    private fun paddingModifier(
        baseModifier: Any,
        start: Int,
        top: Int,
        end: Int,
        bottom: Int,
    ): Any =
        method(PADDING_KT_CLASS, PADDING_METHOD, 5).invoke(
            null,
            baseModifier,
            udp(start),
            udp(top),
            udp(end),
            udp(bottom),
        )

    private fun clickableModifier(baseModifier: Any, name: String, onClick: () -> Unit): Any =
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

    private fun editImageVector(): Any? =
        runCatching {
            method(EDIT_ICON_CLASS, "getEdit", 1).invoke(null, staticObject(ICONS_OUTLINED_CLASS, "INSTANCE"))
        }.getOrNull()

    private fun navigateNextImageVector(): Any? =
        runCatching {
            if (com.reamicro.fix.core.HostSymbols.profileId == "2.3.3-b4") {
                return@runCatching cls("ReaMicroHost.NavigateNextIcon").getDeclaredMethod("i").invoke(null)
            }
            method(NAVIGATE_NEXT_ICON_CLASS, "getNavigateNext", 1).invoke(
                null,
                staticObject(ICONS_AUTO_MIRRORED_FILLED_CLASS, "INSTANCE"),
            )
        }.getOrNull()

    private fun contentCopyImageVector(): Any? =
        runCatching {
            method(CONTENT_COPY_ICON_CLASS, "getContentCopy", 1).invoke(
                null,
                staticObject(ICONS_OUTLINED_CLASS, "INSTANCE"),
            )
        }.getOrNull()

    private fun syncStorageImageVector(book: Any): Any? =
        when (callInt(book, "getBackupType")) {
            1 -> coloredAppIcon(BAIDU_ICON_CLASS, "getBaiduNetdisk")
            2 -> coloredAppIcon(YUN115_ICON_CLASS, "getYun115")
            4 -> if (com.reamicro.fix.core.HostSymbols.profileId == "2.3.3-b4") {
                cls("ReaMicroHost.AliyunIcon").getDeclaredMethod("d").invoke(null)
            } else coloredAppIcon(ALIYUN_ICON_CLASS, "getAliyun")
            else -> coloredAppIcon(ANDROID_OS_ICON_CLASS, "getAndroidOs")
        }

    private fun coloredAppIcon(className: String, methodName: String): Any? =
        runCatching { method(className, methodName, 0).invoke(null) }
            .recoverCatching {
                method(className, methodName, 1).invoke(
                    null,
                    staticObject(APP_ICONS_COLORED_CLASS, "INSTANCE"),
                )
            }
            .getOrNull()

    private fun syncIconTint(composer: Any): Long =
        runCatching { colorUnspecified() }.getOrElse { colorScheme(composer).longMethod("getPrimary") }

    private fun colorUnspecified(): Long =
        staticObject(COLOR_CLASS, "Companion").method0("getUnspecified") as Long

    private fun arrangementStart(): Any =
        staticObject(ARRANGEMENT_CLASS, "INSTANCE").method0("getStart")

    private fun arrangementTop(): Any =
        staticObject(ARRANGEMENT_CLASS, "INSTANCE").method0("getTop")

    private fun alignmentCenterVertically(): Any =
        staticObject(ALIGNMENT_CLASS, "INSTANCE").method0("getCenterVertically")

    private fun alignmentStart(): Any =
        staticObject(ALIGNMENT_CLASS, "INSTANCE").method0("getStart")

    private fun themeOnBackgroundVariant(composer: Any): Long =
        method(THEME_KT_CLASS, "getOnBackgroundVariant", 1).invoke(null, colorScheme(composer)) as Long

    private fun textOverflowEllipsis(): Int =
        staticObject(TEXT_OVERFLOW_CLASS, "INSTANCE").method0("getEllipsis") as Int

    private fun textOverflowMiddleEllipsis(): Int =
        staticObject(TEXT_OVERFLOW_CLASS, "INSTANCE").method0("getMiddleEllipsis") as Int

    private fun fillMaxWidthModifier(baseModifier: Any): Any =
        method(SIZE_KT_CLASS, FILL_MAX_WIDTH_DEFAULT_METHOD, 4).invoke(null, baseModifier, 0f, 1, null)

    private fun lazyAnimateItemModifier(lazyItemScope: Any, baseModifier: Any): Any =
        runCatching {
            lazyItemScope.javaClass.methods.firstOrNull {
                it.name == LAZY_ANIMATE_ITEM_DEFAULT_METHOD && it.parameterTypes.size == 6
            }?.invoke(null, lazyItemScope, baseModifier, null, null, null, 7, null)
                ?: baseModifier
        }.getOrDefault(baseModifier)

    private fun clipModifier(baseModifier: Any, shape: Any): Any =
        method(CLIP_KT_CLASS, CLIP_METHOD, 2).invoke(null, baseModifier, shape)

    private fun borderModifier(baseModifier: Any, width: Float, color: Long, shape: Any): Any =
        method(BORDER_KT_CLASS, BORDER_METHOD, 4).invoke(null, baseModifier, width, color, shape)

    private fun backgroundModifier(baseModifier: Any, color: Long): Any =
        method(BACKGROUND_KT_CLASS, BACKGROUND_DEFAULT_METHOD, 5).invoke(null, baseModifier, color, null, 2, null)

    private fun roundedShape(): Any =
        method(SHAPE_KT_CLASS, ROUNDED_SHAPE_METHOD, 0).invoke(null)

    private fun themeBackgroundAuto(composer: Any): Long =
        method(THEME_KT_CLASS, BACKGROUND_AUTO_METHOD, 1).invoke(null, colorScheme(composer)) as Long

    private fun colorScheme(composer: Any): Any {
        val materialTheme = staticObject(MATERIAL_THEME_CLASS, "INSTANCE")
        val stable = staticInt(MATERIAL_THEME_CLASS, "\$stable")
        return method(MATERIAL_THEME_CLASS, "getColorScheme", 2).invoke(materialTheme, composer, stable)
    }

    private fun typography(composer: Any): Any {
        val materialTheme = staticObject(MATERIAL_THEME_CLASS, "INSTANCE")
        val stable = staticInt(MATERIAL_THEME_CLASS, "\$stable")
        return method(MATERIAL_THEME_CLASS, "getTypography", 2).invoke(materialTheme, composer, stable)
    }

    private fun modifierInstance(): Any =
        staticObject(MODIFIER_CLASS, "INSTANCE")

    private fun udp(value: Int): Float =
        cls(UNIT_EXT_KT_CLASS).declaredMethods.first {
            it.name == UDP_METHOD && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }.invoke(null, value) as Float

    private fun simpleDividerMethod(): Method =
        synchronized(methodCache) {
            methodCache.getOrPut("$DIVIDER_KT_CLASS#SimpleDivider/5") {
                cls(DIVIDER_KT_CLASS).declaredMethods.firstOrNull {
                    it.name.contains("SimpleDivider") && it.parameterTypes.size == 5
                }?.apply { isAccessible = true }
                    ?: error("SimpleDivider method not found")
            }
        }

    private fun functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any =
        composeInterop.functionProxy(name, functionClassName, block)

    private fun cls(className: String): Class<*> =
        classLoader.hostClass(className)

    private fun method(className: String, methodName: String, parameterCount: Int): Method {
        val cacheKey = "$className#$methodName/$parameterCount"
        return synchronized(methodCache) {
            methodCache.getOrPut(cacheKey) {
                val logicalClass = com.reamicro.fix.core.HostSymbols.logicalClass(className)
                val owner = com.reamicro.fix.core.HostSymbols.resolveMethodOwner(logicalClass, methodName)
                val actualName = com.reamicro.fix.core.HostSymbols.resolveMethodName(logicalClass, methodName)
                val candidates = cls(owner).methods.asSequence() + cls(owner).declaredMethods.asSequence()
                candidates.firstOrNull {
                    (it.name == actualName || it.hostNameMatches(methodName)) &&
                        it.parameterTypes.size == parameterCount
                }?.apply { isAccessible = true }
                    ?: error("$className.$methodName/$parameterCount not found")
            }
        }
    }

    private fun staticObject(className: String, fieldName: String): Any {
        val clazz = cls(className)
        val field = runCatching { clazz.getDeclaredField(fieldName) }
            .recoverCatching { clazz.getField(fieldName) }
            .recoverCatching { clazz.getDeclaredField("Companion") }
            .getOrElse {
                val fields = clazz.declaredFields.joinToString { field -> "${field.name}:${field.type.name}" }
                error("$className.$fieldName not found; fields=[$fields]")
            }
        field.isAccessible = true
        return field.get(null)
    }

    private fun staticInt(className: String, fieldName: String): Int =
        cls(className).getDeclaredField(fieldName).apply { isAccessible = true }.getInt(null)

    private fun Any.method0(name: String): Any =
        javaClass.methods.first {
            it.parameterTypes.isEmpty() && (it.name == name || it.name.startsWith("$name-"))
        }.invoke(this)

    private fun Any.longMethod(name: String): Long =
        method0(name) as Long

    private fun targetUnit(): Any? = runCatching {
        staticObject("kotlin.Unit", "INSTANCE")
    }.getOrNull()

    private fun callString(target: Any, methodName: String): String =
        runCatching { XposedHelpers.callMethod(target, methodName)?.toString().orEmpty() }.getOrDefault("")

    private fun callLong(target: Any, methodName: String): Long =
        runCatching { (XposedHelpers.callMethod(target, methodName) as? Number)?.toLong() ?: 0L }.getOrDefault(0L)

    private fun callInt(target: Any, methodName: String): Int =
        runCatching { (XposedHelpers.callMethod(target, methodName) as? Number)?.toInt() ?: 0 }.getOrDefault(0)

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.1f GB", mb / 1024.0)
    }

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val BOOK_LOCAL_SHEET_CLASS = HostClasses.Host.BOOK_LOCAL_SHEET
        const val BOOK_OVERVIEW_ITEMS_CLASS = HostClasses.Host.BOOK_OVERVIEW_ITEMS
        const val BOOK_CLASS = HostClasses.Host.BOOK
        const val BOOK_LOCAL_SHEET_METHOD = "BookLocalSheet"
        const val BOOK_LOCAL_SHEET_CONTENT_METHOD = "BookLocalSheet\$lambda\$2"
        const val FILE_BACKUP_METHOD = "FileBackup"
        const val BOOK_TITLE_AUTHOR_ITEM_METHOD = "BookTitleAuthorItem"
        const val BOOK_SYNC_SIZE_ITEM_METHOD = "BookSyncSizeItem"
        const val BACKUP_TYPE_CLASS = HostClasses.Host.BACKUP_TYPE

        const val ROW_KT_CLASS = HostClasses.Compose.ROW_KT
        const val ROW_METHOD = "Row"
        const val COLUMN_KT_CLASS = HostClasses.Compose.COLUMN_KT
        const val COLUMN_METHOD = "Column"
        const val SPACER_KT_CLASS = HostClasses.Compose.SPACER_KT
        const val SPACER_METHOD = "Spacer"
        const val ARRANGEMENT_CLASS = HostClasses.Compose.ARRANGEMENT
        const val ALIGNMENT_CLASS = HostClasses.Compose.ALIGNMENT
        const val LIST_ITEM_KT_CLASS = HostClasses.Compose.LIST_ITEM_KT
        const val LIST_ITEM_METHOD = "ListItem-HXNGIdc"
        const val LIST_ITEM_DEFAULTS_CLASS = HostClasses.Compose.LIST_ITEM_DEFAULTS
        const val LIST_ITEM_COLORS_METHOD = "colors-J08w3-E"
        const val TEXT_KT_CLASS = HostClasses.Compose.TEXT_KT
        const val TEXT_METHOD = "Text-Nvy7gAk"
        const val TEXT_OVERFLOW_CLASS = HostClasses.Compose.TEXT_OVERFLOW
        const val ICON_KT_CLASS = HostClasses.Compose.ICON_KT
        const val ICON_METHOD = "Icon-ww6aTOc"
        const val IMAGE_VECTOR_CLASS = HostClasses.Compose.IMAGE_VECTOR
        const val EDIT_ICON_CLASS = HostClasses.Compose.EDIT_ICON
        const val CONTENT_COPY_ICON_CLASS = HostClasses.Compose.CONTENT_COPY_ICON
        const val NAVIGATE_NEXT_ICON_CLASS = HostClasses.Compose.NAVIGATE_NEXT_ICON
        const val ICONS_OUTLINED_CLASS = "androidx.compose.material.icons.Icons\$Outlined"
        const val ICONS_AUTO_MIRRORED_FILLED_CLASS = "androidx.compose.material.icons.Icons\$AutoMirrored\$Filled"
        const val APP_ICONS_COLORED_CLASS = "app.zhendong.reamicro.arch.icons.AppIcons\$Colored"
        const val ANDROID_OS_ICON_CLASS = HostClasses.Host.ANDROID_OS_ICON
        const val BAIDU_ICON_CLASS = HostClasses.Host.BAIDU_ICON
        const val YUN115_ICON_CLASS = HostClasses.Host.YUN115_ICON
        const val ALIYUN_ICON_CLASS = HostClasses.Host.ALIYUN_ICON
        const val DIVIDER_KT_CLASS = HostClasses.Host.DIVIDER_KT
        const val SIZE_KT_CLASS = HostClasses.Compose.SIZE_KT
        const val HEIGHT_METHOD = "height-3ABfNKs"
        const val WIDTH_METHOD = "width-3ABfNKs"
        const val SIZE_METHOD = "size-3ABfNKs"
        const val FILL_MAX_WIDTH_DEFAULT_METHOD = "fillMaxWidth\$default"
        const val PADDING_KT_CLASS = HostClasses.Compose.PADDING_KT
        const val PADDING_METHOD = "padding-qDBjuR0"
        const val PADDING_ABSOLUTE_DEFAULT_METHOD = "padding-qDBjuR0\$default"
        const val CLICKABLE_KT_CLASS = HostClasses.Compose.CLICKABLE_KT
        const val CLICKABLE_DEFAULT_METHOD = "clickable-O2vRcR0\$default"
        const val CLIP_KT_CLASS = HostClasses.Compose.CLIP_KT
        const val CLIP_METHOD = "clip"
        const val BORDER_KT_CLASS = HostClasses.Compose.BORDER_KT
        const val BORDER_METHOD = "border-xT4_qwU"
        const val BACKGROUND_KT_CLASS = HostClasses.Compose.BACKGROUND_KT
        const val BACKGROUND_DEFAULT_METHOD = "background-bw27NRU\$default"
        const val MATERIAL_THEME_CLASS = HostClasses.Compose.MATERIAL_THEME
        const val THEME_KT_CLASS = HostClasses.Host.THEME_KT
        const val SHAPE_KT_CLASS = HostClasses.Host.SHAPE_KT
        const val ROUNDED_SHAPE_METHOD = "getRoundedShape"
        const val BACKGROUND_AUTO_METHOD = "getBackgroundAuto"
        const val COLOR_CLASS = HostClasses.Compose.COLOR
        const val COLOR_TRANSPARENT_METHOD = "getTransparent-0d7_KjU"
        const val MODIFIER_CLASS = HostClasses.Compose.MODIFIER
        const val LAZY_ANIMATE_ITEM_DEFAULT_METHOD = "animateItem\$default"
        const val UNIT_EXT_KT_CLASS = HostClasses.Host.UNIT_EXT_KT
        const val UDP_METHOD = "getUdp"
        const val COMPOSABLE_LAMBDA_KT_CLASS = HostClasses.Compose.COMPOSABLE_LAMBDA_KT
        const val COMPOSABLE_LAMBDA_METHOD = "composableLambdaInstance"
        const val FUNCTION0_CLASS = HostClasses.Kotlin.FUNCTION0
        const val FUNCTION2_CLASS = HostClasses.Kotlin.FUNCTION2
        const val FUNCTION3_CLASS = HostClasses.Kotlin.FUNCTION3
        const val TEXT_DEFAULT_MASK_WITH_MODIFIER = 131064
        const val TEXT_SECONDARY_SINGLE_LINE_MASK = 110586
        const val TEXT_SECONDARY_SINGLE_LINE_MASK_WITH_MODIFIER = 110584
        const val FILE_EDIT_TITLE_KEY = 0x524D4701
        const val FILE_EDIT_SUPPORTING_KEY = 0x524D4702
        const val FILE_EDIT_LEADING_KEY = 0x524D4703
        const val FILE_EDIT_TRAILING_KEY = 0x524D4704

        // 原生 ABI 分派所需：这些数值与设置页已核实的调用保持一字不差。
        const val BORDER_VARIANT_METHOD = "getBorderVariant"
        const val ON_BACKGROUND_VARIANT_METHOD = "getOnBackgroundVariant"
        const val NATIVE_CARD_BORDER_DP = 0.8
        const val NATIVE_DIVIDER_START_DP = 16
        const val NATIVE_TEXT_MASK = 131066
        const val NATIVE_HEADLINE_STYLE = "getLabelLarge"
        const val NATIVE_VALUE_STYLE = "getLabelLarge"
        const val NATIVE_LIST_ITEM_CHANGED = 196614
        const val NATIVE_TRAILING_KEY_MASK = 0x0F0F0F0F
        const val NATIVE_SUPPORTING_KEY_MASK = 0x2468ACE0
        const val IDENTIFIER_DISPLAY_MAX_CHARS = 34
        const val IDENTIFIER_DISPLAY_EDGE_CHARS = 15
    }
}
