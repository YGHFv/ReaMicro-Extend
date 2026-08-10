package com.reamicro.fix.hook

import android.app.Dialog
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import com.reamicro.fix.ai.AiApiConfig
import com.reamicro.fix.ai.AiApiStore
import de.robv.android.xposed.XposedBridge
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors

// AI 接口设置簇。
//
// AI 服务的接入配置、词典预设与配图预设。
//
// 从 ReaMicroSettingsHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的
// 结果重新缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaMicroSettingsHook.listAiApiConfigs(): List<AiApiConfig> =
    AiApiStore.list(activityProvider()?.applicationContext)

internal fun ReaMicroSettingsHook.aiApiSubtitle(config: AiApiConfig): String =
    config.model

internal fun ReaMicroSettingsHook.renderAiApiSwitch(config: AiApiConfig, composer: Any) {
    val latest = listAiApiConfigs().firstOrNull { it.id == config.id } ?: config
    val targetChecked = latest.enabled
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
    val onCheckedChange = functionProxy("AiApiSwitch${config.id}", FUNCTION1_CLASS) { args ->
        val enabled = args?.getOrNull(0) as? Boolean ?: return@functionProxy targetUnit()
        if (AiApiStore.setEnabled(activityProvider()?.applicationContext, config.id, enabled)) {
            updateChecked(enabled)
            bumpAiApiVersion()
        } else {
            updateChecked(targetChecked)
        }
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

internal fun ReaMicroSettingsHook.openAiApiConfigDialog() {
    openAiApiConfigDialog(existing = null)
}

internal fun ReaMicroSettingsHook.openAiApiConfigDialog(existing: AiApiConfig?) {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(activity, colors)
            val nameInput = settingsDialogInput(activity, "\u540d\u79f0\uff08\u9ed8\u8ba4\u6a21\u578b\u540d\uff09", singleLine = true, colors = colors).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                setText(existing?.name.orEmpty())
            }
            val baseUrlInput = settingsDialogInput(activity, "base_url", singleLine = true, colors = colors).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(existing?.baseUrl.orEmpty())
            }
            val apiKeyInput = settingsDialogInput(activity, "api_key", singleLine = true, colors = colors).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                typeface = Typeface.DEFAULT
                setHintTextColor(colors.body)
                setText(existing?.apiKey.orEmpty())
            }
            val modelInput = settingsDialogInput(activity, "model", singleLine = true, colors = colors).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                setText(existing?.model.orEmpty())
            }
            val status = settingsDialogStatus(
                activity,
                if (existing == null) {
                    "\u586b\u5b8c\u540e\u5148\u70b9\u51fb\u6d4b\u8bd5\uff0c\u957f\u6309\u5b8c\u6210\u53ef\u76f4\u63a5\u4fdd\u5b58"
                } else {
                    "\u5f53\u524d\u914d\u7f6e\u53ef\u76f4\u63a5\u5b8c\u6210\uff0c\u4fee\u6539\u540e\u9700\u8981\u91cd\u65b0\u6d4b\u8bd5\uff1b\u957f\u6309\u5b8c\u6210\u53ef\u76f4\u63a5\u4fdd\u5b58"
                },
                colors,
            )
            val progress = ProgressBar(activity).apply {
                visibility = View.GONE
                isIndeterminate = true
                ModuleDialogTheme.tintProgress(this, colors.primary)
            }
            val finishButton = settingsDialogButton(activity, "\u5b8c\u6210", colors)
            val deleteButton = existing?.let {
                settingsDialogButton(activity, "\u5220\u9664", colors, SettingsDialogButtonRole.Destructive)
            }
            val cancelButton = settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral)
            val testButton = settingsDialogButton(activity, "\u6d4b\u8bd5", colors)
            val fetchModelsButton = settingsDialogButton(activity, "\u83b7\u53d6\u6a21\u578b", colors, SettingsDialogButtonRole.Neutral)
            val fetchRow = settingsDialogActions(activity).apply {
                addView(fetchModelsButton, settingsDialogButtonParams(activity))
            }
            val actionRow = settingsDialogActions(activity).apply {
                addView(finishButton, settingsDialogButtonParams(activity))
                if (deleteButton != null) addView(deleteButton, settingsDialogButtonParams(activity))
                addView(cancelButton, settingsDialogButtonParams(activity))
                addView(testButton, settingsDialogButtonParams(activity))
            }
            card.addView(settingsDialogTitle(activity, "API \u914d\u7f6e", colors))
            card.addView(nameInput)
            card.addView(baseUrlInput)
            card.addView(apiKeyInput)
            card.addView(modelInput)
            card.addView(fetchRow)
            card.addView(status)
            card.addView(progress, settingsDialogProgressParams(activity))
            card.addView(actionRow)

            var testedBaseUrl = existing?.baseUrl.orEmpty()
            var testedApiKey = existing?.apiKey.orEmpty()
            var testedModel = existing?.model.orEmpty()
            var testing = false
            var fetchingModels = false

            deleteButton?.setOnClickListener {
                val target = existing ?: return@setOnClickListener
                if (AiApiStore.remove(activity.applicationContext, target.id)) {
                    bumpAiApiVersion()
                    showToast("\u5df2\u5220\u9664 API\uff1a${target.displayName}")
                }
                dialog.dismiss()
            }

            fun values(): AiApiDialogValues =
                AiApiDialogValues(
                    nameInput.text?.toString().orEmpty().trim(),
                    baseUrlInput.text?.toString().orEmpty().trim(),
                    apiKeyInput.text?.toString().orEmpty().trim(),
                    modelInput.text?.toString().orEmpty().trim(),
                )

            fun hasBaseAndKey(): Boolean {
                val values = values()
                return values.baseUrl.isNotBlank() && values.apiKey.isNotBlank()
            }

            fun hasAllValues(): Boolean {
                val values = values()
                return values.baseUrl.isNotBlank() && values.apiKey.isNotBlank() && values.model.isNotBlank()
            }

            fun testPassedForCurrentValues(): Boolean {
                val values = values()
                return values.baseUrl == testedBaseUrl && values.apiKey == testedApiKey && values.model == testedModel
            }

            fun refreshButtons() {
                val busy = testing || fetchingModels
                val canFetchModels = hasBaseAndKey() && !busy
                val canTest = hasAllValues() && !busy
                val canFinish = hasAllValues() && !busy
                fetchModelsButton.isEnabled = canFetchModels
                fetchModelsButton.alpha = if (canFetchModels) 1f else 0.38f
                testButton.isEnabled = canTest
                testButton.alpha = if (canTest) 1f else 0.38f
                finishButton.isEnabled = canFinish
                finishButton.alpha = when {
                    !canFinish -> 0.38f
                    testPassedForCurrentValues() -> 1f
                    else -> 0.72f
                }
                deleteButton?.isEnabled = !busy
                deleteButton?.alpha = if (busy) 0.38f else 1f
                cancelButton.isEnabled = !busy
                cancelButton.alpha = if (busy) 0.38f else 1f
            }

            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!testPassedForCurrentValues()) status.text = "\u914d\u7f6e\u5df2\u53d8\u66f4\uff0c\u9700\u8981\u91cd\u65b0\u6d4b\u8bd5"
                    refreshButtons()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            }
            baseUrlInput.addTextChangedListener(watcher)
            apiKeyInput.addTextChangedListener(watcher)
            modelInput.addTextChangedListener(watcher)

            fetchModelsButton.setOnClickListener {
                val values = values()
                if (values.baseUrl.isBlank() || values.apiKey.isBlank()) {
                    Toast.makeText(activity, "\u8bf7\u5148\u586b\u5199 base_url \u548c api_key", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                fetchingModels = true
                status.text = "\u6b63\u5728\u83b7\u53d6\u6a21\u578b\u5217\u8868..."
                progress.visibility = View.VISIBLE
                refreshButtons()
                Thread({
                    val result = AiApiStore.fetchModels(values.baseUrl, values.apiKey)
                    activity.runOnUiThread {
                        fetchingModels = false
                        progress.visibility = View.GONE
                        status.text = result.message
                        refreshButtons()
                        if (result.success && result.models.isNotEmpty()) {
                            showAiModelSelectionDialog(activity, result.models, colors) { model ->
                                modelInput.setText(model)
                                modelInput.setSelection(modelInput.text?.length ?: 0)
                                status.text = "\u5df2\u9009\u62e9\u6a21\u578b\uff0c\u8bf7\u6d4b\u8bd5\u8fde\u63a5"
                                refreshButtons()
                            }
                        }
                    }
                }, "ReaMicroAiModelFetch").start()
            }

            testButton.setOnClickListener {
                val values = values()
                if (values.baseUrl.isBlank() || values.apiKey.isBlank() || values.model.isBlank()) {
                    Toast.makeText(activity, "\u8bf7\u5148\u586b\u5b8c\u5168\u90e8\u5b57\u6bb5", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                testing = true
                status.text = "\u6b63\u5728\u6d4b\u8bd5\u8fde\u63a5..."
                progress.visibility = View.VISIBLE
                refreshButtons()
                Thread({
                    val result = AiApiStore.test(values.baseUrl, values.apiKey, values.model)
                    activity.runOnUiThread {
                        testing = false
                        progress.visibility = View.GONE
                        status.text = result.message
                        if (result.success) {
                            testedBaseUrl = values.baseUrl
                            testedApiKey = values.apiKey
                            testedModel = values.model
                        } else {
                            testedBaseUrl = ""
                            testedApiKey = ""
                            testedModel = ""
                        }
                        refreshButtons()
                    }
                }, "ReaMicroAiApiTest").start()
            }

            fun saveApiConfigDirect() {
                val values = values()
                if (values.baseUrl.isBlank() || values.apiKey.isBlank() || values.model.isBlank()) {
                    Toast.makeText(activity, "\u8bf7\u5148\u586b\u5b8c\u5168\u90e8\u5b57\u6bb5", Toast.LENGTH_SHORT).show()
                    return
                }
                val config = if (existing == null) {
                    AiApiStore.add(activity.applicationContext, values.baseUrl, values.apiKey, values.model, values.name)
                } else {
                    AiApiStore.update(activity.applicationContext, existing.id, values.baseUrl, values.apiKey, values.model, values.name)
                }
                bumpAiApiVersion()
                showToast(
                    if (existing == null) {
                        "\u5df2\u6dfb\u52a0 API\uff1a${config.displayName}"
                    } else {
                        "\u5df2\u66f4\u65b0 API\uff1a${config.displayName}"
                    },
                )
                dialog.dismiss()
            }

            finishButton.setOnClickListener {
                if (!testPassedForCurrentValues()) {
                    Toast.makeText(activity, "\u8bf7\u5148\u6d4b\u8bd5\u901a\u8fc7\uff0c\u6216\u957f\u6309\u5b8c\u6210\u76f4\u63a5\u4fdd\u5b58", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                saveApiConfigDirect()
            }
            finishButton.setOnLongClickListener {
                saveApiConfigDirect()
                true
            }
            cancelButton.setOnClickListener { dialog.dismiss() }
            refreshButtons()
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to open AI API config dialog: ${it.stackTraceToString()}")
            showToast("\u65e0\u6cd5\u6253\u5f00 API \u914d\u7f6e")
        }
    }
}

internal fun ReaMicroSettingsHook.aiApiVersionState(): Any {
    aiApiVersionUiState?.let { return it }
    return mutableState(0).also { aiApiVersionUiState = it }
}

internal fun ReaMicroSettingsHook.aiApiVersionValue(): Int =
    (aiApiVersionState().method0("getValue") as? Number)?.toInt() ?: 0

internal fun ReaMicroSettingsHook.bumpAiApiVersion() {
    val state = aiApiVersionState()
    val value = (state.method0("getValue") as? Number)?.toInt() ?: 0
    state.javaClass.methods
        .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
        ?.invoke(state, value + 1)
}
