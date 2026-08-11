package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.ai.AiApiStore
import com.reamicro.fix.ai.AiDictionaryPreset
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.ai.AiImagePreset
import com.reamicro.fix.ai.AiImagePresetTarget
import de.robv.android.xposed.XposedBridge
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors

// 设置页弹窗构件簇。
//
// 与宿主风格一致的对话框卡片、标题、选项行、按钮、滚动容器，以及文件选择器。
//
// 从 ReaMicroSettingsHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的
// 结果重新缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaMicroSettingsHook.openHookInstallReportDialog() {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(activity, colors)
            card.addView(settingsDialogTitle(activity, "模块自检", colors))
            card.addView(
                settingsDialogChoiceRow(activity, HookInstallReport.summaryLine(), colors) {},
            )
            HookInstallReport.featureSummaries().forEach { line ->
                card.addView(settingsDialogChoiceRow(activity, line, colors) {})
            }
            val failures = HookInstallReport.failureDetails()
            if (failures.isNotEmpty()) {
                card.addView(settingsDialogTitle(activity, "失败明细", colors))
                failures.forEach { line ->
                    card.addView(settingsDialogChoiceRow(activity, line, colors) {})
                }
            }
            val actions = settingsDialogActions(activity)
            actions.addView(
                settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral).apply {
                    setOnClickListener { dialog.dismiss() }
                },
                settingsDialogButtonParams(activity),
            )
            card.addView(actions)
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX hook install report dialog failed: ${it.stackTraceToString()}")
        }
    }
}

internal fun ReaMicroSettingsHook.openAccountDataDocumentPicker() {
    val activity = activityProvider() ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/zip",
                    "application/octet-stream",
                ),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivityForResult(intent, ACCOUNT_DATA_DOCUMENT_REQUEST_CODE)
    }.onFailure {
        Toast.makeText(activity, "\u65e0\u6cd5\u6253\u5f00\u6570\u636e\u9009\u62e9\u5668", Toast.LENGTH_SHORT).show()
        XposedBridge.log("$LOG_PREFIX failed to open account data picker: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.openAccountCredentialDocumentPicker() {
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
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivityForResult(intent, ACCOUNT_CREDENTIAL_DOCUMENT_REQUEST_CODE)
    }.onFailure {
        Toast.makeText(activity, "\u65e0\u6cd5\u6253\u5f00\u51ed\u8bc1\u9009\u62e9\u5668", Toast.LENGTH_SHORT).show()
        XposedBridge.log("$LOG_PREFIX failed to open account credential picker: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.renderDictionaryApiPickerContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("DictionaryApiPickerList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        aiApiVersionValue()
        val context = activityProvider()?.applicationContext
        val dictionarySettings = AiApiStore.dictionarySettings(context)
        val configs = listAiApiConfigs()
        val rows = buildList {
            add(
                ActionRow(
                    key = "dictionary_api_follow",
                    title = "\u8ddf\u968f API \u914d\u7f6e",
                    subtitle = "\u4f7f\u7528 API \u914d\u7f6e\u9875\u4e2d\u542f\u7528\u7684 API",
                    trailing = if (dictionarySettings.apiId.isBlank()) "\u5f53\u524d" else null,
                    onClick = {
                        AiApiStore.setDictionaryApiId(context, "")
                        bumpAiApiVersion()
                        navigateBackFromInjectedRoute()
                    },
                ),
            )
            if (configs.isEmpty()) {
                add(
                    ActionRow(
                        key = "dictionary_api_empty",
                        title = "\u6682\u65e0 API",
                        subtitle = "\u8bf7\u5148\u5728 API \u914d\u7f6e\u9875\u6dfb\u52a0 API",
                    ),
                )
            } else {
                configs.forEach { config ->
                    add(
                        ActionRow(
                            key = "dictionary_api_${config.id}",
                            title = config.displayName.compactOnlineSourceLine(),
                            subtitle = aiApiSubtitle(config),
                            trailing = if (dictionarySettings.apiId == config.id) "\u5f53\u524d" else null,
                            onClick = {
                                AiApiStore.setDictionaryApiId(context, config.id)
                                bumpAiApiVersion()
                                navigateBackFromInjectedRoute()
                            },
                        ),
                    )
                }
            }
        }
        addLazyItem(lazyListScope, DICTIONARY_API_PICKER_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderDictionaryPresetPickerContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("DictionaryPresetPickerList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        aiApiVersionValue()
        val context = activityProvider()?.applicationContext
        val dictionarySettings = AiApiStore.dictionarySettings(context)
        val rows = buildList {
            add(
                ActionRow(
                    key = "dictionary_preset_add",
                    title = "\u6dfb\u52a0\u9884\u8bbe",
                    subtitle = "\u586b\u5199\u9884\u8bbe\u540d\u79f0\u548c\u63d0\u793a\u8bcd\u5185\u5bb9",
                    onClick = { openDictionaryPresetDialog() },
                ),
            )
            AiApiStore.dictionaryPresets(context).forEach { preset ->
                add(
                    ActionRow(
                        key = "dictionary_preset_${preset.id}",
                        title = preset.name.compactOnlineSourceLine(),
                        subtitle = presetPromptPreview(preset.prompt),
                        singleLineSubtitle = true,
                        trailing = if (dictionarySettings.presetId == preset.id) "\u5f53\u524d" else null,
                        onClick = {
                            AiApiStore.setDictionaryPresetId(context, preset.id)
                            bumpAiApiVersion()
                            navigateBackFromInjectedRoute()
                        },
                        onLongClick = if (preset.builtIn) null else {
                            { openDictionaryPresetDialog(preset) }
                        },
                    ),
                )
            }
        }
        addLazyItem(lazyListScope, DICTIONARY_PRESET_PICKER_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderImageApiPickerContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ImageApiPickerList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        aiApiVersionValue()
        val context = activityProvider()?.applicationContext
        val imageSettings = AiApiStore.imageSettings(context)
        val configs = listAiApiConfigs()
        val rows = buildList {
            add(
                ActionRow(
                    key = "image_api_follow",
                    title = "\u8ddf\u968f API \u914d\u7f6e",
                    subtitle = "\u4f7f\u7528 API \u914d\u7f6e\u9875\u4e2d\u542f\u7528\u7684 API",
                    trailing = if (imageSettings.apiId.isBlank()) "\u5f53\u524d" else null,
                    onClick = {
                        AiApiStore.setImageApiId(context, "")
                        bumpAiApiVersion()
                        navigateBackFromInjectedRoute()
                    },
                ),
            )
            if (configs.isEmpty()) {
                add(
                    ActionRow(
                        key = "image_api_empty",
                        title = "\u6682\u65e0 API",
                        subtitle = "\u8bf7\u5148\u5728 API \u914d\u7f6e\u9875\u6dfb\u52a0 API",
                    ),
                )
            } else {
                configs.forEach { config ->
                    add(
                        ActionRow(
                            key = "image_api_${config.id}",
                            title = config.displayName.compactOnlineSourceLine(),
                            subtitle = aiApiSubtitle(config),
                            trailing = if (imageSettings.apiId == config.id) "\u5f53\u524d" else null,
                            onClick = {
                                AiApiStore.setImageApiId(context, config.id)
                                bumpAiApiVersion()
                                navigateBackFromInjectedRoute()
                            },
                        ),
                    )
                }
            }
        }
        addLazyItem(lazyListScope, IMAGE_API_PICKER_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderImagePresetPickerContent(target: AiImagePresetTarget, innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ImagePresetPickerList${target.id}", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        aiApiVersionValue()
        val context = activityProvider()?.applicationContext
        val imageSettings = AiApiStore.imageSettings(context)
        val selectedPresetId = imagePresetId(imageSettings, target)
        val rows = buildList {
            add(
                ActionRow(
                    key = "image_${target.id}_preset_add",
                    title = "\u6dfb\u52a0\u9884\u8bbe",
                    subtitle = "\u586b\u5199\u9884\u8bbe\u540d\u79f0\u548c\u751f\u56fe\u63d0\u793a\u8bcd",
                    onClick = { openImagePresetDialog(target) },
                ),
            )
            AiApiStore.imagePresets(context, target).forEach { preset ->
                add(
                    ActionRow(
                        key = "image_${target.id}_preset_${preset.id}",
                        title = preset.name.compactOnlineSourceLine(),
                        subtitle = presetPromptPreview(preset.prompt),
                        singleLineSubtitle = true,
                        trailing = if (selectedPresetId == preset.id) "\u5f53\u524d" else null,
                        onClick = {
                            AiApiStore.setImagePresetId(context, target, preset.id)
                            bumpAiApiVersion()
                            navigateBackFromInjectedRoute()
                        },
                        onLongClick = if (preset.builtIn) null else {
                            { openImagePresetDialog(target, preset) }
                        },
                    ),
                )
            }
        }
        addLazyItem(lazyListScope, IMAGE_PRESET_PICKER_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.settingsDialogCard(context: Context, colors: SettingsDialogColors): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            settingsDp(context, 20),
            settingsDp(context, 20),
            settingsDp(context, 20),
            settingsDp(context, 18),
        )
        background = settingsRoundedRect(colors.card, settingsDp(context, 8), colors.border)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

internal fun ReaMicroSettingsHook.settingsDialogTitle(
    context: Context,
    title: String,
    colors: SettingsDialogColors,
): TextView =
    TextView(context).apply {
        text = title
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        setTextColor(colors.title)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = settingsDp(context, 14) }
    }

internal fun ReaMicroSettingsHook.settingsDialogInput(
    context: Context,
    hintText: String,
    singleLine: Boolean,
    colors: SettingsDialogColors,
): EditText =
    EditText(context).apply {
        hint = hintText
        textSize = 14f
        setSingleLine(singleLine)
        minHeight = settingsDp(context, 46)
        setTextColor(colors.title)
        setHintTextColor(colors.body)
        setPadding(
            settingsDp(context, 12),
            settingsDp(context, 8),
            settingsDp(context, 12),
            settingsDp(context, 8),
        )
        background = settingsRoundedRect(colors.field, settingsDp(context, 8), colors.border)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = settingsDp(context, 10) }
    }

internal fun ReaMicroSettingsHook.settingsDialogStatus(
    context: Context,
    message: String,
    colors: SettingsDialogColors,
): TextView =
    TextView(context).apply {
        text = message
        textSize = 13f
        setTextColor(colors.body)
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = settingsDp(context, 10) }
    }

internal fun ReaMicroSettingsHook.settingsDialogActions(context: Context): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = settingsDp(context, 4) }
    }

internal fun ReaMicroSettingsHook.settingsDialogButton(
    context: Context,
    title: String,
    colors: SettingsDialogColors,
    role: SettingsDialogButtonRole = SettingsDialogButtonRole.Primary,
): TextView =
    TextView(context).apply {
        text = title
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setSingleLine(true)
        minHeight = settingsDp(context, 44)
        setPadding(settingsDp(context, 8), settingsDp(context, 10), settingsDp(context, 8), settingsDp(context, 10))
        when (role) {
            SettingsDialogButtonRole.Primary -> {
                setTextColor(colors.primaryText)
                background = settingsRoundedRect(colors.primarySoft, settingsDp(context, 8), colors.border)
            }
            SettingsDialogButtonRole.Neutral -> {
                setTextColor(colors.neutralText)
                background = settingsRoundedRect(colors.neutralSoft, settingsDp(context, 8), colors.border)
            }
            SettingsDialogButtonRole.Destructive -> {
                setTextColor(colors.destructiveText)
                background = settingsRoundedRect(colors.destructiveSoft, settingsDp(context, 8), colors.border)
            }
        }
    }

internal fun ReaMicroSettingsHook.settingsDialogButtonParams(context: Context): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        leftMargin = settingsDp(context, 4)
        rightMargin = settingsDp(context, 4)
    }

internal fun ReaMicroSettingsHook.settingsDialogButtonRow(context: Context, buttons: List<TextView>): LinearLayout =
    settingsDialogActions(context).apply {
        buttons.forEach { button ->
            addView(button, settingsDialogButtonParams(context))
        }
    }

internal fun ReaMicroSettingsHook.settingsDialogHint(
    context: Context,
    message: String,
    colors: SettingsDialogColors,
): TextView =
    settingsDialogStatus(context, message, colors)

/** 弹窗内的开关行，外观与在线源配置弹窗里的策略开关一致。 */
internal fun ReaMicroSettingsHook.settingsDialogSwitchRow(
    context: Context,
    title: String,
    checked: Boolean,
    colors: SettingsDialogColors,
): Switch =
    Switch(context).apply {
        text = title
        textSize = 14f
        setTextColor(colors.title)
        isChecked = checked
        showText = false
        gravity = Gravity.CENTER_VERTICAL
        minHeight = settingsDp(context, 48)
        setPadding(
            settingsDp(context, 12),
            settingsDp(context, 6),
            settingsDp(context, 8),
            settingsDp(context, 6),
        )
        background = settingsRoundedRect(colors.field, settingsDp(context, 8), colors.border)
        // 配色复用书源配置弹窗的策略开关，两处观感保持一致。
        thumbTintList = onlineSourcePolicySwitchThumbColors(colors)
        trackTintList = onlineSourcePolicySwitchTrackColors(colors)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = settingsDp(context, 8) }
    }

internal fun ReaMicroSettingsHook.settingsDialogProgressParams(context: Context): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        bottomMargin = settingsDp(context, 10)
    }

internal fun ReaMicroSettingsHook.settingsDialogScroll(context: Context, card: LinearLayout): ScrollView =
    ScrollView(context).apply {
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(card)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

internal fun ReaMicroSettingsHook.showSettingsDialog(dialog: Dialog, content: View, activity: Activity, widthRatio: Float = 0.9f) {
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(content)
    dialog.window?.let { window ->
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0.46f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }
    dialog.show()
    dialog.window?.let { window ->
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0.46f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setLayout(
            (activity.resources.displayMetrics.widthPixels * widthRatio).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}

internal fun ReaMicroSettingsHook.showAiModelSelectionDialog(
    activity: Activity,
    models: List<String>,
    colors: SettingsDialogColors,
    onSelected: (String) -> Unit,
) {
    val dialog = Dialog(activity)
    val card = settingsDialogCard(activity, colors)
    card.addView(settingsDialogTitle(activity, "\u9009\u62e9\u6a21\u578b", colors))
    models.forEach { model ->
        card.addView(
            settingsDialogChoiceRow(activity, model, colors) {
                onSelected(model)
                dialog.dismiss()
            },
        )
    }
    showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, 0.92f)
}

internal fun ReaMicroSettingsHook.settingsDialogChoiceRow(
    context: Context,
    title: String,
    colors: SettingsDialogColors,
    onClick: () -> Unit,
): TextView =
    TextView(context).apply {
        text = title
        textSize = 14f
        setTextColor(colors.title)
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

internal fun ReaMicroSettingsHook.openDictionaryPresetDialog() {
    openDictionaryPresetDialog(existing = null)
}

internal fun ReaMicroSettingsHook.openDictionaryPresetDialog(existing: AiDictionaryPreset?) {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(activity, colors)
            val nameInput = settingsDialogInput(activity, "\u9884\u8bbe\u540d\u79f0", singleLine = true, colors = colors).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                setText(existing?.name.orEmpty())
            }
            val promptInput = settingsDialogInput(
                activity,
                "\u63d0\u793a\u8bcd\u5185\u5bb9\uff0c\u53ef\u7528 {{text}} \u4ee3\u8868\u9009\u4e2d\u6587\u672c",
                singleLine = false,
                colors = colors,
            ).apply {
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 5
                maxLines = 8
                gravity = Gravity.TOP or Gravity.START
                setText(existing?.prompt.orEmpty())
            }
            val finishButton = settingsDialogButton(activity, "\u5b8c\u6210", colors)
            val deleteButton = existing?.takeUnless { it.builtIn }?.let {
                settingsDialogButton(activity, "\u5220\u9664", colors, SettingsDialogButtonRole.Destructive)
            }
            val cancelButton = settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral)
            val actionRow = settingsDialogActions(activity).apply {
                addView(finishButton, settingsDialogButtonParams(activity))
                if (deleteButton != null) addView(deleteButton, settingsDialogButtonParams(activity))
                addView(cancelButton, settingsDialogButtonParams(activity))
            }
            card.addView(settingsDialogTitle(activity, "\u8bcd\u5178\u9884\u8bbe", colors))
            card.addView(nameInput)
            card.addView(promptInput)
            card.addView(actionRow)

            fun hasAllValues(): Boolean =
                nameInput.text?.toString()?.trim()?.isNotBlank() == true &&
                    promptInput.text?.toString()?.trim()?.isNotBlank() == true

            fun refreshButtons() {
                val canFinish = hasAllValues()
                finishButton.isEnabled = canFinish
                finishButton.alpha = if (canFinish) 1f else 0.38f
            }

            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    refreshButtons()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            }
            nameInput.addTextChangedListener(watcher)
            promptInput.addTextChangedListener(watcher)

            deleteButton?.setOnClickListener {
                val target = existing ?: return@setOnClickListener
                if (AiApiStore.removeDictionaryPreset(activity.applicationContext, target.id)) {
                    bumpAiApiVersion()
                    showToast("\u5df2\u5220\u9664\u9884\u8bbe\uff1a${target.name}")
                }
                dialog.dismiss()
            }
            finishButton.setOnClickListener {
                val name = nameInput.text?.toString().orEmpty()
                val prompt = promptInput.text?.toString().orEmpty()
                runCatching {
                    if (existing == null) {
                        AiApiStore.addDictionaryPreset(activity.applicationContext, name, prompt)
                    } else {
                        AiApiStore.updateDictionaryPreset(activity.applicationContext, existing.id, name, prompt)
                    }
                }.onSuccess { preset ->
                    bumpAiApiVersion()
                    showToast(
                        if (existing == null) {
                            "\u5df2\u6dfb\u52a0\u9884\u8bbe\uff1a${preset.name}"
                        } else {
                            "\u5df2\u66f4\u65b0\u9884\u8bbe\uff1a${preset.name}"
                        },
                    )
                    dialog.dismiss()
                }.onFailure { error ->
                    Toast.makeText(activity, error.message ?: "\u65e0\u6cd5\u4fdd\u5b58\u9884\u8bbe", Toast.LENGTH_SHORT).show()
                }
            }
            cancelButton.setOnClickListener { dialog.dismiss() }
            refreshButtons()
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to open dictionary preset dialog: ${it.stackTraceToString()}")
            showToast("\u65e0\u6cd5\u6253\u5f00\u8bcd\u5178\u9884\u8bbe")
        }
    }
}

internal fun ReaMicroSettingsHook.openImagePresetDialog(target: AiImagePresetTarget) {
    openImagePresetDialog(target, existing = null)
}

internal fun ReaMicroSettingsHook.openImagePresetDialog(target: AiImagePresetTarget, existing: AiImagePreset?) {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        runCatching {
            val actualTarget = existing?.target ?: target
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(activity, colors)
            val nameInput = settingsDialogInput(activity, "\u9884\u8bbe\u540d\u79f0", singleLine = true, colors = colors).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                setText(existing?.name.orEmpty())
            }
            val promptInput = settingsDialogInput(
                activity,
                "\u751f\u56fe\u63d0\u793a\u8bcd\uff0c\u53ef\u7528 {{text}} \u4ee3\u8868\u751f\u6210\u4e3b\u9898",
                singleLine = false,
                colors = colors,
            ).apply {
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 5
                maxLines = 8
                gravity = Gravity.TOP or Gravity.START
                setText(existing?.prompt.orEmpty())
            }
            val finishButton = settingsDialogButton(activity, "\u5b8c\u6210", colors)
            val deleteButton = existing?.takeUnless { it.builtIn }?.let {
                settingsDialogButton(activity, "\u5220\u9664", colors, SettingsDialogButtonRole.Destructive)
            }
            val cancelButton = settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral)
            val actionRow = settingsDialogActions(activity).apply {
                addView(finishButton, settingsDialogButtonParams(activity))
                if (deleteButton != null) addView(deleteButton, settingsDialogButtonParams(activity))
                addView(cancelButton, settingsDialogButtonParams(activity))
            }
            card.addView(settingsDialogTitle(activity, actualTarget.title, colors))
            card.addView(nameInput)
            card.addView(promptInput)
            card.addView(actionRow)

            fun hasAllValues(): Boolean =
                nameInput.text?.toString()?.trim()?.isNotBlank() == true &&
                    promptInput.text?.toString()?.trim()?.isNotBlank() == true

            fun refreshButtons() {
                val canFinish = hasAllValues()
                finishButton.isEnabled = canFinish
                finishButton.alpha = if (canFinish) 1f else 0.38f
            }

            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    refreshButtons()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            }
            nameInput.addTextChangedListener(watcher)
            promptInput.addTextChangedListener(watcher)

            deleteButton?.setOnClickListener {
                val targetPreset = existing ?: return@setOnClickListener
                if (AiApiStore.removeImagePreset(activity.applicationContext, targetPreset.id)) {
                    bumpAiApiVersion()
                    showToast("\u5df2\u5220\u9664\u9884\u8bbe\uff1a${targetPreset.name}")
                }
                dialog.dismiss()
            }
            finishButton.setOnClickListener {
                val name = nameInput.text?.toString().orEmpty()
                val prompt = promptInput.text?.toString().orEmpty()
                runCatching {
                    if (existing == null) {
                        AiApiStore.addImagePreset(activity.applicationContext, actualTarget, name, prompt)
                    } else {
                        AiApiStore.updateImagePreset(activity.applicationContext, existing.id, name, prompt)
                    }
                }.onSuccess { preset ->
                    bumpAiApiVersion()
                    showToast(
                        if (existing == null) {
                            "\u5df2\u6dfb\u52a0\u9884\u8bbe\uff1a${preset.name}"
                        } else {
                            "\u5df2\u66f4\u65b0\u9884\u8bbe\uff1a${preset.name}"
                        },
                    )
                    dialog.dismiss()
                }.onFailure { error ->
                    Toast.makeText(activity, error.message ?: "\u65e0\u6cd5\u4fdd\u5b58\u9884\u8bbe", Toast.LENGTH_SHORT).show()
                }
            }
            cancelButton.setOnClickListener { dialog.dismiss() }
            refreshButtons()
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to open image preset dialog: ${it.stackTraceToString()}")
            showToast("\u65e0\u6cd5\u6253\u5f00\u751f\u56fe\u9884\u8bbe")
        }
    }
}

internal fun ReaMicroSettingsHook.updateModuleDialogTheme(composer: Any) {
    runCatching {
        val scheme = colorScheme(composer)
        val pageBackground = composeColorToArgb(backgroundDim(composer))
        val rowBackground = composeColorToArgb(
            method(THEME_KT_CLASS, BACKGROUND_AUTO_METHOD, 1).invoke(null, scheme) as Long,
        )
        val border = composeColorToArgb(
            method(THEME_KT_CLASS, BORDER_VARIANT_METHOD, 1).invoke(null, scheme) as Long,
        )
        val title = composeColorToArgb(scheme.longMethod("getOnBackground"))
        val body = composeColorToArgb(
            runCatching { scheme.longMethod("getOnSurfaceVariant") }
                .getOrElse { method(THEME_KT_CLASS, "getOnBackgroundVariant", 1).invoke(null, scheme) as Long },
        )
        val primary = composeColorToArgb(scheme.longMethod("getPrimary"))
        ModuleDialogTheme.update(
            ModuleDialogTheme.Palette(
                pageBackground = pageBackground,
                rowBackground = rowBackground,
                border = border,
                title = title,
                body = body,
                primary = primary,
                primarySoft = rowBackground,
                primaryText = primary,
                neutralText = title,
                destructiveText = if (primary == 0) title else primary,
            ),
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX update module dialog theme failed: ${it.stackTraceToString()}")
    }
}
