package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.reamicro.fix.settings.OnlineEpubCssBlock
import com.reamicro.fix.settings.OnlineEpubCssBlocks
import com.reamicro.fix.settings.OnlineEpubHeaderScope
import com.reamicro.fix.settings.OnlineEpubStyle
import com.reamicro.fix.settings.OnlineEpubStyleDefaults
import com.reamicro.fix.settings.OnlineEpubStyleKind
import com.reamicro.fix.settings.OnlineEpubStyleLibrary
import com.reamicro.fix.settings.OnlineEpubStyleSettings
import com.reamicro.fix.settings.OnlineEpubStyleStore
import com.reamicro.fix.online.epub.OnlineEpubStylePreview
import com.reamicro.fix.online.epub.OnlineHeaderImageComposer
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors

// 在线补全 EPUB 样式设置簇。
//
// 样式的增删改、CSS 分段编辑、实时预览、导入导出与配图选择。
//
// 从 ReaMicroSettingsHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的
// 结果重新缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaMicroSettingsHook.openOnlineEpubStyleDialog(style: OnlineEpubStyle) {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(activity, colors)
            val nameInput = settingsDialogInput(activity, "样式名称", singleLine = true, colors = colors).apply {
                setText(style.name)
            }
            var fontSelection = style.fontFamily
            var embedFont = style.embedFont
            var assetPath = style.assetPath
            var headerScope = onlineEpubStyleSettings().headerScope
            lateinit var syncFontStatus: () -> Unit
            lateinit var syncPreview: () -> Unit
            val fontStatus = settingsDialogFontChoiceRow(activity, "", null, fontSelection, colors) {
                openSettingsFontSelectionDialog(
                    activity = activity,
                    title = "字体",
                    currentSelection = fontSelection,
                    clearTitle = "跟随全局字体",
                ) { selection ->
                    fontSelection = selection
                    syncFontStatus.invoke()
                    syncPreview.invoke()
                }
            }
            // 字体两种模式：嵌入会把字体文件复制进 EPUB，仅声明只写 font-family 名。
            val fontModeRow = onlineEpubStyleChipRow(activity)
            lateinit var syncFontModeRow: () -> Unit
            syncFontModeRow = {
                fontModeRow.removeAllViews()
                listOf(true to "嵌入 EPUB", false to "仅声明字体名").forEach { (mode, label) ->
                    fontModeRow.addView(
                        onlineEpubStyleSectionChip(activity, label, embedFont == mode, colors) {
                            embedFont = mode
                            syncFontModeRow.invoke()
                            syncPreview.invoke()
                        },
                    )
                }
            }
            // 分段草稿：切走时写回当前段，切入时载入目标段，保证每段都能完整填写。
            val drafts = LinkedHashMap<String, String>()
            OnlineEpubStyleDefaults.selectors(style.kind).forEach { drafts[it] = "" }
            OnlineEpubCssBlocks.parse(style.css.ifBlank { OnlineEpubStyleDefaults.blankCss(style.kind) })
                .forEach { drafts[it.selector] = it.declarations }
            val selectors = drafts.keys.toList()
            // 分段默认全不选中：点击某段才展开编辑框，再点一次收起。
            var activeSelector = ""
            val cssInput = settingsDialogInput(activity, "CSS 声明", singleLine = false, colors = colors).apply {
                minLines = 4
                visibility = View.GONE
            }
            val sectionRow = onlineEpubStyleChipRow(activity)
            val preview = WebView(activity).apply {
                setBackgroundColor(Color.TRANSPARENT)
                webViewClient = WebViewClient()
                // Android 11 起 allowFileAccess 默认 false，合成好的头图与设备字体都走 file://，
                // 不放开的话图片静默加载失败；头图 figure 又是 line-height:0，失败后高度归零就整个不见了。
                settings.allowFileAccess = true
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
            }
            fun composedCss(): String =
                OnlineEpubCssBlocks.compose(drafts.map { (selector, body) -> OnlineEpubCssBlock(selector, body) })
            fun draftStyle(): OnlineEpubStyle =
                style.copy(
                    name = nameInput.text?.toString()?.trim().orEmpty().ifBlank { style.name },
                    css = composedCss(),
                    fontFamily = fontSelection,
                    embedFont = embedFont,
                    assetPath = assetPath,
                )
            syncPreview = {
                preview.loadDataWithBaseURL(
                    "file:///android_asset/",
                    onlineEpubStylePreviewHtml(draftStyle()),
                    "text/html",
                    "utf-8",
                    null,
                )
            }
            // 分割装饰图与头图原图的选择入口，仅在该样式确实需要图片时出现。
            val assetButton = settingsDialogButton(activity, "", colors, SettingsDialogButtonRole.Neutral)
            lateinit var syncAssetButton: () -> Unit
            syncAssetButton = {
                val name = assetPath.takeIf { it.isNotBlank() }?.let { File(it).name }
                assetButton.text = when {
                    name == null && style.kind == OnlineEpubStyleKind.Header -> "选择头图原图"
                    name == null -> "选择装饰图"
                    else -> "更换图片：$name"
                }
            }
            assetButton.setOnClickListener {
                openOnlineEpubStyleImagePicker(activity) { picked ->
                    assetPath = picked.absolutePath
                    syncAssetButton.invoke()
                    syncPreview.invoke()
                }
            }
            // 头图套用范围，仅头图样式可见。
            val headerScopeRow = onlineEpubStyleChipRow(activity)
            lateinit var syncHeaderScopeRow: () -> Unit
            syncHeaderScopeRow = {
                headerScopeRow.removeAllViews()
                OnlineEpubHeaderScope.entries.forEach { scope ->
                    headerScopeRow.addView(
                        onlineEpubStyleSectionChip(activity, scope.title, headerScope == scope, colors) {
                            headerScope = scope
                            OnlineEpubStyleStore.setHeaderScope(onlineEpubStyleContext(), scope)
                            syncHeaderScopeRow.invoke()
                            syncPreview.invoke()
                        },
                    )
                }
            }
            lateinit var syncSectionRow: () -> Unit
            /** 展开某一段：只载入不回写，供首次进入与切段复用。 */
            fun loadSection(selector: String) {
                activeSelector = selector
                if (selector.isBlank()) {
                    cssInput.visibility = View.GONE
                } else {
                    cssInput.visibility = View.VISIBLE
                    cssInput.setText(drafts[selector].orEmpty())
                    cssInput.hint = "$selector 的 CSS 声明"
                }
                syncSectionRow.invoke()
                syncPreview.invoke()
            }
            /** 点分段：先把编辑框内容回写到当前段，再展开目标段；点已展开的段则收起。 */
            fun toggleSection(selector: String) {
                if (activeSelector.isNotBlank()) {
                    drafts[activeSelector] = cssInput.text?.toString().orEmpty().trim()
                }
                loadSection(if (activeSelector == selector) "" else selector)
            }
            syncSectionRow = {
                sectionRow.removeAllViews()
                selectors.forEach { selector ->
                    sectionRow.addView(
                        onlineEpubStyleSectionChip(
                            activity = activity,
                            text = OnlineEpubStyleDefaults.sectionLabel(style.kind, selector),
                            selected = selector == activeSelector,
                            colors = colors,
                        ) { toggleSection(selector) },
                    )
                }
            }
            syncFontStatus = {
                fontStatus.text = "字体：${dialogueHighlightFontSummary(fontSelection)}"
                fontStatus.typeface = androidTypefaceForFontSelection(fontSelection) ?: Typeface.DEFAULT
            }
            cssInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (activeSelector.isBlank()) return
                    drafts[activeSelector] = s?.toString().orEmpty().trim()
                    schedulePreviewRefresh(preview) { syncPreview.invoke() }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            val exportButton = settingsDialogButton(activity, "导出", colors, SettingsDialogButtonRole.Neutral)
            val resetButton = settingsDialogButton(activity, "重置为内置", colors, SettingsDialogButtonRole.Neutral)
            val applyButton = settingsDialogButton(activity, "设为当前", colors, SettingsDialogButtonRole.Neutral)
            val finishButton = settingsDialogButton(activity, "完成", colors)
            val deleteButton = settingsDialogButton(activity, "删除", colors, SettingsDialogButtonRole.Destructive)
            val cancelButton = settingsDialogButton(activity, "取消", colors, SettingsDialogButtonRole.Neutral)
            card.addView(settingsDialogTitle(activity, style.kind.title, colors))
            card.addView(nameInput)
            // 只有标题与卷标承载成段文字，其余类别不给字体设置。
            if (style.supportsFont) {
                syncFontStatus.invoke()
                card.addView(fontStatus)
                syncFontModeRow.invoke()
                card.addView(fontModeRow)
            }
            if (style.kind == OnlineEpubStyleKind.Header) {
                card.addView(settingsDialogHint(activity, "头图套用范围", colors))
                syncHeaderScopeRow.invoke()
                card.addView(headerScopeRow)
            }
            card.addView(sectionRow)
            card.addView(cssInput)
            if (style.needsAsset || style.kind == OnlineEpubStyleKind.Header) {
                syncAssetButton.invoke()
                card.addView(
                    assetButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = settingsDp(activity, 6) },
                )
            }
            card.addView(
                preview,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    settingsDp(activity, 320),
                ).apply { bottomMargin = settingsDp(activity, 8) },
            )
            if (style.kind == OnlineEpubStyleKind.Header) {
                card.addView(
                    settingsDialogHint(activity, "头图会按该样式的蒙版自动裁切后写入成书。", colors),
                )
            }
            val builtInSource = OnlineEpubStyleLibrary.byId(style.id)?.takeIf { it.kind == style.kind }
            card.addView(
                settingsDialogButtonRow(
                    activity,
                    listOfNotNull(exportButton, resetButton.takeIf { builtInSource != null }),
                ),
            )
            val buttons = if (style.builtIn) {
                listOf(applyButton, finishButton, cancelButton)
            } else {
                listOf(deleteButton, applyButton, finishButton, cancelButton)
            }
            card.addView(settingsDialogButtonRow(activity, buttons))
            // 分段一律以收起状态进入，用户点哪段才展开哪段。
            loadSection("")
            fun edited(): OnlineEpubStyle {
                if (activeSelector.isNotBlank()) {
                    drafts[activeSelector] = cssInput.text?.toString().orEmpty().trim()
                }
                return draftStyle()
            }
            exportButton.setOnClickListener { exportOnlineEpubStyle(edited()) }
            resetButton.setOnClickListener {
                val source = builtInSource ?: return@setOnClickListener
                // 丢掉这条内置样式的全部改写记录，重新打开时读回内置库的原始 CSS。
                OnlineEpubStyleStore.resetToBuiltIn(onlineEpubStyleContext(), source.id)
                bumpOnlineEpubStyleVersion()
                dialog.dismiss()
                showToast("已重置为内置：${source.name}")
            }
            applyButton.setOnClickListener {
                val next = edited()
                saveOnlineEpubStyle(next)
                OnlineEpubStyleStore.select(onlineEpubStyleContext(), next.kind, next.id)
                bumpOnlineEpubStyleVersion()
                showToast("已设为当前${next.kind.title}")
            }
            finishButton.setOnClickListener {
                saveOnlineEpubStyle(edited())
                bumpOnlineEpubStyleVersion()
                dialog.dismiss()
            }
            deleteButton.setOnClickListener {
                OnlineEpubStyleStore.remove(onlineEpubStyleContext(), style.id)
                bumpOnlineEpubStyleVersion()
                dialog.dismiss()
            }
            cancelButton.setOnClickListener { dialog.dismiss() }
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, 0.92f)
        }.onFailure {
            showToast("打开样式配置失败")
            XposedBridge.log("$LOG_PREFIX open online epub style dialog failed: ${it.stackTraceToString()}")
        }
    }
}

/** 弹窗内横排 chip 的容器。 */
internal fun ReaMicroSettingsHook.onlineEpubStyleChipRow(activity: Activity): LinearLayout =
    LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = settingsDp(activity, 8) }
    }

/** 分段切换按钮，选中态复用设置弹窗的圆角背景。 */
internal fun ReaMicroSettingsHook.onlineEpubStyleSectionChip(
    activity: Activity,
    text: String,
    selected: Boolean,
    colors: SettingsDialogColors,
    onClick: () -> Unit,
): TextView =
    TextView(activity).apply {
        this.text = text
        textSize = 13f
        setTextColor(if (selected) colors.primaryText else colors.body)
        setSingleLine(true)
        setPadding(
            settingsDp(activity, 12),
            settingsDp(activity, 6),
            settingsDp(activity, 12),
            settingsDp(activity, 6),
        )
        background = settingsRoundedRect(
            if (selected) colors.primarySoft else Color.TRANSPARENT,
            settingsDp(activity, 14),
            colors.border,
        )
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = settingsDp(activity, 8) }
    }

/** 预览文档：与成书完全一致的 CSS，外加一层模拟阅读页的纸张外观。 */
internal fun ReaMicroSettingsHook.onlineEpubStylePreviewHtml(draft: OnlineEpubStyle): String {
    val fontFile = File(draft.fontFamily)
    val assetUrl = if (draft.kind == OnlineEpubStyleKind.Header) {
        onlineEpubHeaderPreviewUrl(draft)
    } else {
        draft.assetPath.takeIf { it.isNotBlank() && File(it).isFile }?.let { "file://$it" }.orEmpty()
    }
    return OnlineEpubStylePreview.html(
        settings = onlineEpubStyleSettings(),
        draft = draft,
        assetUrl = assetUrl,
        fontUrl = if (draft.supportsFont && fontFile.isFile) "file://${fontFile.absolutePath}" else "",
    )
}

/**
 * 头图预览：先按该样式的蒙版把原图合成一遍，预览看到的就是成书里的样子。
 *
 * 还没选原图时用示意色块合成，至少能看清蒙版裁出的形状。合成结果按样式与原图缓存，
 * 避免每次输入 CSS 都重算一遍上百万像素。
 */

internal fun ReaMicroSettingsHook.onlineEpubHeaderPreviewUrl(draft: OnlineEpubStyle): String {
    val activity = activityProvider() ?: return ""
    val source = File(draft.assetPath.trim()).takeIf { it.isFile }
    val cacheKey = "${draft.id}|${draft.maskAsset}|${source?.absolutePath.orEmpty()}|${source?.lastModified() ?: 0L}"
    onlineEpubHeaderPreviewCache[cacheKey]?.let { return it }
    val mask = draft.maskAsset.takeIf { it.isNotBlank() }?.let { asset ->
        ReaderHighlightImageAssets.decodeBitmap("asset://$asset", activity, LOG_PREFIX)
    }
    val bytes = runCatching {
        if (source != null) {
            OnlineHeaderImageComposer.compose(source, mask, draft.sampleWidth, draft.sampleHeight)
        } else {
            OnlineHeaderImageComposer.composePlaceholder(mask, draft.sampleWidth, draft.sampleHeight)
        }
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX header preview compose failed: ${it.stackTraceToString()}")
    }.getOrNull().also { mask?.recycle() } ?: return ""
    return runCatching {
        val target = File(activity.cacheDir, "rm_header_preview_${cacheKey.hashCode()}.png")
        target.writeBytes(bytes)
        "file://${target.absolutePath}".also { onlineEpubHeaderPreviewCache[cacheKey] = it }
    }.getOrDefault("")
}

internal fun ReaMicroSettingsHook.onlineEpubStyleContext(): Context? =
    activityProvider()?.applicationContext ?: activityProvider()

internal fun ReaMicroSettingsHook.onlineEpubStyleSettings(): OnlineEpubStyleSettings =
    OnlineEpubStyleStore.read(onlineEpubStyleContext())

internal fun ReaMicroSettingsHook.saveOnlineEpubStyle(style: OnlineEpubStyle) {
    OnlineEpubStyleStore.save(onlineEpubStyleContext(), style)
}

internal fun ReaMicroSettingsHook.newOnlineEpubStyle(kind: OnlineEpubStyleKind): OnlineEpubStyle {
    val index = onlineEpubStyleSettings().byKind(kind).size + 1
    return OnlineEpubStyle(
        id = "${kind.id}-custom-${System.currentTimeMillis().toString(36)}",
        kind = kind,
        name = "${kind.title} $index",
        css = OnlineEpubStyleDefaults.blankCss(kind),
    )
}

internal fun ReaMicroSettingsHook.onlineEpubStyleSummary(style: OnlineEpubStyle): String {
    val font = style.fontFamily.takeIf { it.isNotBlank() }?.let { "字体 ${dialogueHighlightFontSummary(it)}" }
    val description = style.description.takeIf { it.isNotBlank() }
        ?: "${OnlineEpubCssBlocks.parse(style.css).size} 段 CSS"
    return listOfNotNull(description, font).joinToString(" · ")
}

internal fun ReaMicroSettingsHook.onlineEpubStyleRowSubtitle(
    settings: OnlineEpubStyleSettings,
    kind: OnlineEpubStyleKind,
): String {
    val name = settings.selected(kind)?.name.orEmpty().ifBlank { "未选择" }
    return if (kind == OnlineEpubStyleKind.Header) "$name（仅保存，不写入成书）" else name
}

internal fun ReaMicroSettingsHook.exportOnlineEpubStyle(style: OnlineEpubStyle) {
    val activity = activityProvider() ?: return
    runCatching {
        val fileName = "reamicro_epub_style_${safeDownloadName(style.name)}_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".json"
        writeTextToDownloads(activity, fileName, OnlineEpubStyleStore.exportJson(style).toString(2))
        showToast("已导出到下载目录：$fileName")
    }.onFailure {
        showToast("导出样式失败")
        XposedBridge.log("$LOG_PREFIX export online epub style failed: ${it.stackTraceToString()}")
    }
}

/** 样式关联图片（分割装饰图 / 头图原图）的选择入口。 */
internal fun ReaMicroSettingsHook.openOnlineEpubStyleImagePicker(activity: Activity, onPicked: (File) -> Unit) {
    runCatching {
        pendingOnlineEpubStyleImagePick = onPicked
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp", "image/*"))
        }
        activity.startActivityForResult(intent, ONLINE_EPUB_STYLE_IMAGE_DOCUMENT_REQUEST_CODE)
    }.onFailure {
        showToast("打开图片选择失败")
        XposedBridge.log("$LOG_PREFIX open online epub style image picker failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.importOnlineEpubStyleImageFromUri(activity: Activity, uri: Uri) {
    runCatching {
        val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("读取图片失败")
        val dir = onlineEpubStyleAssetDir(activity).apply { mkdirs() }
        val extension = onlineEpubStyleImageExtension(activity, uri)
        val target = File(dir, "style_image_${System.currentTimeMillis().toString(36)}.$extension")
        target.writeBytes(bytes)
        pendingOnlineEpubStyleImagePick?.invoke(target)
        showToast("已选择图片：${target.name}")
    }.onFailure {
        showToast(it.message ?: "选择图片失败")
        XposedBridge.log("$LOG_PREFIX import online epub style image failed: ${it.stackTraceToString()}")
    }
    pendingOnlineEpubStyleImagePick = null
}

internal fun ReaMicroSettingsHook.onlineEpubStyleImageExtension(activity: Activity, uri: Uri): String =
    when (activity.contentResolver.getType(uri)?.lowercase(Locale.ROOT)) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "png"
    }

/** 样式关联图片统一放模块私有目录，导出导入都以此为落点。 */
internal fun ReaMicroSettingsHook.onlineEpubStyleAssetDir(context: Context): File =
    File(context.filesDir, ONLINE_EPUB_STYLE_ASSET_DIR)

internal fun ReaMicroSettingsHook.openOnlineEpubStyleImportPicker() {
    val activity = activityProvider() ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "*/*"))
        }
        activity.startActivityForResult(intent, ONLINE_EPUB_STYLE_DOCUMENT_REQUEST_CODE)
    }.onFailure {
        showToast("打开样式导入失败")
        XposedBridge.log("$LOG_PREFIX open online epub style import failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.importOnlineEpubStyleFromUri(activity: Activity, uri: Uri) {
    runCatching {
        val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("读取样式文件失败")
        val style = OnlineEpubStyleStore.importStyle(
            JSONObject(bytes.toString(Charsets.UTF_8)),
            onlineEpubStyleAssetDir(activity),
        ) ?: error("样式文件格式不正确")
        saveOnlineEpubStyle(style)
        bumpOnlineEpubStyleVersion()
        showToast("已导入样式：${style.name}")
    }.onFailure {
        showToast(it.message ?: "导入样式失败")
        XposedBridge.log("$LOG_PREFIX import online epub style failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.onlineEpubStyleVersionState(): Any {
    onlineEpubStyleVersionUiState?.let { return it }
    return mutableState(0).also { onlineEpubStyleVersionUiState = it }
}

internal fun ReaMicroSettingsHook.onlineEpubStyleVersionValue(): Int =
    (onlineEpubStyleVersionState().method0("getValue") as? Number)?.toInt() ?: 0

internal fun ReaMicroSettingsHook.bumpOnlineEpubStyleVersion() {
    val state = onlineEpubStyleVersionState()
    val value = (state.method0("getValue") as? Number)?.toInt() ?: 0
    state.javaClass.methods
        .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
        ?.invoke(state, value + 1)
}

/** 某一类成书样式的列表页：结构与「高亮样式」页一致。 */
internal fun ReaMicroSettingsHook.renderOnlineEpubStyleListContent(
    kind: OnlineEpubStyleKind,
    innerPaddings: Any,
    composer: Any,
) {
    val listContent = functionProxy("OnlineEpubStyleList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        fontLibraryVersionValue()
        onlineEpubStyleVersionValue()
        val styleSettings = onlineEpubStyleSettings()
        val selectedId = styleSettings.selectedId(kind)
        val rows = buildList {
            add(
                ActionRow(
                    key = "online_epub_style_add",
                    title = "添加配置",
                    subtitle = "新增一组${kind.title}",
                    singleLineSubtitle = true,
                    onClick = { openOnlineEpubStyleDialog(newOnlineEpubStyle(kind)) },
                    onLongClick = ::openOnlineEpubStyleImportPicker,
                ),
            )
            styleSettings.byKind(kind).forEach { style ->
                add(
                    ActionRow(
                        key = "online_epub_style_${style.id}",
                        title = style.name,
                        subtitle = onlineEpubStyleSummary(style),
                        trailing = if (style.id == selectedId) "当前" else null,
                        singleLineSubtitle = true,
                        onClick = { openOnlineEpubStyleDialog(style) },
                        onLongClick = { exportOnlineEpubStyle(style) },
                    ),
                )
            }
        }
        addLazyItem(lazyListScope, ONLINE_EPUB_STYLE_LIST_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}
