package com.reamicro.fix.hook

import android.app.Activity
import android.content.Intent
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import com.reamicro.fix.hook.settings.*

// ReaMicroSettingsHook 的宿主 hook 安装簇。
//
// 所有 hookXxx()：把模块的设置项挂进宿主设置页的列表与导航图。
//
// 从 ReaMicroSettingsHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的
// 结果重新缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
/**
 * 组合期「插入下标越界」诊断（发现页 `insertBottomUp` → `MutableVector.add` 越界闪退专用）。
 *
 * 崩溃栈里完全没有业务帧，静态分析无法确定是哪个容器在被错位插入。这里给插入链路上的
 * **三层**都挂探针：`UiApplier.insertBottomUp` → `LayoutNode.insertAt$ui` →
 * `MutableVector.add(int, T)`。每层各有三条日志：
 * - **alive**（一次性）：证明这一层的 hook 真的会被调用。
 * - **size-unreadable**（一次性量级）：尺寸反射读不到且下标不小，说明漏报现场就在这里。
 * - **ILLEGAL**（条件触发）：`index > 当前子节点数`，即下一行数组拷贝必炸时，打出
 *   父容器身份 + 现有子节点清单 + 两级父链 + 待插入节点——**这是本探针最有价值的一条**。
 *
 * ## 它抓到了什么（本探针的实际战果）
 *
 * 发现页宫格「加载更多」两次连点闪退，靠这条 ILLEGAL 定位到根因：
 * ```
 * ILLEGAL insert: idx=4 size=2 parent=RowMeasurePolicy 1124x626
 *   children=[Column 354x626, Column 355x0] child=Column 0x0
 * ```
 * 父 Row 只有 2 个孩子却要插到下标 4。根因是同一 Row 内子节点**种类切换**
 * （末行从「2 本书 + 1 个 Spacer 占位」变成「3 本书 + 0 个 Spacer」），
 * gapbuffer 的 `PostInsertNodeFixup` 按槽位下标而非 applier 孩子下标调 insert，
 * 于是下标溢出。修复见 `ReaMicroSettingsHook.Discover.kt` 的 `renderDiscoverGridRow`
 * （每行固定产出同构格子，不再用 Spacer 补位）。
 *
 * ⚠ 历史坑：探针一度「三层 alive 都打出来、ILLEGAL 一条都没有」，原因不是 ART 内联，
 * 而是 [layoutNodeChildrenSize] 读错了字段名（`_children` 实为 `_foldedChildren`），
 * `runCatching` 把 NoSuchFieldException 静默吞掉，越界判定因此永远不成立。
 */
internal fun ReaMicroSettingsHook.hookLayoutNodeInsertDiagnostics() {
    hookInsertProbe(
        className = "androidx.compose.ui.node.UiApplier",
        methodName = "insertBottomUp",
        label = "UiApplier.insertBottomUp",
        parentOf = { param ->
            runCatching { XposedHelpers.getObjectField(param.thisObject, "current") }.getOrNull()
                ?: runCatching { XposedHelpers.callMethod(param.thisObject, "getCurrent") }.getOrNull()
        },
    )
    hookInsertProbe(
        className = "androidx.compose.ui.node.LayoutNode",
        methodName = "insertAt",
        label = "LayoutNode.insertAt",
        parentOf = { param -> param.thisObject },
    )
    hookInsertProbe(
        className = "androidx.compose.runtime.collection.MutableVector",
        methodName = "add",
        label = "MutableVector.add",
        parentOf = { param -> param.thisObject },
        sizeOf = { vector ->
            runCatching { XposedHelpers.callMethod(vector, "getSize") as? Int }.getOrNull()
        },
        describeNode = { "itself=${it?.javaClass?.simpleName}@${Integer.toHexString(System.identityHashCode(it))}" },
    )
}

/**
 * 给单个插入点挂 alive/ILLEGAL 双探针。
 *
 * ## 形参形状
 *
 * 三层目标的签名各不相同，统一按「首个形参是 int 下标」来找（`MutableVector.add(T)` 是
 * 一元的追加版、**没有**下标形参，不是我们要盯的那个）：
 * - `UiApplier.insertBottomUp(int, Object)` —— 崩溃栈里那一帧
 * - `LayoutNode.insertAt(int, LayoutNode)`（`insertAt$ui` 编译后的名）
 * - `MutableVector.add(int, Object)` —— 真正抛越界的 gapbuffer 写入
 *
 * 找不到就报 FAILED，不静默降级——形参过滤放宽过一次（上一轮按「名字 + 两个形参」取，
 * 结果 `MutableVector` 被 1 元的 `add` 挤掉、拿错了方法），这类错误必须炸在明面上。
 */
private fun ReaMicroSettingsHook.hookInsertProbe(
    className: String,
    methodName: String,
    label: String,
    parentOf: (XC_MethodHook.MethodHookParam) -> Any?,
    sizeOf: (Any) -> Int? = { layoutNodeChildrenSize(it) },
    describeNode: (Any?) -> String = { describeLayoutNode(it) },
) {
    runCatching {
        val targetClass = cls(className)
        val method = targetClass.declaredMethods.firstOrNull {
            it.name.startsWith(methodName) && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Integer.TYPE
        } ?: error("$className.$methodName(int,…) not found")
        method.isAccessible = true
        var aliveLogged = false
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val index = param.args.getOrNull(0) as? Int ?: return
                if (!aliveLogged) {
                    aliveLogged = true
                    XposedBridge.logAlways("[ReaMicro] insert probe alive: $label")
                }
                val parent = runCatching { parentOf(param) }.getOrNull() ?: return
                val size = runCatching { sizeOf(parent) }.getOrNull()
                if (size == null) {
                    // 尺寸读不到且下标不小，极可能就是漏报现场——也打一条（一次性由 alive 保证量级）。
                    if (index >= 4) {
                        XposedBridge.logAlways(
                            "[ReaMicro] insert probe size-unreadable: $label idx=$index " +
                                "parent=${parent.javaClass.name}",
                        )
                    }
                    return
                }
                if (index <= size) return
                XposedBridge.logAlways(
                    "[ReaMicro] ILLEGAL insert: $label idx=$index size=$size " +
                        "parent=${describeNode(parent)} " +
                        "children=${describeChildren(parent)} " +
                        "grandparent=${describeLayoutNode(layoutNodeParent(parent))} " +
                        "child=${describeLayoutNode(param.args.getOrNull(1))}",
                )
            }
        })
        XposedBridge.log("$LOG_PREFIX insert probe installed: $label")
    }.onFailure {
        XposedBridge.logAlways("$LOG_PREFIX insert probe FAILED: $label ${it.javaClass.simpleName}: ${it.message}")
    }
}

/**
 * 读 LayoutNode 当前子节点数。
 *
 * **字段名是 `_foldedChildren`，不是 `_children`**——上一轮探针「一条日志都没有」的真正原因
 * 就在这里：`getObjectField(node, "_children")` 每次都抛 NoSuchFieldException，被
 * `runCatching` 静默吞掉，于是 size 恒为 null、越界判定永远不成立。
 * 已用 MT 反编译核对（宿主 2.3.2 beta）：`LayoutNode._foldedChildren:
 * MutableVectorWithMutationTracking`（另有 `_unfoldedChildren` / `_zSortedChildren` 两个
 * 裸 `MutableVector`，但插入链路读写的是 folded 那个），包装类暴露 `getSize()`。
 */
private fun layoutNodeChildrenSize(node: Any): Int? = runCatching {
    val children = XposedHelpers.getObjectField(node, FOLDED_CHILDREN_FIELD) ?: return@runCatching null
    (XposedHelpers.callMethod(children, "getSize") as? Int)
        ?: runCatching {
            XposedHelpers.callMethod(XposedHelpers.getObjectField(children, "vector"), "getSize") as? Int
        }.getOrNull()
}.getOrNull()

/** `LayoutNode` 持有子节点向量的字段名（宿主 2.3.2 beta 实测）。 */
private const val FOLDED_CHILDREN_FIELD = "_foldedChildren"

private fun layoutNodeParent(node: Any): Any? =
    runCatching { XposedHelpers.callMethod(node, "getParent") }.getOrNull()

/** 容器身份速写：measurePolicy 类名（Row/Column/Box/LazyList 各不相同）+ 实测尺寸 + 身份哈希。 */
private fun describeLayoutNode(node: Any?): String {
    if (node == null) return "null"
    val policy = runCatching { XposedHelpers.callMethod(node, "getMeasurePolicy") }.getOrNull()
    val width = runCatching { XposedHelpers.callMethod(node, "getWidth") as? Int }.getOrNull()
    val height = runCatching { XposedHelpers.callMethod(node, "getHeight") as? Int }.getOrNull()
    return "${policy?.javaClass?.simpleName}@${Integer.toHexString(System.identityHashCode(node))} ${width}x$height"
}

/**
 * 越界现场把父容器**已经存在**的子节点逐个列出来（类型 + 尺寸 + 身份）。
 *
 * 只打 `size=N` 不够——真正要回答的是「这个 Row 里现在是哪两个节点，正被插入的又是第几个」。
 * `_foldedChildren` 是 `MutableVectorWithMutationTracking`，其 `vector` 字段是裸
 * `MutableVector`，`content` 数组 + `size` 才是真实数据源。
 */
private fun describeChildren(node: Any): String = runCatching {
    val folded = XposedHelpers.getObjectField(node, FOLDED_CHILDREN_FIELD) ?: return@runCatching "?"
    val vector = runCatching { XposedHelpers.getObjectField(folded, "vector") }.getOrNull() ?: folded
    val size = runCatching { XposedHelpers.callMethod(vector, "getSize") as? Int }.getOrNull()
        ?: return@runCatching "?"
    val content = runCatching { XposedHelpers.getObjectField(vector, "content") }.getOrNull() as? Array<*>
        ?: return@runCatching "size=$size content=?"
    (0 until minOf(size, 12)).joinToString(prefix = "[", postfix = "]", separator = ",") { i ->
        describeLayoutNode(content.getOrNull(i))
    }
}.getOrDefault("?")

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
        // 导航图谱每次重组都会调用 composable(...) 注册页面。借它把「最近见到的 NavGraphScope」
        // 刷新到最新实例——Activity 被系统回收后重建时，旧实例还留在缓存里，用它 navigate 会
        // 静默失败（用户现象：从多任务切回来之后再点「发现」点不动）。
        // 只刷新 lastKnown 这一个字段：它本来就是「最近见到的」，语义不变；
        // currentSettingsNavGraphScope 仍由设置页列表构建处维护，避免多 NavHost 时互相覆盖。
        navGraphScopeClass.declaredMethods
            .filter { it.name == NAV_GRAPH_COMPOSABLE_METHOD }
            .forEach { candidate ->
                runCatching {
                    XposedBridge.hookMethod(candidate, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.thisObject?.let { lastKnownNavGraphScope = it }
                        }
                    })
                }
            }
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
                consumeTopInjectedRoute("navGraphScope.popBackStack")
            }
        })
        XposedBridge.hookAllMethods(cls(NAV_CONTROLLER_CLASS), "popBackStack", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (poppingInjectedRoute.get() == true) return
                if (!isCurrentSettingsNavController(param.thisObject)) return
                if (handleNestedInjectedBack()) {
                    param.result = true
                    return
                }
                // 宿主系统返回键走的是 NavController 而非 NavGraphScope；此前这里只处理
                // 嵌套子路由，顶层注入路由（如「发现」）不会出栈，进而在下次进入入口时
                // 让 [openNestedInjectedRoute] 误判为「已在注入页内」。两处统一出栈。
                consumeTopInjectedRoute("navController.popBackStack")
            }
        })
        XposedBridge.hookAllMethods(cls(NAV_CONTROLLER_CLASS), "navigateUp", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (poppingInjectedRoute.get() == true) return
                if (!isCurrentSettingsNavController(param.thisObject)) return
                if (handleNestedInjectedBack()) {
                    param.result = true
                    return
                }
                consumeTopInjectedRoute("navController.navigateUp")
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
                val route = activeInjectedRoute()
                if (route == null) return
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
