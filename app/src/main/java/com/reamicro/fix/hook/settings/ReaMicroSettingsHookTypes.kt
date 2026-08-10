package com.reamicro.fix.hook.settings

import android.app.Activity
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.ai.AiImagePresetTarget
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.settings.OnlineEpubStyleKind
import com.reamicro.fix.settings.ReaderHighlightRule
import com.reamicro.fix.settings.ReaderHighlightStyle
import com.reamicro.fix.hook.settings.*

// 从 ReaMicroSettingsHook 提升出来的嵌套类型。
//
// 拆分成同包扩展函数后，这些类型要在多个文件里出现；提升到子包顶层配合包级
// star import，引用点无需加限定名。inner class 需要外部实例，仍留在原类里。
internal data class PreviewNineSlice(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class PreviewBoxEdges(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun scale(factor: Float): PreviewBoxEdges =
        PreviewBoxEdges(left * factor, top * factor, right * factor, bottom * factor)
}

internal data class PreviewBoxStyle(
    val paddingLeftPx: Float,
    val paddingTopPx: Float,
    val paddingRightPx: Float,
    val paddingBottomPx: Float,
    val marginLeftPx: Float,
    val marginTopPx: Float,
    val marginRightPx: Float,
    val marginBottomPx: Float,
    val borderWidthPx: Float,
    val borderColor: Int?,
    val radiusPx: Float,
    val backgroundSize: String,
)

internal class ExportProgress {
    @Volatile private var dialog: Dialog? = null
    @Volatile private var messageView: TextView? = null
    @Volatile private var pendingMessage: String? = null
    @Volatile private var canceled: Boolean = false
    @Volatile private var backWarnedAtMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    val isCancelled: Boolean
        get() = canceled

    fun attach(dialog: Dialog, messageView: TextView) {
        this.dialog = dialog
        this.messageView = messageView
        pendingMessage?.let {
            messageView.text = it
            pendingMessage = null
        }
    }

    fun handleBack(activity: Activity) {
        val now = System.currentTimeMillis()
        if (now - backWarnedAtMs <= EXPORT_CANCEL_BACK_WINDOW_MS) {
            canceled = true
            Toast.makeText(activity, "\u5df2\u53d6\u6d88\u5bfc\u51fa", Toast.LENGTH_SHORT).show()
            dismiss()
            return
        }
        backWarnedAtMs = now
        Toast.makeText(activity, "\u518d\u6b21\u8fd4\u56de\u53d6\u6d88\u5bfc\u51fa", Toast.LENGTH_SHORT).show()
    }

    fun update(message: String) {
        if (canceled) return
        val view = messageView
        if (view == null) {
            pendingMessage = message
            return
        }
        mainHandler.post {
            view.text = message
        }
    }

    fun dismiss() {
        mainHandler.post {
            runCatching {
                dialog?.takeIf { it.isShowing }?.dismiss()
            }
            dialog = null
            messageView = null
            pendingMessage = null
        }
    }

    companion object {
        private const val EXPORT_CANCEL_BACK_WINDOW_MS = 2_500L
    }
}

internal enum class SettingsDialogButtonRole {
    Primary,
    Neutral,
    Destructive,
}

internal data class AiApiDialogValues(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

internal data class OnlineSourcePolicyInputs(
    val requestsPerSecond: EditText,
    val dailyChapterLimit: EditText,
    val preferOnDemandLoading: Switch,
    val paragraphCommentsEnabled: Switch?,
)

internal enum class ExternalImportKind { ONLINE_SOURCE, TTS_SOURCE, HIGHLIGHT }

internal data class ToggleRow(
    val key: String,
    val title: String,
    val checked: Boolean,
    val checkedProvider: () -> Boolean = { checked },
    val visibleProvider: () -> Boolean = { true },
    val syncWithSnapshot: Boolean = false,
    val onChanged: (Boolean, (String, Boolean) -> Unit) -> Boolean,
)

internal data class ActionRow(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val trailing: String? = null,
    val trailingContent: ((Any) -> Unit)? = null,
    val titleFontSelection: String? = null,
    val singleLineSubtitle: Boolean = false,
    val onClick: (() -> Unit)? = null,
    val onLongClick: (() -> Unit)? = null,
)

internal data class FontSelectionOption(
    val value: String,
    val title: String,
    val subtitle: String,
)

internal data class HighlightColorOption(
    val value: String,
    val title: String,
)

internal data class ReaderHighlightBookGroupRow(
    val bookKey: String,
    val bookTitle: String,
    val subtitle: String,
)

internal data class ReaderHighlightImport(
    val styles: List<ReaderHighlightStyle>,
    val rules: List<ReaderHighlightRule>,
    val reeden: Boolean,
    val skippedDuplicates: Int = 0,
)

internal enum class FontPickerTarget(
    val title: String,
    val clearTitle: String,
    val clearSubtitle: String,
) {
    Global(
        title = "\u5168\u5c40\u5b57\u4f53",
        clearTitle = "\u8ddf\u968f\u9605\u5fae",
        clearSubtitle = "\u4e0d\u4f7f\u7528\u5168\u5c40\u5b57\u4f53",
    ),
    SongMapping(
        title = "\u5b8b\u4f53\u6620\u5c04",
        clearTitle = "\u4e0d\u6620\u5c04\u5b8b\u4f53",
        clearSubtitle = "\u4f7f\u7528\u9605\u5fae\u5185\u7f6e\u5b8b\u4f53",
    ),
    KaiMapping(
        title = "\u6977\u4f53\u6620\u5c04",
        clearTitle = "\u4e0d\u6620\u5c04\u6977\u4f53",
        clearSubtitle = "\u4f7f\u7528\u9605\u5fae\u5185\u7f6e\u6977\u4f53",
    ),
    DialogueHighlight(
        title = "\u5bf9\u8bdd\u5b57\u4f53",
        clearTitle = "\u8ddf\u968f\u5168\u5c40\u5b57\u4f53",
        clearSubtitle = "\u4f7f\u7528\u5b57\u4f53\u8865\u5168\u91cc\u7684\u5168\u5c40\u5b57\u4f53",
    ),
}

internal sealed class InjectedRoute(val title: String) {
    object ModuleSettings : InjectedRoute(MODULE_ENTRY_TITLE)
    object AssociationCompletionSettings : InjectedRoute("\u5173\u8054\u8865\u5168")
    object ReaderCompletionSettings : InjectedRoute("\u9605\u8bfb\u8865\u5168")
    object ReaderReadAloudSettings : InjectedRoute("\u542c\u4e66\u7ba1\u7406")
    object ReaderSelectionMenuSettings : InjectedRoute("\u9009\u4e2d\u83dc\u5355")
    object ReaderHighlightSettings : InjectedRoute(READER_HIGHLIGHT_SETTINGS_TITLE)
    object ReaderHighlightConfigSettings : InjectedRoute("\u9ad8\u4eae\u6837\u5f0f")
    object ReaderHighlightTextSettings : InjectedRoute("\u9ad8\u4eae\u89c4\u5219")
    // 从阅读页原生高亮界面点击"补全计划"进入的完整聚合页（复用宿主 NavHost 页面框架）。
    data class ReaderCompletionPlan(val bookKey: String, val bookTitle: String) : InjectedRoute("\u8865\u5168\u8ba1\u5212")
    data class ReaderBookHighlightRules(val bookKey: String, val bookTitle: String) : InjectedRoute(bookTitle)
    data class ReaderBookOnlyHighlightRules(val bookKey: String, val bookTitle: String) : InjectedRoute("\u5355\u4e66\u9ad8\u4eae\u89c4\u5219")
    data class ReaderBookGlobalHighlightRules(val bookKey: String, val bookTitle: String) : InjectedRoute("\u8ddf\u968f\u5168\u5c40")
    object ReaderHighlightColorPicker : InjectedRoute("\u5bf9\u8bdd\u989c\u8272")
    object ProfileBackgroundSettings : InjectedRoute("\u4e3b\u9875\u8865\u5168")
    object CloudCompletionSettings : InjectedRoute("\u4e91\u76d8\u8865\u5168")
    object RotationCompletionSettings : InjectedRoute("\u65cb\u8f6c\u8865\u5168")
    object AccountSwitch : InjectedRoute(ACCOUNT_SWITCH_TITLE)
    object OnlineCompletionSettings : InjectedRoute(ONLINE_COMPLETION_TITLE)
    object OnlineDownloadStyleSettings : InjectedRoute(ONLINE_DOWNLOAD_STYLE_TITLE)
    data class OnlineEpubStyleList(val kind: OnlineEpubStyleKind) : InjectedRoute(kind.title)
    object AiConfigSettings : InjectedRoute(AI_CONFIG_TITLE)
    object DictionarySettings : InjectedRoute(DICTIONARY_SETTINGS_TITLE)
    object DictionaryApiPicker : InjectedRoute("\u0041\u0050\u0049 \u914d\u7f6e")
    object DictionaryPresetPicker : InjectedRoute("\u8bcd\u5178\u9884\u8bbe")
    object ImageSettings : InjectedRoute(IMAGE_SETTINGS_TITLE)
    object ImageApiPicker : InjectedRoute("\u0041\u0050\u0049 \u914d\u7f6e")
    data class ImagePresetPicker(val target: AiImagePresetTarget) : InjectedRoute(target.title)
    object FontSettings : InjectedRoute(FONT_SETTINGS_TITLE)
    data class FontPicker(val target: FontPickerTarget) : InjectedRoute(target.title)
    object FontLibrary : InjectedRoute(FONT_LIBRARY_TITLE)
    object AboutCompletion : InjectedRoute(ABOUT_COMPLETION_TITLE)
}

internal data class RotationUiState(
    val autoEnabled: Boolean,
    val portraitLockEnabled: Boolean,
    val landscapeLockEnabled: Boolean,
    val reverseEnabled: Boolean,
) {
    fun isEnabled(key: String): Boolean =
        when (key) {
            ModuleSettings.KEY_ROTATION_AUTO_ENABLED -> autoEnabled
            ModuleSettings.KEY_ROTATION_PORTRAIT_LOCK_ENABLED -> portraitLockEnabled
            ModuleSettings.KEY_ROTATION_LANDSCAPE_LOCK_ENABLED -> landscapeLockEnabled
            ModuleSettings.KEY_ROTATION_REVERSE_ENABLED -> reverseEnabled
            else -> false
        }

    fun withBase(key: String, enabled: Boolean): RotationUiState =
        copy(
            autoEnabled = enabled && key == ModuleSettings.KEY_ROTATION_AUTO_ENABLED,
            portraitLockEnabled = enabled && key == ModuleSettings.KEY_ROTATION_PORTRAIT_LOCK_ENABLED,
            landscapeLockEnabled = enabled && key == ModuleSettings.KEY_ROTATION_LANDSCAPE_LOCK_ENABLED,
        )

    companion object {
        fun from(snapshot: com.reamicro.fix.settings.ModuleSettingsSnapshot): RotationUiState =
            RotationUiState(
                autoEnabled = snapshot.rotation.autoEnabled,
                portraitLockEnabled = snapshot.rotation.portraitLockEnabled,
                landscapeLockEnabled = snapshot.rotation.landscapeLockEnabled,
                reverseEnabled = snapshot.rotationReverseEnabled,
            )

        fun fromActivityOrientation(
            requestedOrientation: Int?,
            defaultReverseEnabled: Boolean,
        ): RotationUiState? =
            when (requestedOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR -> RotationUiState(
                    autoEnabled = true,
                    portraitLockEnabled = false,
                    landscapeLockEnabled = false,
                    reverseEnabled = false,
                )
                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR -> RotationUiState(
                    autoEnabled = true,
                    portraitLockEnabled = false,
                    landscapeLockEnabled = false,
                    reverseEnabled = true,
                )
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> RotationUiState(
                    autoEnabled = false,
                    portraitLockEnabled = true,
                    landscapeLockEnabled = false,
                    reverseEnabled = false,
                )
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> RotationUiState(
                    autoEnabled = false,
                    portraitLockEnabled = true,
                    landscapeLockEnabled = false,
                    reverseEnabled = true,
                )
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> RotationUiState(
                    autoEnabled = false,
                    portraitLockEnabled = false,
                    landscapeLockEnabled = true,
                    reverseEnabled = false,
                )
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> RotationUiState(
                    autoEnabled = false,
                    portraitLockEnabled = false,
                    landscapeLockEnabled = true,
                    reverseEnabled = true,
                )
                else -> if (defaultReverseEnabled) {
                    RotationUiState(
                        autoEnabled = false,
                        portraitLockEnabled = false,
                        landscapeLockEnabled = false,
                        reverseEnabled = true,
                    )
                } else {
                    null
                }
            }
    }
}
