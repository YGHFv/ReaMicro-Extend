package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import com.reamicro.fix.tts.TtsSourceEntry
import com.reamicro.fix.tts.TtsSourceStore
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import de.robv.android.xposed.XposedBridge
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors

// 朗读设置簇。
//
// 朗读引擎与音色选择、翻页重启、音频焦点、Lyricon 歌词卡片开关。
//
// 从 ReaMicroSettingsHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的
// 结果重新缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaMicroSettingsHook.renderReaderReadAloudSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ReaderReadAloudSettingsList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        onlineSourceVersionValue()
        val snapshot = settings.snapshot()
        val settingRows = listOf(
            ToggleRow(
                key = ModuleSettings.KEY_READER_READ_ALOUD_IGNORE_AUDIO_FOCUS,
                title = "\u5ffd\u7565\u97f3\u9891\u7126\u70b9",
                checked = snapshot.readerReadAloudIgnoreAudioFocus,
                onChanged = { checked, _ ->
                    settings.setReaderReadAloudIgnoreAudioFocus(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_READER_READ_ALOUD_RESTART_ON_PAGE_TURN,
                title = "\u7ffb\u9875\u91cd\u65b0\u5f00\u59cb",
                checked = snapshot.readerReadAloudRestartOnPageTurn,
                onChanged = { checked, _ ->
                    settings.setReaderReadAloudRestartOnPageTurn(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_READER_READ_ALOUD_LYRICON_ENABLED,
                title = "\u8bcd\u5e55 API",
                checked = snapshot.readerReadAloudLyriconEnabled,
                onChanged = { checked, _ ->
                    settings.setReaderReadAloudLyriconEnabled(checked)
                    checked
                },
            ),
        )
        val sources = listOf(systemTtsSource()) + listTtsSources()
        val sourceRows = buildList {
            add(
                ActionRow(
                    key = "tts_source_add",
                    title = "\u6dfb\u52a0\u542c\u4e66\u6e90",
                    subtitle = "\u70b9\u51fb\u8bfb\u53d6\u526a\u8d34\u677f URL\uff0c\u652f\u6301\u5728\u7ebf JSON \u548c\u76f4\u63a5 TTS URL\uff1b\u957f\u6309\u9009\u62e9 JSON \u6587\u4ef6",
                    onClick = ::importTtsSourceFromClipboard,
                    onLongClick = ::openTtsSourceDocumentPicker,
                ),
            )
            sources.forEach { source ->
                add(
                    ActionRow(
                        key = "tts_source_${source.id}",
                        title = source.name.compactOnlineSourceLine(),
                        subtitle = ttsSourceSubtitle(source),
                        onClick = {
                            if (source.id == ModuleSettings.SYSTEM_TTS_SOURCE_ID) {
                                showToast("\u7cfb\u7edf TTS \u65e0\u9700\u7f16\u8f91")
                            } else {
                                openTtsSourceDialog(source)
                            }
                        },
                        onLongClick = if (source.id == ModuleSettings.SYSTEM_TTS_SOURCE_ID) null else {
                            { openTtsSourceDialog(source) }
                        },
                        trailingContent = { itemComposer ->
                            renderTtsSourceSwitch(source, itemComposer)
                        },
                    ),
                )
            }
        }
        addLazyItem(lazyListScope, READER_READ_ALOUD_SETTINGS_ITEM_KEY) { itemComposer ->
            renderHostSettingsCard(settingRows, itemComposer)
        }
        addLazyItem(lazyListScope, READER_READ_ALOUD_SOURCES_ITEM_KEY) { itemComposer ->
            renderHostActionCard(sourceRows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.listTtsSources(): List<TtsSourceEntry> =
    TtsSourceStore.list(activityProvider()?.applicationContext)

internal fun ReaMicroSettingsHook.systemTtsSource(): TtsSourceEntry =
    TtsSourceEntry(
        id = ModuleSettings.SYSTEM_TTS_SOURCE_ID,
        name = "\u7cfb\u7edf TTS",
        fileName = "",
        url = "",
        contentType = "Android TextToSpeech",
        concurrentRate = "",
        loginUrl = "",
        loginUi = "",
        loginCheckJs = "",
        header = "",
        enabledCookieJar = false,
        origin = "system",
    )

internal fun ReaMicroSettingsHook.ttsSourceSubtitle(source: TtsSourceEntry): String {
    val tags = mutableListOf<String>()
    tags += if (settings.isTtsSourceEnabled(source.id)) "\u5df2\u542f\u7528" else "\u672a\u542f\u7528"
    if (source.id == ModuleSettings.SYSTEM_TTS_SOURCE_ID) {
        tags += "\u8c03\u7528 Android \u7cfb\u7edf TTS"
        return tags.joinToString(" \u00b7 ")
    }
    val rate = source.concurrentRate
        .compactOnlineSourceLine()
        .takeIf { it.isNotBlank() && it != "0" }
        ?.let { "\u9891\u63a7 $it" }
    rate?.let { tags += it }
    if (source.contentType.isNotBlank()) tags += source.contentType.compactOnlineSourceLine()
    return tags.joinToString(" \u00b7 ")
}

internal fun ReaMicroSettingsHook.setOnlyTtsSourceEnabled(sourceId: String?) {
    settings.setTtsSourceEnabled(ModuleSettings.SYSTEM_TTS_SOURCE_ID, sourceId == ModuleSettings.SYSTEM_TTS_SOURCE_ID)
    listTtsSources().forEach { source ->
        settings.setTtsSourceEnabled(source.id, source.id == sourceId)
    }
}

internal fun ReaMicroSettingsHook.openTtsSourceDialog(source: TtsSourceEntry) {
    val activity = activityProvider() ?: return
    if (source.id == ModuleSettings.SYSTEM_TTS_SOURCE_ID) {
        showToast("\u7cfb\u7edf TTS \u65e0\u9700\u7f16\u8f91")
        return
    }
    runCatching {
        val colors = SettingsDialogColors(activity)
        val dialog = Dialog(activity)
        val card = settingsDialogCard(activity, colors)
        val nameInput = settingsDialogInput(activity, "\u540d\u79f0", singleLine = true, colors = colors).apply {
            setText(source.name)
        }
        val urlInput = settingsDialogInput(
            activity,
            "URL\uff0c\u652f\u6301 {{speakText}} \u5360\u4f4d",
            singleLine = false,
            colors = colors,
        ).apply {
            minLines = 3
            setText(source.url)
        }
        val contentTypeInput = settingsDialogInput(activity, "Content-Type / Accept\uff0c\u4f8b\u5982 audio/mpeg", singleLine = true, colors = colors).apply {
            setText(source.contentType)
        }
        val rateInput = settingsDialogInput(activity, "\u9891\u63a7\uff08\u53ef\u7559\u7a7a\uff09", singleLine = true, colors = colors).apply {
            setText(source.concurrentRate)
        }
        val headerInput = settingsDialogInput(activity, "Header JSON \u6216 Key: Value\uff08\u53ef\u7559\u7a7a\uff09", singleLine = false, colors = colors).apply {
            minLines = 3
            setText(source.header)
        }
        val status = settingsDialogStatus(activity, "\u4fdd\u5b58\u540e\u81ea\u52a8\u542f\u7528\u8be5\u542c\u4e66\u6e90\uff0c\u5176\u4ed6\u6e90\u4f1a\u5173\u95ed\u3002", colors)
        val cancelButton = settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral)
        val deleteButton = settingsDialogButton(activity, "\u5220\u9664", colors, SettingsDialogButtonRole.Destructive)
        val saveButton = settingsDialogButton(activity, "\u4fdd\u5b58", colors)
        cancelButton.setOnClickListener { dialog.dismiss() }
        deleteButton.setOnClickListener {
            settings.setTtsSourceEnabled(source.id, false)
            val removed = TtsSourceStore.remove(activity.applicationContext, source.id)
            dialog.dismiss()
            bumpOnlineSourceVersion()
            showToast(if (removed) "\u5df2\u79fb\u9664 TTS\uff1a${source.name}" else "TTS \u79fb\u9664\u5931\u8d25\uff1a${source.name}")
        }
        saveButton.setOnClickListener {
            val updated = source.copy(
                name = nameInput.text?.toString().orEmpty().trim().ifBlank { source.name },
                url = urlInput.text?.toString().orEmpty().trim(),
                contentType = contentTypeInput.text?.toString().orEmpty().trim(),
                concurrentRate = rateInput.text?.toString().orEmpty().trim(),
                header = headerInput.text?.toString().orEmpty().trim(),
            )
            runCatching {
                TtsSourceStore.save(activity.applicationContext, updated)
            }.onSuccess { saved ->
                setOnlyTtsSourceEnabled(saved.id)
                dialog.dismiss()
                bumpOnlineSourceVersion()
                showToast("\u5df2\u4fdd\u5b58\u5e76\u542f\u7528 TTS\uff1a${saved.name}")
            }.onFailure {
                status.text = it.message ?: "TTS \u4fdd\u5b58\u5931\u8d25"
            }
        }
        card.addView(settingsDialogTitle(activity, "\u542c\u4e66\u6e90", colors))
        card.addView(nameInput)
        card.addView(urlInput)
        card.addView(contentTypeInput)
        card.addView(rateInput)
        card.addView(headerInput)
        card.addView(status)
        card.addView(settingsDialogButtonRow(activity, listOf(deleteButton, cancelButton, saveButton)))
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, 0.94f)
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX tts source dialog failed: ${it.stackTraceToString()}")
        showToast("TTS \u7f16\u8f91\u5931\u8d25")
    }
}

internal fun ReaMicroSettingsHook.armTtsSourceRemoval(source: TtsSourceEntry) {
    pendingDeleteTtsSourceId = source.id
    pendingDeleteTtsSourceAtMs = SystemClock.elapsedRealtime()
    showToast("\u518d\u6b21\u70b9\u51fb\u79fb\u9664 TTS\uff1a${source.name}")
}

internal fun ReaMicroSettingsHook.confirmOrRemoveTtsSource(source: TtsSourceEntry) {
    val now = SystemClock.elapsedRealtime()
    if (
        pendingDeleteTtsSourceId == source.id &&
        now - pendingDeleteTtsSourceAtMs <= ONLINE_SOURCE_REMOVE_CONFIRM_WINDOW_MS
    ) {
        settings.setTtsSourceEnabled(source.id, false)
        val removed = TtsSourceStore.remove(activityProvider()?.applicationContext, source.id)
        pendingDeleteTtsSourceId = ""
        pendingDeleteTtsSourceAtMs = 0L
        if (removed) {
            bumpOnlineSourceVersion()
            showToast("\u5df2\u79fb\u9664 TTS\uff1a${source.name}")
        } else {
            showToast("TTS \u79fb\u9664\u5931\u8d25\uff1a${source.name}")
        }
    } else {
        armTtsSourceRemoval(source)
    }
}

internal fun ReaMicroSettingsHook.renderTtsSourceSwitch(source: TtsSourceEntry, composer: Any) {
    val targetChecked = settings.isTtsSourceEnabled(source.id)
    val state = rememberBooleanState(composer, targetChecked)
    fun updateChecked(value: Boolean) {
        state.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, value)
    }
    val rememberedChecked = state.method0("getValue") as? Boolean ?: targetChecked
    val checked = if (rememberedChecked != targetChecked) {
        updateChecked(targetChecked)
        targetChecked
    } else {
        rememberedChecked
    }
    val onCheckedChange = functionProxy("TtsSourceSwitch${source.id}", FUNCTION1_CLASS) { args ->
        val enabled = args?.getOrNull(0) as? Boolean ?: return@functionProxy targetUnit()
        if (enabled) {
            setOnlyTtsSourceEnabled(source.id)
        } else {
            settings.setTtsSourceEnabled(source.id, false)
        }
        updateChecked(enabled)
        bumpOnlineSourceVersion()
        targetUnit()
    }
    method(SWITCH_KT_CLASS, SWITCH_METHOD, 10).invoke(
        null,
        checked,
        onCheckedChange,
        switchModifier(),
        null,
        false,
        switchColors(composer),
        null,
        composer,
        0,
        88,
    )
}

internal fun ReaMicroSettingsHook.importTtsSourceFromClipboard() {
    val activity = activityProvider() ?: return
    val url = readClipboardText(activity).trim()
    if (url.isBlank()) {
        showToast("\u526a\u8d34\u677f\u4e2d\u6ca1\u6709\u542c\u4e66\u6e90 URL")
        return
    }
    importTtsSourceFromUrl(activity, url)
}

internal fun ReaMicroSettingsHook.importTtsSourceFromUrl(activity: Activity, url: String) {
    val token = "tts_clipboard:$url"
    val now = System.currentTimeMillis()
    if (token == lastOnlineSourceImportToken && now - lastOnlineSourceImportAtMs < ONLINE_SOURCE_IMPORT_DEDUPE_WINDOW_MS) {
        return
    }
    lastOnlineSourceImportToken = token
    lastOnlineSourceImportAtMs = now
    Thread {
        runCatching { TtsSourceStore.importFromUrl(activity.applicationContext, url) }
            .onSuccess { sources ->
                activity.runOnUiThread {
                    setOnlyTtsSourceEnabled(sources.firstOrNull()?.id)
                    bumpOnlineSourceVersion()
                    showToast("\u5df2\u6dfb\u52a0 TTS\uff1a${sources.joinToString { it.name }}")
                }
            }
            .onFailure {
                XposedBridge.log("$LOG_PREFIX tts source url import failed: ${it.stackTraceToString()}")
                activity.runOnUiThread {
                    showToast(it.message ?: "TTS \u5bfc\u5165\u5931\u8d25")
                }
            }
    }.apply {
        name = "ReaMicroTtsSourceUrlImport"
        isDaemon = true
        start()
    }
}

internal fun ReaMicroSettingsHook.openTtsSourceDocumentPicker() {
    val activity = activityProvider() ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/json",
                    "text/plain",
                    "application/octet-stream",
                ),
            )
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivityForResult(intent, READ_ALOUD_SOURCE_DOCUMENT_REQUEST_CODE)
    }.onFailure {
        Toast.makeText(activity, "\u65e0\u6cd5\u6253\u5f00\u542c\u4e66\u6e90\u9009\u62e9\u5668", Toast.LENGTH_SHORT).show()
        XposedBridge.log("$LOG_PREFIX failed to open tts source picker: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.importTtsSourceDocumentResult(activity: Activity, intent: Intent) {
    val clipData = intent.clipData
    if (clipData != null && clipData.itemCount != 1) {
        showToast("\u4e00\u6b21\u6700\u591a\u5bfc\u5165\u4e00\u4e2a\u542c\u4e66\u6e90\u6587\u4ef6")
        return
    }
    val uri = clipData?.getItemAt(0)?.uri ?: intent.data
    if (uri == null) {
        showToast("\u672a\u9009\u62e9\u542c\u4e66\u6e90\u6587\u4ef6")
        return
    }
    val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: run {
            showToast("\u65e0\u6cd5\u8bfb\u53d6\u542c\u4e66\u6e90\u6587\u4ef6")
            return
        }
    val displayName = queryDisplayName(activity, uri)
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "tts_source.json"
    importTtsSourceAsync(activity, "tts_uri:$uri") {
        TtsSourceStore.importBytes(activity.applicationContext, bytes, displayName, uri.toString())
    }
}

internal fun ReaMicroSettingsHook.importTtsSourceAsync(
    activity: Activity,
    token: String,
    importer: () -> List<TtsSourceEntry>,
) {
    val now = System.currentTimeMillis()
    if (token == lastOnlineSourceImportToken && now - lastOnlineSourceImportAtMs < ONLINE_SOURCE_IMPORT_DEDUPE_WINDOW_MS) {
        return
    }
    lastOnlineSourceImportToken = token
    lastOnlineSourceImportAtMs = now
    Thread {
        runCatching { importer() }
            .onSuccess { sources ->
                activity.runOnUiThread {
                    setOnlyTtsSourceEnabled(sources.firstOrNull()?.id)
                    bumpOnlineSourceVersion()
                    showToast("\u5df2\u6dfb\u52a0 TTS\uff1a${sources.joinToString { it.name }}")
                }
            }
            .onFailure {
                XposedBridge.log("$LOG_PREFIX tts source import failed: ${it.stackTraceToString()}")
                activity.runOnUiThread {
                    showToast(it.message ?: "TTS \u5bfc\u5165\u5931\u8d25")
                }
            }
    }.apply {
        name = "ReaMicroTtsSourceImport"
        isDaemon = true
        start()
    }
}

internal fun ReaMicroSettingsHook.readerReadAloudSettingsSummary(snapshot: ModuleSettingsSnapshot): String {
    val lyricon = if (snapshot.readerReadAloudLyriconEnabled) "\u8bcd\u5e55\u5df2\u542f\u7528" else "\u8bcd\u5e55\u672a\u542f\u7528"
    val enabledSource = (listOf(systemTtsSource()) + listTtsSources())
        .firstOrNull { settings.isTtsSourceEnabled(it.id) }
        ?.name
        ?: "\u672a\u9009\u62e9\u542c\u4e66\u6e90"
    return "$lyricon / $enabledSource"
}

internal fun ReaMicroSettingsHook.importExternalTtsSource(activity: Activity, bytes: ByteArray, displayName: String) {
    importTtsSourceAsync(activity, "external_tts:$displayName:${bytes.size}") {
        TtsSourceStore.importBytes(activity.applicationContext, bytes, displayName, "import:external")
    }
}
