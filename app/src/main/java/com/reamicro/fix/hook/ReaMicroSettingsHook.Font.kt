package com.reamicro.fix.hook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.xposed.HookRuntime
import java.io.File
import com.reamicro.fix.epub.editor.AccountFontFiles
import com.reamicro.fix.epub.editor.FontSelectionResolution
import com.reamicro.fix.epub.editor.VerifiedFileIO
import com.reamicro.fix.reload.runOnModuleUiThread
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors

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
                        if (setFontSelection(target, "")) navigateBackFromInjectedRoute()
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
                            if (setFontSelection(target, option.value)) navigateBackFromInjectedRoute()
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
                            if (setFontSelection(target, selection)) navigateBackFromInjectedRoute()
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
        val dialog = settingsDialog(activity)
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
        HookRuntime.log("$LOG_PREFIX open settings font selection failed: ${it.stackTraceToString()}")
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
        typeface = androidTypefaceForFontSelection(selection) ?: EmbeddedHostUi.nativeTypeface(context) ?: Typeface.DEFAULT
        if (selection.isNotBlank()) tag = "reamicro-font-preview"
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
    val activity = activityProvider() ?: return null
    val file = resolveAccountFontFile(activity, selection) ?: return null
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

internal fun ReaMicroSettingsHook.setFontSelection(target: FontPickerTarget, value: String): Boolean {
    if (value.isNotBlank() && !isBuiltinFontSelection(value)) {
        val activity = activityProvider() ?: return false
        if (resolveAccountFontFile(activity, value) == null) {
            showToast("字体不可用，请重新选择")
            return false
        }
    }
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
    return true
}

internal fun ReaMicroSettingsHook.sameFontSelection(current: String, selection: String): Boolean {
    if (current == selection) return true
    if (current.isBlank() || selection.isBlank() || isBuiltinFontSelection(current) || isBuiltinFontSelection(selection)) return false
    val activity = activityProvider() ?: return false
    val identity = AccountHostRuntime.storageSnapshot(activity) ?: return false
    return runCatching {
        val directory = AccountFontFiles.directory(activity.filesDir, identity.accountId)
        fun path(value: String): File? {
            val file = File(value)
            return when {
                file.isAbsolute -> file.canonicalFile
                file.parent == null -> File(directory, value).canonicalFile
                else -> null
            }
        }
        val first = path(current)
        first != null && first == path(selection) && AccountHostRuntime.isCurrentStorage(identity, activity)
    }.getOrDefault(false)
}

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

internal fun ReaMicroSettingsHook.resolveAccountFontFile(activity: Activity, selection: String): File? {
    val identity = AccountHostRuntime.storageSnapshot(activity) ?: return null
    return runCatching {
        AccountFontFiles.resolve(activity.filesDir, identity.accountId, selection)
            ?.takeIf { AccountHostRuntime.isCurrentStorage(identity, activity) }
    }.getOrNull()
}

internal fun ReaMicroSettingsHook.listFontFiles(): List<File> {
    val activity = activityProvider() ?: return emptyList()
    val identity = AccountHostRuntime.storageSnapshot(activity) ?: return emptyList()
    val version = fontLibraryVersionValue()
    val now = android.os.SystemClock.elapsedRealtime()
    val cached = synchronized(fontFilesCacheLock) {
        val cachedIdentity = cachedFontFilesIdentity
        if (cachedIdentity != null && cachedIdentity.accountId == identity.accountId &&
            cachedIdentity.generation == identity.generation &&
            cachedFontFilesVersion == version && now - cachedFontFilesAtMs < FONT_FILES_CACHE_WINDOW_MS) {
            cachedFontFiles
        } else null
    }
    if (cached != null) return if (AccountHostRuntime.isCurrentStorage(identity, activity)) cached else emptyList()
    val files = runCatching {
        AccountFontFiles.files(activity.filesDir, identity.accountId)
            .sortedBy { displayFontName(it.absolutePath).lowercase(java.util.Locale.ROOT) }
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to read current font library: ${it.javaClass.simpleName}")
    }.getOrDefault(emptyList())
    if (!AccountHostRuntime.isCurrentStorage(identity, activity)) return emptyList()
    synchronized(fontFilesCacheLock) {
        cachedFontFilesIdentity = identity
        cachedFontFilesVersion = version
        cachedFontFilesAtMs = now
        cachedFontFiles = files
    }
    return files
}

internal fun ReaMicroSettingsHook.openFontDocumentPicker() {
    val activity = activityProvider() ?: return
    val identity = AccountHostRuntime.storageSnapshot(activity)
    if (identity == null) {
        showToast("账号状态正在更新，请稍后重试")
        return
    }
    val canLaunch = synchronized(fontFilesCacheLock) {
        val pending = pendingFontPickIdentity
        if (pending != null && AccountHostRuntime.isCurrentStorage(pending, activity)) false
        else { pendingFontPickIdentity = identity; true }
    }
    if (!canLaunch) return
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
        check(AccountHostRuntime.isCurrentStorage(identity, activity)) { "账号已切换，请重新选择字体" }
        activity.startActivityForResult(intent, FONT_DOCUMENT_REQUEST_CODE)
    }.onFailure {
        synchronized(fontFilesCacheLock) { if (pendingFontPickIdentity === identity) pendingFontPickIdentity = null }
        showToast(it.message ?: "无法打开字体选择器")
        HookRuntime.log("$LOG_PREFIX failed to open font picker: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.consumeFontDocumentPickerResult(activity: Activity, resultCode: Int, intent: Intent?) {
    val identity = synchronized(fontFilesCacheLock) { pendingFontPickIdentity.also { pendingFontPickIdentity = null } } ?: return
    if (resultCode != Activity.RESULT_OK) return
    val uri = intent?.data ?: return
    if (!AccountHostRuntime.isCurrentStorage(identity, activity)) {
        showToast("账号已切换，请重新选择字体")
        return
    }
    copyFontUriToLibrary(activity, uri, identity)
}

internal fun ReaMicroSettingsHook.copyFontUriToLibrary(activity: Activity, uri: Uri, identity: AccountHostRuntime.Identity) {
    com.reamicro.fix.reload.ModuleResources.thread {
        runCatching {
            fun checkCurrent() {
                check(!Thread.currentThread().isInterrupted) { "字体导入已取消" }
                check(AccountHostRuntime.isCurrentStorage(identity, activity)) { "账号已切换，请重新选择字体" }
            }
            checkCurrent()
            val displayName = sanitizeFontFileName(queryDisplayName(activity, uri) ?: uri.lastPathSegment.orEmpty())
            require(isFontFileName(displayName)) { "请选择 ttf/otf 字体文件" }
            val input = activity.contentResolver.openInputStream(uri) ?: error("无法读取字体文件")
            input.use {
                AccountFontFiles.importFont(
                    activity.filesDir, identity.accountId, displayName, it,
                    checkCurrent = ::checkCurrent,
                    validate = { candidate -> Typeface.createFromFile(candidate) },
                    commit = { action -> AccountHostRuntime.withCurrentStorage(identity, activity, action) },
                )
            }
        }.onSuccess { file ->
            activity.runOnModuleUiThread {
                bumpFontLibraryVersion()
                if (AccountHostRuntime.isCurrentStorage(identity, activity)) {
                    Toast.makeText(activity, "已添加字体：${displayFontName(file.name)}", Toast.LENGTH_SHORT).show()
                }
            }
        }.onFailure { error ->
            activity.runOnModuleUiThread { showToast(error.message ?: "添加字体失败") }
            HookRuntime.log("$LOG_PREFIX failed to copy font: ${error.stackTraceToString()}")
        }
    }.start()
}

internal fun ReaMicroSettingsHook.sanitizeFontFileName(name: String): String {
    val cleaned = name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
    return cleaned.ifBlank { "font_${System.currentTimeMillis()}.ttf" }
}

internal fun ReaMicroSettingsHook.isFontFileName(name: String): Boolean = AccountFontFiles.isFontName(name)

internal fun ReaMicroSettingsHook.deleteFont(file: File) {
    val activity = activityProvider() ?: return
    val identity = AccountHostRuntime.storageSnapshot(activity) ?: return
    runCatching {
        val target = file.canonicalFile
        AccountHostRuntime.withCurrentStorage(identity, activity) {
            val directory = AccountFontFiles.directory(activity.filesDir, identity.accountId)
            require(target.parentFile == directory && isFontFileName(target.name) && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
                "字体不属于当前账号，请重新进入字体库"
            }
            require(!target.exists() || target.isFile) { "字体文件不可用" }
            if (target.exists()) check(target.delete()) { "无法移除字体，请重试" }
            VerifiedFileIO.requireMissing(target)
            clearDeletedFontSelection(target)
            setPendingDeleteFontSelection("")
        }
        bumpFontLibraryVersion()
        Toast.makeText(activity, "已移除字体：${displayFontName(target.absolutePath)}", Toast.LENGTH_SHORT).show()
    }.onFailure {
        showToast(it.message ?: "移除字体失败")
        HookRuntime.log("$LOG_PREFIX failed to delete font: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.clearDeletedFontSelection(file: File) {
    val selection = file.absolutePath
    val config = settings.fontSettings()
    if (sameFontSelection(config.globalFamily, selection)) settings.setFontGlobalFamily("")
    if (sameFontSelection(config.songMapping, selection)) settings.setFontSongMapping("")
    if (sameFontSelection(config.kaiMapping, selection)) settings.setFontKaiMapping("")
    if (sameFontSelection(settings.dialogueHighlightSettings().fontFamily, selection)) settings.setReaderDialogueHighlightFontFamily("")
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
    (stateValue(fontLibraryVersionState()) as? Number)?.toInt() ?: 0

internal fun ReaMicroSettingsHook.bumpFontLibraryVersion() {
    val state = fontLibraryVersionState()
    val value = (stateValue(state) as? Number)?.toInt() ?: 0
    setStateValue(state, value + 1)
    synchronized(fontFilesCacheLock) {
        cachedFontFilesIdentity = null
        cachedFontFilesVersion = Int.MIN_VALUE
        cachedFontFilesAtMs = 0L
        cachedFontFiles = emptyList()
    }
    synchronized(previewFontFamilyCache) { previewFontFamilyCache.clear() }
}

internal fun ReaMicroSettingsHook.pendingDeleteFontState(): Any {
    pendingDeleteFontUiState?.let { return it }
    return mutableState("").also { pendingDeleteFontUiState = it }
}

internal fun ReaMicroSettingsHook.pendingDeleteFontSelection(): String =
    stateValue(pendingDeleteFontState()) as? String ?: ""

internal fun ReaMicroSettingsHook.setPendingDeleteFontSelection(selection: String) {
    val state = pendingDeleteFontState()
    setStateValue(state, selection)
}

internal fun ReaMicroSettingsHook.clearPendingDeleteFontSelection() {
    val state = pendingDeleteFontUiState ?: return
    setStateValue(state, "")
}

internal fun ReaMicroSettingsHook.resolvePreviewFontFamily(selection: String): Any? {
    if (selection.isBlank()) return null
    if (isBuiltinFontSelection(selection)) {
        synchronized(previewFontFamilyCache) { previewFontFamilyCache[selection]?.let { return it } }
        return resolveBuiltinFontFamily(selection)?.also { family ->
            synchronized(previewFontFamilyCache) { previewFontFamilyCache[selection] = family }
        }
    }
    val activity = activityProvider() ?: return null
    val identity = AccountHostRuntime.storageSnapshot(activity) ?: return null
    val file = runCatching {
        AccountFontFiles.resolve(activity.filesDir, identity.accountId, selection)
    }.getOrNull() ?: return null
    val cacheKey = "${identity.generation}|${FontSelectionResolution.cacheKey(selection, file)}"
    val cached = synchronized(previewFontFamilyCache) { previewFontFamilyCache[cacheKey] }
    if (cached != null) return cached.takeIf { AccountHostRuntime.isCurrentStorage(identity, activity) }
    return runCatching {
        val family = com.reamicro.fix.core.HostFontFamilies.fromFile(classLoader, file) ?: return null
        if (!AccountHostRuntime.isCurrentStorage(identity, activity)) return null
        synchronized(previewFontFamilyCache) { previewFontFamilyCache[cacheKey] = family }
        family
    }.onFailure { logResolvePreviewFontFailure(selection, it) }.getOrNull()
}

internal fun ReaMicroSettingsHook.resolveBuiltinFontFamily(selection: String): Any? =
    runCatching {
        when (selection) {
            FAMILY_SYSTEM -> resolveDefaultFontFamily()
            FAMILY_SOURCE_HAN_SERIF -> com.reamicro.fix.core.HostFontFamilies.song(classLoader)
            else -> null
        }
    }.onFailure { logResolvePreviewFontFailure(selection, it) }.getOrNull()

internal fun ReaMicroSettingsHook.resolveDefaultFontFamily(): Any? =
    com.reamicro.fix.core.HostFontFamilies.system(classLoader)

internal fun ReaMicroSettingsHook.logResolvePreviewFontFailure(selection: String, error: Throwable) {
    val key = "$selection|${error.javaClass.name}|${error.message}"
    synchronized(failedPreviewFontFamilyLogKeys) {
        if (!failedPreviewFontFamilyLogKeys.add(key)) return
    }
    HookRuntime.log(
        "$LOG_PREFIX failed to resolve preview font: " +
            "${displayFontName(selection)}: ${error.javaClass.simpleName}: ${error.message}",
    )
}
