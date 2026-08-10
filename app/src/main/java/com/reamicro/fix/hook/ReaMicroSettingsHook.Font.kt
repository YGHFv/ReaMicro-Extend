package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.FileOutputStream
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors

// 字体设置簇。
//
// 全局界面字体与阅读正文字体的导入、选择、按文本样式分别指定。
//
// 从 ReaMicroSettingsHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的
// 结果重新缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaMicroSettingsHook.renderFontSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("FontSettingsList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        fontLibraryVersionValue()
        val config = settings.fontSettings()
        val rows = listOf(
            ActionRow(
                key = "font_global_family",
                title = "\u5168\u5c40\u5b57\u4f53",
                subtitle = fontSelectionSummary(config.globalFamily, "\u8ddf\u968f\u9605\u5fae"),
                onClick = { openNestedInjectedRoute(InjectedRoute.FontPicker(FontPickerTarget.Global)) },
            ),
            ActionRow(
                key = "font_song_mapping",
                title = "\u5b8b\u4f53\u6620\u5c04",
                subtitle = fontSelectionSummary(config.songMapping, "\u4e0d\u6620\u5c04"),
                onClick = { openNestedInjectedRoute(InjectedRoute.FontPicker(FontPickerTarget.SongMapping)) },
            ),
            ActionRow(
                key = "font_kai_mapping",
                title = "\u6977\u4f53\u6620\u5c04",
                subtitle = fontSelectionSummary(config.kaiMapping, "\u4e0d\u6620\u5c04"),
                onClick = { openNestedInjectedRoute(InjectedRoute.FontPicker(FontPickerTarget.KaiMapping)) },
            ),
            ActionRow(
                key = "font_library",
                title = "\u5b57\u4f53\u5e93",
                subtitle = "${listFontFiles().size} \u4e2a\u5b57\u4f53",
                onClick = { openNestedInjectedRoute(InjectedRoute.FontLibrary) },
            ),
        )
        addLazyItem(lazyListScope, FONT_SETTINGS_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderFontPickerContent(target: FontPickerTarget, innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("FontPickerList${target.name}", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        fontLibraryVersionValue()
        val current = currentFontSelection(target)
        val rows = buildList {
            add(
                ActionRow(
                    key = "font_${target.name}_clear",
                    title = target.clearTitle,
                    subtitle = target.clearSubtitle,
                    trailing = if (current.isBlank()) "\u5f53\u524d" else null,
                    onClick = {
                        setFontSelection(target, "")
                        navigateBackFromInjectedRoute()
                    },
                ),
            )
            builtinFontSelectionsFor(target).forEach { option ->
                add(
                    ActionRow(
                        key = "font_${target.name}_${option.value}",
                        title = option.title,
                        subtitle = option.subtitle,
                        trailing = if (sameFontSelection(current, option.value)) "\u5f53\u524d" else null,
                        titleFontSelection = option.value,
                        onClick = {
                            setFontSelection(target, option.value)
                            navigateBackFromInjectedRoute()
                        },
                    ),
                )
            }
            val files = listFontFiles()
            files.forEach { file ->
                val selection = file.absolutePath
                add(
                    ActionRow(
                        key = "font_${target.name}_${selection.hashCode()}",
                        title = displayFontName(selection),
                        subtitle = file.name,
                        trailing = if (sameFontSelection(current, selection)) "\u5f53\u524d" else null,
                        titleFontSelection = selection,
                        onClick = {
                            setFontSelection(target, selection)
                            navigateBackFromInjectedRoute()
                        },
                    ),
                )
            }
            if (files.isEmpty()) {
                add(
                    ActionRow(
                        key = "font_${target.name}_empty",
                        title = "\u6682\u65e0\u5b57\u4f53",
                        subtitle = "\u8bf7\u5148\u5728\u5b57\u4f53\u5e93\u6dfb\u52a0\u5b57\u4f53",
                        onClick = { openNestedInjectedRoute(InjectedRoute.FontLibrary) },
                    ),
                )
            }
        }
        addLazyItem(lazyListScope, FONT_PICKER_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderFontLibraryContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("FontLibraryList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        fontLibraryVersionValue()
        val pendingDelete = pendingDeleteFontSelection()
        val files = listFontFiles()
        val rows = buildList {
            add(
                ActionRow(
                    key = "font_library_add",
                    title = "\u6dfb\u52a0\u5b57\u4f53",
                    subtitle = "\u9009\u62e9 ttf/otf \u5b57\u4f53\u6587\u4ef6",
                    onClick = { openFontDocumentPicker() },
                ),
            )
            builtinFontSelections().forEach { option ->
                add(
                    ActionRow(
                        key = "font_library_builtin_${option.value}",
                        title = option.title,
                        subtitle = option.subtitle,
                        titleFontSelection = option.value,
                    ),
                )
            }
            if (files.isEmpty()) {
                add(
                    ActionRow(
                        key = "font_library_empty",
                        title = "\u6682\u65e0\u5b57\u4f53",
                        subtitle = "\u5b57\u4f53\u5e93\u4e0e\u9605\u8bfb\u6392\u7248\u4e92\u901a",
                    ),
                )
            } else {
                files.forEach { file ->
                    val selection = file.absolutePath
                    val isPendingDelete = sameFontSelection(pendingDelete, selection)
                    add(
                        ActionRow(
                            key = "font_library_${selection.hashCode()}",
                            title = displayFontName(selection),
                            subtitle = if (isPendingDelete) "\u518d\u6b21\u70b9\u51fb\u786e\u8ba4\u79fb\u9664" else "\u70b9\u51fb\u79fb\u9664",
                            trailing = if (isPendingDelete) "\u79fb\u9664" else null,
                            titleFontSelection = selection,
                            onClick = {
                                if (isPendingDelete) {
                                    deleteFont(file)
                                } else {
                                    setPendingDeleteFontSelection(selection)
                                }
                            },
                        ),
                    )
                }
            }
        }
        addLazyItem(lazyListScope, FONT_LIBRARY_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.addSettingsFontChoiceRows(
    activity: Activity,
    card: LinearLayout,
    colors: SettingsDialogColors,
    currentSelection: String,
    clearTitle: String,
    onSelected: (String) -> Unit,
): List<View> {
    val rows = mutableListOf<View>()
    fun addRow(selection: String, title: String, subtitle: String? = null) {
        val current = sameFontSelection(currentSelection, selection)
        val row = settingsDialogFontChoiceRow(
            context = activity,
            title = if (current) "$title  \u5f53\u524d" else title,
            subtitle = subtitle,
            selection = selection,
            colors = colors,
        ) {
            onSelected(selection)
        }
        rows.add(row)
        card.addView(row)
    }
    addRow("", clearTitle)
    builtinFontSelections().forEach { option ->
        addRow(option.value, option.title, option.subtitle)
    }
    val files = listFontFiles()
    files.forEach { file ->
        addRow(file.absolutePath, displayFontName(file.absolutePath), file.name)
    }
    if (files.isEmpty()) {
        val row = settingsDialogStatus(activity, "\u5b57\u4f53\u5e93\u6682\u65e0\u5b57\u4f53\uff0c\u53ef\u5148\u5728\u5b57\u4f53\u7ba1\u7406\u4e2d\u6dfb\u52a0\u3002", colors)
        rows.add(row)
        card.addView(row)
    }
    return rows
}

internal fun ReaMicroSettingsHook.openSettingsFontSelectionDialog(
    activity: Activity,
    title: String,
    currentSelection: String,
    clearTitle: String,
    onSelected: (String) -> Unit,
) {
    runCatching {
        val colors = SettingsDialogColors(activity)
        val dialog = Dialog(activity)
        val card = settingsDialogCard(activity, colors)
        fun addRow(selection: String, rowTitle: String, subtitle: String? = null) {
            val current = sameFontSelection(currentSelection, selection)
            card.addView(
                settingsDialogFontChoiceRow(
                    context = activity,
                    title = if (current) "$rowTitle  \u5f53\u524d" else rowTitle,
                    subtitle = subtitle,
                    selection = selection,
                    colors = colors,
                ) {
                    onSelected(selection)
                    dialog.dismiss()
                },
            )
        }
        card.addView(settingsDialogTitle(activity, title, colors))
        addRow("", clearTitle)
        builtinFontSelections().forEach { option ->
            addRow(option.value, option.title, option.subtitle)
        }
        val files = listFontFiles()
        files.forEach { file ->
            addRow(file.absolutePath, displayFontName(file.absolutePath), file.name)
        }
        if (files.isEmpty()) {
            card.addView(settingsDialogHint(activity, "\u5b57\u4f53\u5e93\u6682\u65e0\u5b57\u4f53\uff0c\u53ef\u5148\u5728\u5b57\u4f53\u7ba1\u7406\u4e2d\u6dfb\u52a0\u3002", colors))
        }
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, 0.9f)
    }.onFailure {
        showToast("\u6253\u5f00\u5b57\u4f53\u9009\u62e9\u5931\u8d25")
        XposedBridge.log("$LOG_PREFIX open settings font selection failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.settingsDialogFontChoiceRow(
    context: Context,
    title: String,
    subtitle: String?,
    selection: String,
    colors: SettingsDialogColors,
    onClick: () -> Unit,
): TextView =
    TextView(context).apply {
        text = if (subtitle.isNullOrBlank()) title else "$title\n$subtitle"
        textSize = 14f
        setTextColor(colors.title)
        typeface = androidTypefaceForFontSelection(selection) ?: Typeface.DEFAULT
        setSingleLine(false)
        setPadding(
            settingsDp(context, 12),
            settingsDp(context, 10),
            settingsDp(context, 12),
            settingsDp(context, 10),
        )
        background = settingsRoundedRect(colors.field, settingsDp(context, 8), colors.border)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = settingsDp(context, 8) }
    }

internal fun ReaMicroSettingsHook.androidTypefaceForFontSelection(selection: String): Typeface? {
    if (selection.isBlank()) return null
    if (selection == FAMILY_SYSTEM) return Typeface.DEFAULT
    val file = File(selection)
    if (!file.isFile || !isFontFileName(file.name)) return null
    return runCatching { Typeface.createFromFile(file) }.getOrNull()
}

internal fun ReaMicroSettingsHook.fontSelectionSummary(value: String, fallback: String): String =
    if (value.isBlank()) fallback else displayFontName(value)

internal fun ReaMicroSettingsHook.currentFontSelection(target: FontPickerTarget): String {
    val config = settings.fontSettings()
    return when (target) {
        FontPickerTarget.Global -> config.globalFamily
        FontPickerTarget.SongMapping -> config.songMapping
        FontPickerTarget.KaiMapping -> config.kaiMapping
        FontPickerTarget.DialogueHighlight -> settings.dialogueHighlightSettings().fontFamily
    }
}

internal fun ReaMicroSettingsHook.setFontSelection(target: FontPickerTarget, value: String) {
    when (target) {
        FontPickerTarget.Global -> {
            val changed = !sameFontSelection(currentFontSelection(target), value)
            settings.setFontGlobalFamily(value)
            if (changed) onGlobalFontChanged()
        }
        FontPickerTarget.SongMapping -> settings.setFontSongMapping(value)
        FontPickerTarget.KaiMapping -> settings.setFontKaiMapping(value)
        FontPickerTarget.DialogueHighlight -> settings.setReaderDialogueHighlightFontFamily(value)
    }
}

internal fun ReaMicroSettingsHook.sameFontSelection(current: String, selection: String): Boolean =
    current == selection ||
        (!isBuiltinFontSelection(current) && !isBuiltinFontSelection(selection) &&
            current.isNotBlank() && File(current).name == File(selection).name)

internal fun ReaMicroSettingsHook.displayFontName(value: String): String {
    builtinFontSelections().firstOrNull { it.value == value }?.let { return it.title }
    val name = File(value).name.ifBlank { value }
    return name.substringBeforeLast('.', name)
}

internal fun ReaMicroSettingsHook.builtinFontSelections(): List<FontSelectionOption> =
    listOf(
        FontSelectionOption(
            value = FAMILY_SOURCE_HAN_SERIF,
            title = "\u601d\u6e90\u5b8b\u4f53",
            subtitle = "\u9605\u5fae\u5185\u7f6e\u5b57\u4f53",
        ),
        FontSelectionOption(
            value = FAMILY_SYSTEM,
            title = "\u7cfb\u7edf\u5b57\u4f53",
            subtitle = "\u8ddf\u968f\u7cfb\u7edf\u9ed8\u8ba4\u5b57\u4f53",
        ),
    )

internal fun ReaMicroSettingsHook.builtinFontSelectionsFor(target: FontPickerTarget): List<FontSelectionOption> =
    builtinFontSelections().filterNot { option ->
        option.value == FAMILY_SOURCE_HAN_SERIF &&
            (target == FontPickerTarget.Global || target == FontPickerTarget.SongMapping)
    }

internal fun ReaMicroSettingsHook.isBuiltinFontSelection(selection: String): Boolean =
    selection == FAMILY_SOURCE_HAN_SERIF || selection == FAMILY_SYSTEM

internal fun ReaMicroSettingsHook.listFontFiles(): List<File> {
    val activity = activityProvider() ?: return emptyList()
    val version = fontLibraryVersionValue()
    val now = System.currentTimeMillis()
    synchronized(fontFilesCacheLock) {
        if (
            cachedFontFilesVersion == version &&
            now - cachedFontFilesAtMs < FONT_FILES_CACHE_WINDOW_MS
        ) {
            return cachedFontFiles
        }
    }
    val files = fontDirectories(activity)
        .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
        .filter { it.isFile && isFontFileName(it.name) }
        .distinctBy { it.absolutePath }
        .sortedBy { displayFontName(it.absolutePath).lowercase() }
    synchronized(fontFilesCacheLock) {
        cachedFontFilesVersion = version
        cachedFontFilesAtMs = now
        cachedFontFiles = files
    }
    return files
}

internal fun ReaMicroSettingsHook.fontDirectories(activity: Activity): List<File> {
    val filesDir = activity.filesDir ?: return emptyList()
    val dirs = filesDir.listFiles()
        ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
        ?.map { File(it, "fonts") }
        ?.toMutableList()
        ?: mutableListOf()
    val defaultDir = File(File(filesDir, "0"), "fonts")
    if (dirs.none { it.absolutePath == defaultDir.absolutePath }) {
        dirs.add(defaultDir)
    }
    return dirs.distinctBy { it.absolutePath }
}

internal fun ReaMicroSettingsHook.writableFontDirectory(activity: Activity): File =
    fontDirectories(activity)
        .filter { it.exists() }
        .maxByOrNull { it.lastModified() }
        ?: File(File(activity.filesDir, "0"), "fonts")

internal fun ReaMicroSettingsHook.openFontDocumentPicker() {
    val activity = activityProvider() ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "font/ttf",
                    "font/otf",
                    "application/x-font-ttf",
                    "application/x-font-otf",
                    "application/font-sfnt",
                    "application/octet-stream",
                ),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivityForResult(intent, FONT_DOCUMENT_REQUEST_CODE)
    }.onFailure {
        Toast.makeText(activity, "\u65e0\u6cd5\u6253\u5f00\u5b57\u4f53\u9009\u62e9\u5668", Toast.LENGTH_SHORT).show()
        XposedBridge.log("$LOG_PREFIX failed to open font picker: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.copyFontUriToLibrary(activity: Activity, uri: Uri) {
    runCatching {
        val displayName = sanitizeFontFileName(queryDisplayName(activity, uri) ?: uri.lastPathSegment.orEmpty())
        if (!isFontFileName(displayName)) {
            Toast.makeText(activity, "\u8bf7\u9009\u62e9 ttf/otf \u5b57\u4f53\u6587\u4ef6", Toast.LENGTH_SHORT).show()
            return
        }
        val importToken = "$uri|$displayName"
        val now = System.currentTimeMillis()
        if (importToken == lastFontImportToken && now - lastFontImportAtMs < FONT_IMPORT_DEDUPE_WINDOW_MS) {
            return
        }
        lastFontImportToken = importToken
        lastFontImportAtMs = now
        if (listFontFiles().any { it.name.equals(displayName, ignoreCase = true) }) {
            Toast.makeText(activity, "\u5b57\u4f53\u5df2\u5b58\u5728\uff1a${displayFontName(displayName)}", Toast.LENGTH_SHORT).show()
            bumpFontLibraryVersion()
            return
        }
        val targetDir = writableFontDirectory(activity).apply { mkdirs() }
        val targetFile = File(targetDir, displayName)
        val input = activity.contentResolver.openInputStream(uri) ?: error("font input stream is null")
        input.use { source ->
            FileOutputStream(targetFile).use { output ->
                source.copyTo(output)
            }
        }
        bumpFontLibraryVersion()
        Toast.makeText(activity, "\u5df2\u6dfb\u52a0\u5b57\u4f53\uff1a${displayFontName(targetFile.name)}", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(activity, "\u6dfb\u52a0\u5b57\u4f53\u5931\u8d25", Toast.LENGTH_SHORT).show()
        XposedBridge.log("$LOG_PREFIX failed to copy font: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.sanitizeFontFileName(name: String): String {
    val cleaned = name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
    return cleaned.ifBlank { "font_${System.currentTimeMillis()}.ttf" }
}

internal fun ReaMicroSettingsHook.isFontFileName(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension == "ttf" || extension == "otf"
}

internal fun ReaMicroSettingsHook.uniqueFontFile(dir: File, name: String): File {
    var file = File(dir, name)
    if (!file.exists()) return file
    val base = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', "")
    var index = 1
    while (file.exists()) {
        file = File(dir, "$base-$index.$extension")
        index += 1
    }
    return file
}

internal fun ReaMicroSettingsHook.deleteFont(file: File) {
    val activity = activityProvider() ?: return
    runCatching {
        val target = file.canonicalFile
        if (!isKnownFontFile(activity, target)) {
            error("font file is outside user font directories: ${target.absolutePath}")
        }
        if (!target.exists()) {
            clearDeletedFontSelection(target)
            bumpFontLibraryVersion()
            Toast.makeText(activity, "\u5b57\u4f53\u5df2\u4e0d\u5b58\u5728", Toast.LENGTH_SHORT).show()
            return
        }
        if (!target.delete()) error("delete returned false")
        clearDeletedFontSelection(target)
        setPendingDeleteFontSelection("")
        bumpFontLibraryVersion()
        Toast.makeText(activity, "\u5df2\u79fb\u9664\u5b57\u4f53\uff1a${displayFontName(target.absolutePath)}", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(activity, "\u79fb\u9664\u5b57\u4f53\u5931\u8d25", Toast.LENGTH_SHORT).show()
        XposedBridge.log("$LOG_PREFIX failed to delete font: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.isKnownFontFile(activity: Activity, file: File): Boolean {
    if (!file.isFile && file.exists()) return false
    if (!isFontFileName(file.name)) return false
    val targetPath = file.canonicalFile.absolutePath
    return fontDirectories(activity).any { dir ->
        val dirPath = dir.canonicalFile.absolutePath
        targetPath.startsWith(dirPath + File.separator)
    }
}

internal fun ReaMicroSettingsHook.clearDeletedFontSelection(file: File) {
    val selection = file.absolutePath
    val config = settings.fontSettings()
    if (sameFontSelection(config.globalFamily, selection)) settings.setFontGlobalFamily("")
    if (sameFontSelection(config.songMapping, selection)) settings.setFontSongMapping("")
    if (sameFontSelection(config.kaiMapping, selection)) settings.setFontKaiMapping("")
}

internal fun ReaMicroSettingsHook.fontExpandedState(initialValue: Boolean): Any {
    fontExpandedUiState?.let { return it }
    return mutableBooleanState(initialValue).also { fontExpandedUiState = it }
}

internal fun ReaMicroSettingsHook.fontLibraryVersionState(): Any {
    fontLibraryVersionUiState?.let { return it }
    return mutableState(0).also { fontLibraryVersionUiState = it }
}

internal fun ReaMicroSettingsHook.fontLibraryVersionValue(): Int =
    (fontLibraryVersionState().method0("getValue") as? Number)?.toInt() ?: 0

internal fun ReaMicroSettingsHook.bumpFontLibraryVersion() {
    val state = fontLibraryVersionState()
    val value = (state.method0("getValue") as? Number)?.toInt() ?: 0
    state.javaClass.methods
        .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
        ?.invoke(state, value + 1)
    synchronized(fontFilesCacheLock) {
        cachedFontFilesVersion = Int.MIN_VALUE
        cachedFontFilesAtMs = 0L
        cachedFontFiles = emptyList()
    }
}

internal fun ReaMicroSettingsHook.pendingDeleteFontState(): Any {
    pendingDeleteFontUiState?.let { return it }
    return mutableState("").also { pendingDeleteFontUiState = it }
}

internal fun ReaMicroSettingsHook.pendingDeleteFontSelection(): String =
    pendingDeleteFontState().method0("getValue") as? String ?: ""

internal fun ReaMicroSettingsHook.setPendingDeleteFontSelection(selection: String) {
    val state = pendingDeleteFontState()
    state.javaClass.methods
        .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
        ?.invoke(state, selection)
}

internal fun ReaMicroSettingsHook.clearPendingDeleteFontSelection() {
    val state = pendingDeleteFontUiState ?: return
    state.javaClass.methods
        .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
        ?.invoke(state, "")
}

internal fun ReaMicroSettingsHook.resolvePreviewFontFamily(selection: String): Any? {
    if (selection.isBlank()) return null
    synchronized(previewFontFamilyCache) {
        previewFontFamilyCache[selection]?.let { return it }
    }
    resolveBuiltinFontFamily(selection)?.let { family ->
        synchronized(previewFontFamilyCache) {
            previewFontFamilyCache[selection] = family
        }
        return family
    }
    val file = File(selection)
    if (!file.isFile || !isFontFileName(file.name)) return null
    return runCatching {
        val provider = staticObject(FONT_PROVIDER_CLASS, "INSTANCE")
        val normal = fontWeight("getNormal")
        val bold = fontWeight("getBold")
        val fromPath = method(FONT_PROVIDER_CLASS, "fromPath", 2)
        val fonts = listOfNotNull(
            fromPath.invoke(provider, file.absolutePath, normal),
            fromPath.invoke(provider, file.absolutePath, bold),
        )
        if (fonts.isEmpty()) return null
        val family = cls(FONT_FAMILY_KT_CLASS).declaredMethods.first {
            it.name == "FontFamily" &&
                it.parameterTypes.size == 1 &&
                List::class.java.isAssignableFrom(it.parameterTypes[0])
        }.invoke(null, fonts)
        synchronized(previewFontFamilyCache) {
            previewFontFamilyCache[selection] = family
        }
        family
    }.onFailure { logResolvePreviewFontFailure(selection, it) }.getOrNull()
}

internal fun ReaMicroSettingsHook.resolveBuiltinFontFamily(selection: String): Any? =
    runCatching {
        when (selection) {
            FAMILY_SYSTEM -> resolveDefaultFontFamily()
            FAMILY_SOURCE_HAN_SERIF -> staticObject(FONT_PROVIDER_CLASS, "INSTANCE").method0("builtInSong")
            else -> null
        }
    }.onFailure { logResolvePreviewFontFailure(selection, it) }.getOrNull()

internal fun ReaMicroSettingsHook.resolveDefaultFontFamily(): Any? {
    fieldObjectOrNull(FONT_FAMILY_CLASS, "Default")?.let { return it }
    for (fieldName in listOf("Companion", "INSTANCE")) {
        val companion = fieldObjectOrNull(FONT_FAMILY_CLASS, fieldName) ?: continue
        callMethod(companion, "getDefault")?.let { return it }
    }
    return staticMethod(FONT_FAMILY_CLASS, "getDefault")?.invoke(null)
}

internal fun ReaMicroSettingsHook.fontWeight(methodName: String): Any {
    val clazz = cls(FONT_WEIGHT_CLASS)
    companionFontWeight(clazz, methodName)?.let { return it }
    val (fieldName, weight) = when (methodName) {
        "getBold" -> "Bold" to 700
        else -> "Normal" to 400
    }
    staticFontWeight(clazz, fieldName)?.let { return it }
    return clazz.getDeclaredConstructor(Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
        .newInstance(weight)
}

internal fun ReaMicroSettingsHook.companionFontWeight(clazz: Class<*>, methodName: String): Any? {
    for (fieldName in listOf("INSTANCE", "Companion")) {
        val companion = runCatching {
            clazz.getDeclaredField(fieldName).apply { isAccessible = true }.get(null)
        }.recoverCatching {
            clazz.getField(fieldName).apply { isAccessible = true }.get(null)
        }.getOrNull() ?: continue
        val method = companion.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.isEmpty()
        } ?: continue
        return method.invoke(companion)
    }
    return null
}

internal fun ReaMicroSettingsHook.staticFontWeight(clazz: Class<*>, fieldName: String): Any? =
    runCatching {
        clazz.getDeclaredField(fieldName).apply { isAccessible = true }.get(null)
    }.recoverCatching {
        clazz.getField(fieldName).apply { isAccessible = true }.get(null)
    }.getOrNull()

internal fun ReaMicroSettingsHook.logResolvePreviewFontFailure(selection: String, error: Throwable) {
    val key = "$selection|${error.javaClass.name}|${error.message}"
    synchronized(failedPreviewFontFamilyLogKeys) {
        if (!failedPreviewFontFamilyLogKeys.add(key)) return
    }
    XposedBridge.log(
        "$LOG_PREFIX failed to resolve preview font: " +
            "${displayFontName(selection)}: ${error.javaClass.simpleName}: ${error.message}",
    )
}
