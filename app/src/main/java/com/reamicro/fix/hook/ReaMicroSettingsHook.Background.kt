package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.reamicro.fix.settings.ModuleSettings
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.Locale
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors

// 我的页背景与阅读页背景的设置簇。
//
// 颜色与图片选择、裁剪位置、显示模式、模糊与透明度。
//
// 从 ReaMicroSettingsHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的
// 结果重新缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaMicroSettingsHook.profileBackgroundColorSummary(value: String): String =
    profileBackgroundArgbHex(profileBackgroundColorValue(value))

internal fun ReaMicroSettingsHook.profileBackgroundCropPositionSummary(value: String): String =
    PROFILE_BACKGROUND_CROP_POSITION_OPTIONS.firstOrNull { it.value == value }?.title
        ?: PROFILE_BACKGROUND_CROP_POSITION_OPTIONS.first().title

internal fun ReaMicroSettingsHook.profileBackgroundDisplayModeSummary(value: String): String =
    PROFILE_BACKGROUND_DISPLAY_MODE_OPTIONS.firstOrNull { it.value == value }?.title
        ?: PROFILE_BACKGROUND_DISPLAY_MODE_OPTIONS.first().title

internal fun ReaMicroSettingsHook.profileBackgroundRandomUrlSummary(value: String): String =
    value.trim().ifBlank { "\u672a\u8bbe\u7f6e" }

internal fun ReaMicroSettingsHook.renderProfileBackgroundSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ProfileBackgroundSettingsList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        profileBackgroundVersionValue()
        val snapshot = settings.snapshot()
        val imageSubtitle = if (snapshot.profileBackgroundImage.isBlank()) {
            "\u672a\u9009\u62e9"
        } else {
            snapshot.profileBackgroundImage.substringAfterLast('/')
        }
        val switchRows = listOf(
            ToggleRow(
                key = ModuleSettings.KEY_PROFILE_BACKGROUND_ENABLED,
                title = "\u56fe\u7247\u80cc\u666f",
                checked = snapshot.profileBackgroundEnabled,
                onChanged = { checked, _ ->
                    settings.setProfileBackgroundEnabled(checked)
                    if (checked) settings.setProfileBackgroundUseImage(true)
                    bumpProfileBackgroundVersion()
                    checked
                },
            ),
        )
        val imageRows = listOf(
            ActionRow(
                key = "profile_background_image_entry",
                title = "\u672c\u5730\u80cc\u666f\u56fe\u7247",
                subtitle = imageSubtitle,
                singleLineSubtitle = true,
                onClick = { openProfileBackgroundImagePicker() },
                onLongClick = if (snapshot.profileBackgroundImage.isBlank()) null else {
                    { removeProfileBackgroundImage() }
                },
            ),
            ActionRow(
                key = "profile_background_random_url_entry",
                title = "\u968f\u673a\u56fe\u7247\u94fe\u63a5",
                subtitle = profileBackgroundRandomUrlSummary(snapshot.profileBackgroundImageUrl),
                singleLineSubtitle = true,
                onClick = { importProfileBackgroundRandomUrlFromClipboard() },
                onLongClick = if (snapshot.profileBackgroundImageUrl.isBlank()) null else {
                    { clearProfileBackgroundRandomUrl() }
                },
            ),
            ActionRow(
                key = "profile_background_display_mode_entry",
                title = "\u663e\u793a\u65b9\u5f0f",
                subtitle = profileBackgroundDisplayModeSummary(snapshot.profileBackgroundDisplayMode),
                singleLineSubtitle = true,
                onClick = { openProfileBackgroundDisplayModeDialog() },
            ),
            ActionRow(
                key = "profile_background_crop_position_entry",
                title = "\u88c1\u526a\u4f4d\u7f6e",
                subtitle = profileBackgroundCropPositionSummary(snapshot.profileBackgroundCropPosition),
                singleLineSubtitle = true,
                onClick = { openProfileBackgroundCropPositionDialog() },
            ),
        )
        addLazyItem(lazyListScope, PROFILE_BACKGROUND_ENABLE_ITEM_KEY) { itemComposer ->
            renderHostSettingsCard(switchRows, itemComposer)
        }
        addLazyItem(lazyListScope, PROFILE_BACKGROUND_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(imageRows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.openProfileBackgroundColorDialog() {
    val current = profileBackgroundArgbHex(profileBackgroundColorValue(settings.snapshot().profileBackgroundColor))
    openProfileBackgroundCustomColorDialog(current)
}

internal fun ReaMicroSettingsHook.openProfileBackgroundCustomColorDialog(initialHex: String) {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(activity, colors)
            card.addView(settingsDialogTitle(activity, "\u80cc\u666f\u989c\u8272", colors))
            var pendingHex = profileBackgroundNormalizeArgbHexOrNull(initialHex)
                ?: ModuleSettings.DEFAULT_PROFILE_BACKGROUND_COLOR
            val previewRow = profileBackgroundColorPreviewRow(activity, pendingHex, colors)
            val input = settingsDialogInput(activity, "#AARRGGBB", singleLine = true, colors = colors).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                setText(pendingHex)
                selectAll()
            }
            val status = settingsDialogStatus(activity, pendingHex, colors)

            fun updatePreview(raw: String) {
                val normalized = profileBackgroundNormalizeArgbHexOrNull(raw)
                if (normalized == null) {
                    status.text = "\u989c\u8272\u503c\u683c\u5f0f\u4e0d\u6b63\u786e"
                    status.setTextColor(colors.destructiveText)
                    return
                }
                pendingHex = normalized
                status.text = normalized
                status.setTextColor(colors.body)
                previewRow.background = settingsRoundedRect(
                    profileBackgroundColorValue(normalized),
                    settingsDp(activity, 8),
                    colors.border,
                )
            }

            input.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        updatePreview(s?.toString().orEmpty())
                    }

                    override fun afterTextChanged(s: Editable?) = Unit
                },
            )
            card.addView(previewRow)
            card.addView(input)
            card.addView(status)

            val actions = settingsDialogActions(activity)
            actions.addView(
                settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral).apply {
                    setOnClickListener { dialog.dismiss() }
                },
                settingsDialogButtonParams(activity),
            )
            actions.addView(
                settingsDialogButton(activity, "\u5b8c\u6210", colors).apply {
                    setOnClickListener {
                        val normalized = profileBackgroundNormalizeArgbHexOrNull(input.text?.toString().orEmpty())
                        if (normalized == null) {
                            Toast.makeText(activity, "\u989c\u8272\u503c\u683c\u5f0f\u4e0d\u6b63\u786e", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        settings.setProfileBackgroundColor(normalized)
                        dialog.dismiss()
                        bumpProfileBackgroundVersion()
                    }
                },
                settingsDialogButtonParams(activity),
            )
            card.addView(actions)
            showSettingsDialog(dialog, card, activity)
        }.onFailure {
            XposedBridge.log("ReaMicro LSP profile custom color dialog failed: ${it.stackTraceToString()}")
        }
    }
}

internal fun ReaMicroSettingsHook.openProfileBackgroundDisplayModeDialog() {
    openProfileBackgroundOptionDialog(
        title = "\u663e\u793a\u65b9\u5f0f",
        current = settings.snapshot().profileBackgroundDisplayMode,
        options = PROFILE_BACKGROUND_DISPLAY_MODE_OPTIONS,
        onSelected = settings::setProfileBackgroundDisplayMode,
        logName = "display mode",
    )
}

internal fun ReaMicroSettingsHook.openProfileBackgroundCropPositionDialog() {
    openProfileBackgroundOptionDialog(
        title = "\u88c1\u526a\u4f4d\u7f6e",
        current = settings.snapshot().profileBackgroundCropPosition,
        options = PROFILE_BACKGROUND_CROP_POSITION_OPTIONS,
        onSelected = settings::setProfileBackgroundCropPosition,
        logName = "crop position",
    )
}

internal fun ReaMicroSettingsHook.openProfileBackgroundOptionDialog(
    title: String,
    current: String,
    options: List<HighlightColorOption>,
    onSelected: (String) -> Unit,
    logName: String,
) {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(activity, colors)
            card.addView(settingsDialogTitle(activity, title, colors))
            options.forEach { option ->
                val selected = option.value == current
                card.addView(
                    settingsDialogChoiceRow(
                        activity,
                        if (selected) "${option.title}  \u5f53\u524d" else option.title,
                        colors,
                    ) {
                        onSelected(option.value)
                        dialog.dismiss()
                        bumpProfileBackgroundVersion()
                    },
                )
            }
            val actions = settingsDialogActions(activity)
            actions.addView(
                settingsDialogButton(activity, "\u5173\u95ed", colors, SettingsDialogButtonRole.Neutral).apply {
                    setOnClickListener { dialog.dismiss() }
                },
                settingsDialogButtonParams(activity),
            )
            card.addView(actions)
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX profile background $logName dialog failed: ${it.stackTraceToString()}")
        }
    }
}

internal fun ReaMicroSettingsHook.profileBackgroundColorPreviewRow(
    context: Context,
    hex: String,
    colors: SettingsDialogColors,
): View =
    View(context).apply {
        background = settingsRoundedRect(profileBackgroundColorValue(hex), settingsDp(context, 8), colors.border)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            settingsDp(context, 52),
        ).apply { bottomMargin = settingsDp(context, 10) }
    }

internal fun ReaMicroSettingsHook.profileBackgroundNormalizeArgbHexOrNull(value: String): String? {
    val normalized = value.trim().removePrefix("#")
    if (!normalized.matches(Regex("^[0-9a-fA-F]{6}$")) &&
        !normalized.matches(Regex("^[0-9a-fA-F]{8}$"))
    ) {
        return null
    }
    val argb = when (normalized.length) {
        6 -> "FF$normalized"
        8 -> normalized
        else -> return null
    }
    return "#${argb.uppercase(Locale.US)}"
}

internal fun ReaMicroSettingsHook.profileBackgroundColorValue(hex: String): Int {
    val normalized = hex.trim().removePrefix("#")
    return runCatching {
        when (normalized.length) {
            8 -> normalized.toLong(16).toInt()
            6 -> (0xFF shl 24) or normalized.toInt(16)
            else -> 0x80000000.toInt()
        }
    }.getOrDefault(0x80000000.toInt())
}

internal fun ReaMicroSettingsHook.profileBackgroundArgbHex(argb: Int): String =
    String.format(Locale.US, "#%08X", argb)

internal fun ReaMicroSettingsHook.drawPreviewBitmapByBackgroundSize(
    canvas: Canvas,
    bitmap: Bitmap,
    dst: Rect,
    backgroundSize: String,
) {
    if (backgroundSize.contains("100% 100%", ignoreCase = true) ||
        backgroundSize.contains("stretch", ignoreCase = true)
    ) {
        canvas.drawBitmap(bitmap, null, dst, null)
        return
    }
    drawPreviewCoverBitmap(canvas, bitmap, dst)
}

internal fun ReaMicroSettingsHook.openProfileBackgroundImagePicker() {
    val activity = activityProvider() ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val chooser = Intent.createChooser(intent, "\u9009\u62e9\u80cc\u666f\u56fe\u7247").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(intent))
        }
        activity.startActivityForResult(chooser, PROFILE_BACKGROUND_IMAGE_DOCUMENT_REQUEST_CODE)
    }.onFailure {
        showToast("\u6253\u5f00\u80cc\u666f\u56fe\u7247\u9009\u62e9\u5931\u8d25")
        XposedBridge.log("$LOG_PREFIX open profile background image picker failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.importProfileBackgroundImageFromUri(activity: Activity, uri: Uri) {
    runCatching {
        val target = copyProfileBackgroundImageUri(activity, uri)
        settings.setProfileBackgroundImage(target.absolutePath)
        settings.setProfileBackgroundImageUrl("")
        settings.setProfileBackgroundEnabled(true)
        settings.setProfileBackgroundUseImage(true)
        showToast("\u5df2\u9009\u62e9\u80cc\u666f\u56fe\u7247\uff1a${target.name}")
        bumpProfileBackgroundVersion()
    }.onFailure {
        showToast(it.message ?: "\u9009\u62e9\u80cc\u666f\u56fe\u7247\u5931\u8d25")
        XposedBridge.log("$LOG_PREFIX import profile background image failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.importProfileBackgroundRandomUrlFromClipboard() {
    val activity = activityProvider() ?: return
    val url = profileBackgroundImageUrlOrNull(readClipboardText(activity))
    if (url == null) {
        showToast("\u526a\u8d34\u677f\u4e2d\u6ca1\u6709\u56fe\u7247\u94fe\u63a5")
        return
    }
    settings.setProfileBackgroundImageUrl(url)
    settings.setProfileBackgroundEnabled(true)
    settings.setProfileBackgroundUseImage(true)
    showToast("\u5df2\u4fdd\u5b58\u968f\u673a\u56fe\u7247\u94fe\u63a5")
    bumpProfileBackgroundVersion()
    ProfileBackgroundHook.refreshRandomBackgroundNow(activity, force = true)
}

internal fun ReaMicroSettingsHook.clearProfileBackgroundRandomUrl() {
    settings.setProfileBackgroundImageUrl("")
    showToast("\u5df2\u6e05\u9664\u968f\u673a\u56fe\u7247\u94fe\u63a5")
    bumpProfileBackgroundVersion()
}

internal fun ReaMicroSettingsHook.profileBackgroundImageUrlOrNull(text: String): String? {
    val candidate = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        .find(text)
        ?.value
        ?.trim()
        ?.trimEnd('.', ',', ';', '\'', '"', ')', ']', '}', '\u3002', '\uff0c')
        ?: return null
    if (candidate.length > PROFILE_BACKGROUND_IMAGE_URL_MAX_LENGTH) return null
    return candidate.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
}

internal fun ReaMicroSettingsHook.removeProfileBackgroundImage() {
    val activity = activityProvider() ?: return
    if (settings.snapshot().profileBackgroundImage.isBlank()) {
        showToast("\u672a\u6dfb\u52a0\u80cc\u666f\u56fe\u7247")
        return
    }
    runCatching {
        // \u6e05\u9664\u56fe\u7247\u6587\u4ef6\u5e76\u590d\u4f4d\u4e3b\u9875\u80cc\u666f\u76f8\u5173\u5f00\u5173\uff0c
        // \u4f7f canShowProfileBackground \u81ea\u52a8\u5931\u6548\uff0c\u6240\u6709\u4e3b\u9875\u8865\u5168\u80cc\u666f\u529f\u80fd\u4e0d\u518d\u751f\u6548\u3002
        File(activity.filesDir, "profile_background").listFiles()?.forEach { it.delete() }
        settings.setProfileBackgroundImage("")
        settings.setProfileBackgroundImageUrl("")
        settings.setProfileBackgroundUseImage(false)
        settings.setProfileBackgroundEnabled(false)
        showToast("\u5df2\u79fb\u9664\u80cc\u666f\u56fe\u7247")
        bumpProfileBackgroundVersion()
    }.onFailure {
        showToast(it.message ?: "\u79fb\u9664\u80cc\u666f\u56fe\u7247\u5931\u8d25")
        XposedBridge.log("$LOG_PREFIX remove profile background image failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.copyProfileBackgroundImageUri(activity: Activity, uri: Uri): File {
    val rawName = queryDisplayName(activity, uri) ?: uri.lastPathSegment.orEmpty()
    val extension = rawName.substringAfterLast('.', "png").lowercase()
    val safeExt = if (extension.matches(Regex("^[a-z0-9]{1,8}$"))) extension else "png"
    val displayName = "profile_background_${System.currentTimeMillis()}.$safeExt"
    val targetDir = File(activity.filesDir, "profile_background").apply { mkdirs() }
    targetDir.listFiles()?.forEach { it.delete() }
    val target = File(targetDir, displayName)
    activity.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().buffered().use { output -> input.copyTo(output) }
    } ?: error("\u65e0\u6cd5\u8bfb\u53d6\u80cc\u666f\u56fe\u7247")
    return target
}

internal fun ReaMicroSettingsHook.profileBackgroundVersionState(): Any {
    profileBackgroundVersionUiState?.let { return it }
    return mutableState(0).also { profileBackgroundVersionUiState = it }
}

internal fun ReaMicroSettingsHook.profileBackgroundVersionValue(): Int =
    (profileBackgroundVersionState().method0("getValue") as? Number)?.toInt() ?: 0

internal fun ReaMicroSettingsHook.bumpProfileBackgroundVersion() {
    val state = profileBackgroundVersionState()
    val value = (state.method0("getValue") as? Number)?.toInt() ?: 0
    state.javaClass.methods
        .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
        ?.invoke(state, value + 1)
}

internal fun ReaMicroSettingsHook.backgroundDim(composer: Any): Long =
    method(THEME_KT_CLASS, BACKGROUND_DIM_METHOD, 1).invoke(null, colorScheme(composer)) as Long
