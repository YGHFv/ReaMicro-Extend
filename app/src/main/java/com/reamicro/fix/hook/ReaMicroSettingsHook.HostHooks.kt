package com.reamicro.fix.hook

import android.app.Activity
import android.content.Intent
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.hook.settings.*

internal fun ReaMicroSettingsHook.hookHostAccountSignOut() {
    AccountHostRuntime.install(activityProvider)
    com.reamicro.fix.core.HookInstallReport.install(FEATURE_ID, "accountPreferenceRestore") {
        accountController.installAdvancedSettingsRestore {
            val snapshot = settings.snapshot()
            snapshot.canRunAccountCompletion && snapshot.accountPreferenceRestoreEnabled
        }
    }
    accountController.installNativeSignOut {
        val snapshot = settings.snapshot()
        snapshot.canRunAccountCompletion && snapshot.accountPreferenceRestoreEnabled
    }
}
internal fun ReaMicroSettingsHook.hookFontDocumentPickerResult() {
    runCatching {
        val resultMethod = requireNotNull(com.reamicro.fix.core.HostBookUiBindings.metadata) { "Native activity result contract unavailable" }.method("activityResult")
        HookRuntime.hookMethod(resultMethod, object : HookCallback() {
            override fun after(param: Call) {
                val requestCode = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                val resultCode = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                val activity = param.thisObject as? Activity ?: return
                if (!com.reamicro.fix.core.HostPackages.contains(activity.packageName)) return
                val resultIntent = param.args?.getOrNull(2) as? Intent
                if (requestCode == FONT_DOCUMENT_REQUEST_CODE) {
                    consumeFontDocumentPickerResult(activity, resultCode, resultIntent)
                    return
                }
                if (resultCode != Activity.RESULT_OK) return
                val intent = resultIntent ?: return
                if (requestCode == ONLINE_SOURCE_DOCUMENT_REQUEST_CODE) {
                    importOnlineSourceDocumentResult(activity, intent)
                    return
                }
                val uri = intent.data ?: return
                when (requestCode) {
                    HIGHLIGHT_STYLE_DOCUMENT_REQUEST_CODE -> importReaderHighlightStyleFromUri(activity, uri)
                    ONLINE_EPUB_STYLE_DOCUMENT_REQUEST_CODE -> importOnlineEpubStyleFromUri(activity, uri)
                    ONLINE_EPUB_STYLE_IMAGE_DOCUMENT_REQUEST_CODE -> importOnlineEpubStyleImageFromUri(activity, uri)
                    HIGHLIGHT_NINE_PATCH_DOCUMENT_REQUEST_CODE -> importHighlightNinePatchFromUri(activity, uri)
                    PROFILE_BACKGROUND_IMAGE_DOCUMENT_REQUEST_CODE -> importProfileBackgroundImageFromUri(activity, uri)
                    ACCOUNT_CREDENTIAL_DOCUMENT_REQUEST_CODE -> importCredentialFromUri(activity, uri)
                    ACCOUNT_DATA_DOCUMENT_REQUEST_CODE -> importAccountDataFromUri(activity, uri)
                }
            }
        })
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook font picker result: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun ReaMicroSettingsHook.hookExternalSourceImportIntent() {
    runCatching {
        HookRuntime.hookAllMethods(Activity::class.java, "onCreate", object : HookCallback() {
            override fun after(param: Call) {
                val activity = param.thisObject as? Activity ?: return
                if (!com.reamicro.fix.core.HostPackages.contains(activity.packageName)) return
                consumeExternalSourceImportIntent(activity, activity.intent)
            }
        })
        HookRuntime.hookAllMethods(Activity::class.java, "onNewIntent", object : HookCallback() {
            override fun after(param: Call) {
                val activity = param.thisObject as? Activity ?: return
                if (!com.reamicro.fix.core.HostPackages.contains(activity.packageName)) return
                val intent = param.args?.getOrNull(0) as? Intent ?: activity.intent
                consumeExternalSourceImportIntent(activity, intent)
            }
        })
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook external source import intent: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun ReaMicroSettingsHook.hookModuleSettingsIntent() {
    // 热重载后宿主已编译的 super 调用可能绕过 Activity 基类，直接观察实际入口。
    val activityClass = requireNotNull(com.reamicro.fix.core.HostIntegrationBindings.activity).type("activity")
    val method = activityClass.getDeclaredMethod("onNewIntent", Intent::class.java)
    HookRuntime.hookMethod(method, object : HookCallback() {
        override fun after(param: Call) {
            val activity = param.thisObject as? Activity ?: return
            if (!com.reamicro.fix.core.HostPackages.contains(activity.packageName)) return
            val intent = param.args.getOrNull(0) as? Intent ?: activity.intent
            consumeModuleSettingsIntent(activity, intent)
        }
    })
    HookRuntime.deoptimize(method)
}
