package com.reamicro.fix.hook

import android.app.Activity
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import com.reamicro.fix.hook.settings.*

// ReaMicroSettingsHook 的宿主 hook 安装簇。
//
// 所有 hookXxx()：把模块的设置项挂进宿主设置页的列表与导航图。
//
// 从 ReaMicroSettingsHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的
// 结果重新缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaMicroSettingsHook.hookStringResource() {
    runCatching {
        val stringResourcesClass = cls(STRING_RESOURCES_CLASS)
        XposedBridge.hookAllMethods(stringResourcesClass, STRING_RESOURCE_METHOD, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val overrideTitle = settingsEntryTitleOverride.get() ?: return
                if (param.result == HOST_ABOUT_TITLE) {
                    param.result = overrideTitle
                }
            }
        })
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook stringResource: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.hookNavGraphScope() {
    runCatching {
        val navGraphScopeClass = cls(NAV_GRAPH_SCOPE_CLASS)
        XposedBridge.hookAllMethods(navGraphScopeClass, "navigate", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 缓存 NavGraphScope 单例，供阅读页高亮页导航兜底使用。
                param.thisObject?.let { lastKnownNavGraphScope = it }
                val route = param.args?.getOrNull(0) ?: return
                if (route.javaClass.name == ROUTE_ABOUT_CLASS && navigatingModuleRoute.get() != true) {
                    injectedRouteStack = emptyList()
                    setInjectedRouteState(null)
                    clearPendingDeleteFontSelection()
                }
            }
        })
        XposedBridge.hookAllMethods(navGraphScopeClass, "popBackStack", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // Injected module pages reuse the host About route, so back navigation must
                // consume our nested stack before the host NavController pops its own route.
                if (poppingInjectedRoute.get() == true) return
                if (handleNestedInjectedBack()) {
                    param.result = true
                    return
                }
                val nextStack = injectedRouteStack.dropLast(1)
                injectedRouteStack = nextStack
                setInjectedRouteState(nextStack.lastOrNull())
            }
        })
        XposedBridge.hookAllMethods(cls(NAV_CONTROLLER_CLASS), "popBackStack", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (poppingInjectedRoute.get() == true) return
                if (!isCurrentSettingsNavController(param.thisObject)) return
                if (handleNestedInjectedBack()) {
                    param.result = true
                }
            }
        })
        XposedBridge.hookAllMethods(cls(NAV_CONTROLLER_CLASS), "navigateUp", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (poppingInjectedRoute.get() == true) return
                if (!isCurrentSettingsNavController(param.thisObject)) return
                if (handleNestedInjectedBack()) {
                    param.result = true
                }
            }
        })
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook NavGraphScope: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.hookAboutScreen() {
    runCatching {
        val aboutScreen = method(ABOUT_SCREEN_CLASS, ABOUT_SCREEN_METHOD, 2)
        XposedBridge.hookMethod(aboutScreen, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val route = activeInjectedRoute() ?: return
                val composer = param.args?.getOrNull(0) ?: return
                runCatching {
                    renderInjectedSettingsScreen(route, composer)
                    param.result = null
                }.onFailure {
                    injectedRouteStack = emptyList()
                    XposedBridge.log("$LOG_PREFIX failed to render injected settings screen: ${it.stackTraceToString()}")
                }
            }
        })
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook AboutScreen: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.hookSettingsListBuilder() {
    runCatching {
        val buildMethod = resolveSettingsListBuilderMethod()
        XposedBridge.hookMethod(buildMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // Track the host settings LazyColumn build so injected rows appear at a
                // stable position even when upstream adds or removes nearby settings.
                currentSettingsNavGraphScope = param.args?.firstOrNull { it?.javaClass?.name == NAV_GRAPH_SCOPE_CLASS }
                    ?: param.args?.getOrNull(0)
                currentSettingsNavController = runCatching {
                    currentSettingsNavGraphScope?.method0("getNavController")
                }.getOrNull()
                settingsBuildDepth.set((settingsBuildDepth.get() ?: 0) + 1)
                itemCount.set(0)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val depth = ((settingsBuildDepth.get() ?: 0) - 1).coerceAtLeast(0)
                settingsBuildDepth.set(depth)
                if (depth == 0) itemCount.set(0)
            }
        })
        XposedBridge.log("$LOG_PREFIX ReaMicro settings list hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook settings list: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.hookLazyListItem() {
    runCatching {
        val lazyListScopeClass = cls(LAZY_LIST_SCOPE_CLASS)
        val method = lazyListScopeClass.declaredMethods.firstOrNull {
            it.name == LAZY_ITEM_DEFAULT_METHOD && it.parameterTypes.size == 6
        } ?: error("LazyListScope.item\$default not found")
        method.isAccessible = true
        lazyItemDefaultMethod = method
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (injectingModuleItem.get() == true) return
                // 高亮界面：在宿主的第一个 item 注册之前插入"补全计划"入口，使其位于列表最上方。
                if ((highlightScreenBuildDepth.get() ?: 0) > 0 && highlightEntryInjected.get() != true) {
                    highlightEntryInjected.set(true)
                    insertHighlightScreenEntryItem(param.args[0] ?: return)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (injectingModuleItem.get() == true) return
                if ((highlightScreenBuildDepth.get() ?: 0) > 0) return
                if ((settingsBuildDepth.get() ?: 0) <= 0) return
                val count = (itemCount.get() ?: 0) + 1
                itemCount.set(count)
                if (count == INSERT_AFTER_SETTINGS_ITEM_COUNT) {
                    insertModuleSettingsItem(param.args[0] ?: return)
                } else if (count == INSERT_BEFORE_SIGN_OUT_ITEM_COUNT) {
                    insertAccountSettingsItem(param.args[0] ?: return)
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX LazyList settings item hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook LazyList item: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.hookHostAccountSignOut() {
    runCatching {
        XposedBridge.hookAllMethods(
            cls(USER_REPOSITORY_CLASS),
            USER_REPOSITORY_SIGN_OUT_METHOD,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!settings.snapshot().canRunAccountCompletion) return
                    runCatching { accountController.persistCurrentAccountSnapshot() }
                        .onFailure {
                            XposedBridge.log("$LOG_PREFIX failed to persist account before sign out: ${it.stackTraceToString()}")
                        }
                }
            },
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook host sign out: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.hookAccountSecurityScreen() {
    runCatching {
        val method = method(
            ACCOUNT_SECURITY_SCREEN_CLASS,
            ACCOUNT_SECURITY_DELETE_CONTENT_METHOD,
            5,
        )
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!settings.snapshot().canRunAccountDataExport) return
                val state = param.args?.getOrNull(0) ?: return
                val deleteDialogState = param.args?.getOrNull(1) ?: return
                val composer = param.args?.getOrNull(3) ?: return
                runCatching {
                    renderAccountSecurityExportContent(state, deleteDialogState, composer)
                    param.result = targetUnit()
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX failed to render account security export list: ${it.stackTraceToString()}")
                }
            }
        })
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook account security screen: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.hookFontDocumentPickerResult() {
    runCatching {
        XposedBridge.hookAllMethods(Activity::class.java, "onActivityResult", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val requestCode = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                val resultCode = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                if (resultCode != Activity.RESULT_OK) return
                val activity = param.thisObject as? Activity ?: return
                val intent = param.args?.getOrNull(2) as? Intent ?: return
                if (requestCode == ONLINE_SOURCE_DOCUMENT_REQUEST_CODE) {
                    importOnlineSourceDocumentResult(activity, intent)
                    return
                }
                if (requestCode == READ_ALOUD_SOURCE_DOCUMENT_REQUEST_CODE) {
                    importTtsSourceDocumentResult(activity, intent)
                    return
                }
                val uri = intent.data ?: return
                when (requestCode) {
                    FONT_DOCUMENT_REQUEST_CODE -> copyFontUriToLibrary(activity, uri)
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
        XposedBridge.log("$LOG_PREFIX failed to hook font picker result: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.hookExternalSourceImportIntent() {
    runCatching {
        XposedBridge.hookAllMethods(Activity::class.java, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.packageName != HOST_PACKAGE_NAME) return
                consumeExternalSourceImportIntent(activity, activity.intent)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onNewIntent", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.packageName != HOST_PACKAGE_NAME) return
                val intent = param.args?.getOrNull(0) as? Intent ?: activity.intent
                consumeExternalSourceImportIntent(activity, intent)
            }
        })
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook external source import intent: ${it.stackTraceToString()}")
    }
}
