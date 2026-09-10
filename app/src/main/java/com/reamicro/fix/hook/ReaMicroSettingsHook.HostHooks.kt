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
                // 账号配置页：宿主渲染完「邮箱」条目后（第 2 个 item）注入「切换账号」入口。
                if ((accountSecurityBuildDepth.get() ?: 0) > 0) {
                    val count = (accountSecurityItemCount.get() ?: 0) + 1
                    accountSecurityItemCount.set(count)
                    if (count == INSERT_AFTER_ACCOUNT_EMAIL_ITEM_COUNT && accountSwitchEntryInjected.get() != true) {
                        accountSwitchEntryInjected.set(true)
                        insertAccountSwitchEntryItem(param.args[0] ?: return)
                    }
                    return
                }
                if ((settingsBuildDepth.get() ?: 0) <= 0) return
                val count = (itemCount.get() ?: 0) + 1
                itemCount.set(count)
                if (count == INSERT_AFTER_SETTINGS_ITEM_COUNT) {
                    insertModuleSettingsItem(param.args[0] ?: return)
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
        val method = resolveAccountSecurityDeleteContentMethod()
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

/**
 * 定位账号配置页「删除账号」条目 lambda。
 *
 * 宿主把这一屏拆成 LazyColumn 的若干 item lambda（雅号 / 邮箱 / 登录方式 / 删除账号），
 * 每个都是 `AccountSecurityScreen$lambda$0$0$4$0$N`，mangling 后缀 N 会随条目增删漂移
 * （2.3.2 beta 新增「登录方式」项，删除项从 N=2 移到 N=3，导致旧的写死名把「登录方式」
 * 当成删除项替换，QQ/微信绑定消失且出现两个删除账号）。
 *
 * 删除项 lambda 的形参签名独一无二：`(State, MutableState, LazyItemScope, Composer, int)`
 * ——只有它以 `State` 开头、次参为 `MutableState`。按此签名匹配，容忍后缀漂移；
 * 匹配不到再回退到写死常量名。
 */
internal fun ReaMicroSettingsHook.resolveAccountSecurityDeleteContentMethod(): java.lang.reflect.Method {
    val screenClass = cls(ACCOUNT_SECURITY_SCREEN_CLASS)
    val stateClass = cls(COMPOSE_STATE_CLASS)
    val mutableStateClass = cls(MUTABLE_STATE_CLASS)
    val lazyItemScopeClass = cls(LAZY_ITEM_SCOPE_CLASS)
    val composerClass = cls(COMPOSER_CLASS)
    val bySignature = screenClass.declaredMethods.firstOrNull { candidate ->
        val types = candidate.parameterTypes
        candidate.name.startsWith("AccountSecurityScreen\$lambda") &&
            types.size == 5 &&
            stateClass.isAssignableFrom(types[0]) &&
            mutableStateClass.isAssignableFrom(types[1]) &&
            lazyItemScopeClass.isAssignableFrom(types[2]) &&
            composerClass.isAssignableFrom(types[3]) &&
            types[4] == Int::class.javaPrimitiveType
    }?.apply { isAccessible = true }
    if (bySignature != null) return bySignature
    XposedBridge.log("$LOG_PREFIX account security delete lambda not matched by signature; falling back to $ACCOUNT_SECURITY_DELETE_CONTENT_METHOD")
    return method(ACCOUNT_SECURITY_SCREEN_CLASS, ACCOUNT_SECURITY_DELETE_CONTENT_METHOD, 5)
}

/**
 * 跟踪账号配置页 LazyColumn 的构建，配合 [hookLazyListItem] 把「切换账号」注入到「邮箱」之后。
 *
 * 该 LazyColumn 内容 lambda（`AccountSecurityScreen$lambda$…$4$0`）形参以 `LazyListScope` 结尾、
 * 返回 Unit，是这一屏唯一接受 LazyListScope 的方法；按此签名定位，容忍 mangling 漂移。
 */
internal fun ReaMicroSettingsHook.hookAccountSecurityColumn() {
    runCatching {
        val screenClass = cls(ACCOUNT_SECURITY_SCREEN_CLASS)
        val lazyListScopeClass = cls(LAZY_LIST_SCOPE_CLASS)
        val builder = screenClass.declaredMethods.firstOrNull { candidate ->
            candidate.name.startsWith("AccountSecurityScreen\$lambda") &&
                candidate.parameterTypes.isNotEmpty() &&
                candidate.parameterTypes.last() == lazyListScopeClass
        }?.apply { isAccessible = true }
            ?: error("AccountSecurityScreen LazyColumn builder not found")
        XposedBridge.hookMethod(builder, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!settings.snapshot().canRunAccountCompletion) return
                accountSecurityBuildDepth.set((accountSecurityBuildDepth.get() ?: 0) + 1)
                accountSecurityItemCount.set(0)
                accountSwitchEntryInjected.set(false)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val depth = ((accountSecurityBuildDepth.get() ?: 0) - 1).coerceAtLeast(0)
                accountSecurityBuildDepth.set(depth)
                if (depth == 0) accountSecurityItemCount.set(0)
            }
        })
        XposedBridge.log("$LOG_PREFIX account security column hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook account security column: ${it.stackTraceToString()}")
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
