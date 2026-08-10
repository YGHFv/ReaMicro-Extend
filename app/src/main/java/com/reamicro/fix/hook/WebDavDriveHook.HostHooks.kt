package com.reamicro.fix.hook

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.reamicro.fix.cloud.webdav.CloudDownloadCancelledException
import com.reamicro.fix.cloud.webdav.CloudBookRowExtendedDisplayContext
import com.reamicro.fix.cloud.webdav.HomeSearchRenderContext
import com.reamicro.fix.cloud.webdav.HomeSearchSection
import com.reamicro.fix.cloud.webdav.ImportLocalLibraryRowContext
import com.reamicro.fix.cloud.webdav.ImportUnauthRenderContext
import com.reamicro.fix.cloud.webdav.SyncAuthCardRenderContext
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XCallback
import java.lang.ref.WeakReference
import java.net.URL
import java.util.UUID
import kotlin.math.max
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.online.search.sanitizeHostCloudSearchResults
import com.reamicro.fix.cloud.local.localLibraryPathArg
import com.reamicro.fix.cloud.local.syntheticLocalLibraryBookEntry
import com.reamicro.fix.cloud.webdav.webDavDisplayDir
import com.reamicro.fix.cloud.webdav.hasWebDavLogin
import com.reamicro.fix.cloud.webdav.webDavPathArg
import com.reamicro.fix.cloud.webdav.childWebDavPath
import com.reamicro.fix.cloud.webdav.normalizeWebDavPath
import com.reamicro.fix.cloud.webdav.parentWebDavPath
import com.reamicro.fix.cloud.webdav.syntheticWebDavBookEntry
import com.reamicro.fix.logging.logWebDav

// WebDavDriveHook 的宿主 hook 安装簇。
//
// 所有 hookXxx()：只负责挂到宿主方法上，具体实现在其它簇。
//
// 从 WebDavDriveHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun WebDavDriveHook.hookOnlineCompletionBookRowDuration() {
    runCatching {
        val bookRowInfo = cls(BOOK_ROW_INFO_CLASS).declaredMethods.first {
            it.name == BOOK_ROW_INFO_METHOD &&
                it.parameterTypes.size == 4 &&
                it.parameterTypes[1].name == BOOK_CLASS
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(bookRowInfo, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val book = param.args?.getOrNull(1) ?: return
                if (!isOnlineCompletionLocalBook(book)) return
                onlineCompletionBookRowInfoDepth.set((onlineCompletionBookRowInfoDepth.get() ?: 0) + 1)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val next = (onlineCompletionBookRowInfoDepth.get() ?: 0) - 1
                if (next <= 0) onlineCompletionBookRowInfoDepth.remove() else onlineCompletionBookRowInfoDepth.set(next)
            }
        })

        val secondToHours = cls(TIME_EXT_KT_CLASS).declaredMethods.first {
            it.name == SECOND_TO_HOURS_METHOD &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == java.lang.Long.TYPE
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(secondToHours, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if ((onlineCompletionBookRowInfoDepth.get() ?: 0) > 0) {
                    param.result = ""
                }
            }
        })
        logWebDav("online completion book row duration hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook online completion book row duration: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookOnlineCompletionBookLocalSheet() {
    runCatching {
        val sheetClass = cls(BOOK_LOCAL_SHEET_CLASS)
        val localSheetContextMethods = sheetClass.declaredMethods
            .filter { method -> isBookLocalSheetBookContextMethod(method) }
        localSheetContextMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        onlineCompletionLocalSheetDepth.set((onlineCompletionLocalSheetDepth.get() ?: 0) + 1)
                        onlineCompletionLocalSheetBook.set(param.args?.getOrNull(0))
                        logWebDav(
                            "online completion local sheet book captured via=${method.name} " +
                                "title=${param.args?.getOrNull(0)?.callString("getTitle").orEmpty()}",
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val nextDepth = ((onlineCompletionLocalSheetDepth.get() ?: 0) - 1).coerceAtLeast(0)
                        onlineCompletionLocalSheetDepth.set(nextDepth)
                        if (nextDepth == 0) onlineCompletionLocalSheetBook.remove()
                    }
                })
            }

        val fileBackupMethods = sheetClass.declaredMethods.filter {
            it.name == FILE_BACKUP_METHOD && it.parameterTypes.size == 5
        }
        if (fileBackupMethods.isNotEmpty()) {
            fileBackupMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        renderOnlineCompletionUpdateRowFromLocalSheet(param.args?.getOrNull(3))
                    }
                })
            }
        } else {
            val communityMethods = sheetClass.declaredMethods.filter {
                it.name == BOOK_COMMUNITY_METHOD && it.parameterTypes.size == 4
            }
            communityMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        logWebDav(
                            "online completion BookCommunity after composer=${param.args?.getOrNull(2)?.javaClass?.name.orEmpty()} " +
                                "book=${onlineCompletionLocalSheetBook.get()?.callString("getTitle").orEmpty()}",
                        )
                        renderOnlineCompletionUpdateRowFromLocalSheet(param.args?.getOrNull(2))
                    }
                })
            }
        }
        if (fileBackupMethods.isEmpty()) {
            val publisherMethods = sheetClass.declaredMethods.filter {
                it.name == BOOK_PUBLISHER_METHOD &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes.getOrNull(0) == String::class.java &&
                    it.parameterTypes.getOrNull(1)?.name == FUNCTION0_CLASS &&
                    it.parameterTypes.getOrNull(2)?.name == COMPOSER_CLASS
            }
            publisherMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        renderOnlineCompletionUpdateRowFromLocalSheet(param.args?.getOrNull(2))
                    }
                })
            }
            if (publisherMethods.isNotEmpty()) {
                logWebDav("online completion update row hooked after BookPublisher count=${publisherMethods.size}")
            }
        }
        hookOnlineCompletionBookSourceLabel()
        hookOnlineCompletionBookGroupRow(sheetClass)
        logWebDav(
            "online completion local sheet hook installed " +
                "contexts=${localSheetContextMethods.size} backups=${fileBackupMethods.size}",
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook online completion local sheet: ${it.stackTraceToString()}")
    }
}

// The book local sheet's top-left source tag is FileSource.queryName(book.uri); for online-source books the uri is a
// detail URL so it renders as "网址链接". Rewrite that label (and only it) to the resolved online source name (e.g. 晚风里).
internal fun WebDavDriveHook.hookOnlineCompletionBookSourceLabel() {
    if (onlineCompletionSourceLabelHooked) return
    runCatching {
        val fileSourceClass = cls(FILE_SOURCE_CLASS)
        val methods = fileSourceClass.declaredMethods.filter {
            it.name == FILE_SOURCE_QUERY_NAME_METHOD &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes.getOrNull(0) == String::class.java &&
                it.returnType == String::class.java
        }
        methods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val book = onlineCompletionLocalSheetBook.get() ?: return
                    val info = onlineImportedBookSourceInfo(book) ?: return
                    val sourceName = info.sourceName.takeUnless { it.isBlank() || it == "未知源" } ?: return
                    if (param.result == sourceName) return
                    val previous = param.result
                    param.result = sourceName
                    logWebDav(
                        "book source label rewritten to source name=$sourceName " +
                            "from=${previous?.toString().orEmpty()} title=${book.callString("getTitle")}",
                    )
                }
            })
        }
        onlineCompletionSourceLabelHooked = methods.isNotEmpty()
        if (methods.isNotEmpty()) {
            logWebDav("online completion source label hooked count=${methods.size}")
        }
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook online completion source label: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookOnlineCompletionBookGroupRow(sheetClass: Class<*>) {
    val methods = sheetClass.declaredMethods.filter {
        it.name == BOOK_META_ACTION_ROW_METHOD &&
            it.parameterTypes.size == 6 &&
            it.parameterTypes.getOrNull(1) == String::class.java &&
            it.parameterTypes.getOrNull(2) == String::class.java &&
            it.parameterTypes.getOrNull(3)?.name == FUNCTION0_CLASS &&
            it.parameterTypes.getOrNull(4)?.name == COMPOSER_CLASS
    }
    methods.forEach { method ->
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (onlineCompletionUpdateRowInjecting.get() == true) return
                if (param.args?.getOrNull(1)?.toString() != "分组") return
                val book = onlineCompletionLocalSheetBook.get() ?: return
                val info = onlineImportedBookSourceInfo(book) ?: return
                val groupIcon = param.args?.getOrNull(0) ?: return
                val groupClick = param.args?.getOrNull(3) ?: return
                val composer = param.args?.getOrNull(4) ?: return
                onlineCompletionUpdateRowInjecting.set(true)
                runCatching {
                    renderOnlineCompletionUpdateGroupActionRow(book, info, groupIcon, groupClick, composer)
                    param.result = targetUnit()
                    logWebDav("online completion update/group row rendered title=${book.callString("getTitle")} source=${info.sourceName}")
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX online completion update/group row render failed: ${it.stackTraceToString()}")
                }
                onlineCompletionUpdateRowInjecting.remove()
            }
        })
    }
    onlineCompletionCombinedGroupRowHooked = methods.isNotEmpty()
    if (methods.isNotEmpty()) {
        logWebDav("online completion update/group row hook installed: ${methods.size}")
    }
}

internal fun WebDavDriveHook.hookWebDavCleartextPolicy() {
    val hooked = mutableListOf<String>()
    fun hookClass(className: String, loader: ClassLoader? = null) {
        runCatching {
            val policyClass = if (loader == null) Class.forName(className) else XposedHelpers.findClass(className, loader)
            policyClass.declaredMethods.filter {
                it.name == "isCleartextTrafficPermitted" &&
                    (it.parameterTypes.isEmpty() || (it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java))
            }.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (shouldAllowCleartext(param.args)) {
                            logCleartextAllowed(param.args?.firstOrNull(), "$className/${method.parameterTypes.size}")
                            param.result = true
                        }
                    }
                })
                hooked += "$className/${method.parameterTypes.size}"
            }
        }.onFailure {
            logWebDav("cleartext policy class skipped $className: ${it.message}")
        }
    }
    fun hookCleartextUrlFilter() {
        runCatching {
            val filterClass = Class.forName(ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS)
            filterClass.declaredMethods.filter {
                it.name == "checkURLPermitted" &&
                    it.parameterTypes.size == 1 &&
                    URL::class.java.isAssignableFrom(it.parameterTypes[0])
            }.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (shouldAllowCleartext(param.args)) {
                            logCleartextAllowed(param.args?.firstOrNull(), "$ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS/checkURLPermitted")
                            param.result = null
                        }
                    }
                })
                hooked += "$ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS/${method.name}"
            }
        }.onFailure {
            logWebDav("cleartext url filter skipped $ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS: ${it.message}")
        }
    }
    hookClass(NETWORK_SECURITY_POLICY_CLASS)
    hookCleartextUrlFilter()
    hookClass(ANDROID_OKHTTP_PLATFORM_CLASS)
    hookClass(OKHTTP_PLATFORM_CLASS, classLoader)
    hookClass(OKHTTP_ANDROID_PLATFORM_CLASS, classLoader)
    hookClass(OKHTTP_ANDROID10_PLATFORM_CLASS, classLoader)
    logWebDav("cleartext policy hook installed: ${hooked.joinToString().ifBlank { "none" }}")
}

internal fun WebDavDriveHook.hookBackupTypeName() {
    runCatching {
        val method = cls(BACKUP_TYPE_CLASS).declaredMethods.first {
            it.name == BACKUP_TYPE_NAME_METHOD && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                if (
                    type == BACKUP_TYPE_WEBDAV ||
                    type == BACKUP_TYPE_LOCAL_LIBRARY ||
                    isOnlineCompletionRenderType(type) ||
                    ((onlineCompletionCloudTitleDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_WEBDAV) ||
                    ((webDavAccountScreenDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_BAIDU) ||
                    ((localLibraryAccountScreenDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_YUN115) ||
                    ((webDavBackupCardDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_YUN115) ||
                    ((webDavRunningSyncAuthCardDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_YUN115)
                ) {
                    param.result = if (
                        ((onlineCompletionCloudTitleDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_WEBDAV) ||
                            isOnlineCompletionRenderType(type)
                    ) {
                        onlineCompletionTitleForType(type)
                    } else if (
                        type == BACKUP_TYPE_LOCAL_LIBRARY ||
                        ((localLibraryAccountScreenDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_YUN115)
                    ) {
                        LOCAL_LIBRARY_TITLE
                    } else {
                        WEBDAV_TITLE
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val types = syncAvailableTypes.get() ?: return
                val current = syncAvailableRowIndex.get() ?: 0
                if (current < types.size) {
                    syncAvailableRowIndex.set(current + 1)
                }
            }
        })
        logWebDav("backup type name hook installed")
    }.onFailure {
        logWebDav("failed to hook WebDAV backup type name: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookHomeCloudResultListRenderContext() {
    runCatching {
        val method = cls(HOME_SEARCH_BAR_CLASS).declaredMethods.first {
            it.name == HOME_CLOUD_RESULT_LIST_METHOD && it.parameterTypes.size == 6
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                // Compose 重组时会直接重新调用 CloudResultList 方法（绕过 addHomeWebDavSearchSection
                // 里 withWebDavIcon{}/pushLocalLibraryIcon() 的调用点包裹），导致标题图标 getYun115
                // 在 depth=0 下透传原始 115 图标——即"展开后概率变 115、折叠仍是 115"。
                // 在方法 hook 里按稳定的 type 参数设 depth，初次合成与重组都覆盖。
                when {
                    isOnlineCompletionRenderType(type) -> {
                        pushOnlineCompletionCloudTitle(onlineCompletionTitleForType(type))
                        pushWebDavIcon()
                    }
                    type == BACKUP_TYPE_WEBDAV -> pushWebDavIcon()
                    type == BACKUP_TYPE_LOCAL_LIBRARY -> pushLocalLibraryIcon()
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                when {
                    isOnlineCompletionRenderType(type) -> {
                        popWebDavIcon()
                        popOnlineCompletionCloudTitle()
                    }
                    type == BACKUP_TYPE_WEBDAV -> popWebDavIcon()
                    type == BACKUP_TYPE_LOCAL_LIBRARY -> popLocalLibraryIcon()
                }
            }
        })
        logWebDav("home cloud result render context hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook home cloud result render context: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookOnlineCompletionHomeCloudBookRow() {
    runCatching {
        val method = cls(HOME_SEARCH_BAR_CLASS).declaredMethods.first {
            it.name == HOME_CLOUD_BOOK_ROW_METHOD && it.parameterTypes.size == 7
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val book = param.args?.getOrNull(1) ?: return
                val path = cloudPathOf(book)
                val target = onlineCompletionSearchTargets[path] ?: return
                val composer = param.args?.getOrNull(4) ?: return
                renderOnlineCompletionCloudBookSearchRow(book, target, composer)
                param.result = targetUnit()
            }
        })
        logWebDav("online completion home CloudBookRow hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook online completion home CloudBookRow: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavRowIcon() {
    runCatching {
        val method = cls(BAIDU_ICON_CLASS).declaredMethods.first {
            it.name == BAIDU_ICON_METHOD && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val syncTypes = syncAvailableTypes.get()
                val syncIndex = syncAvailableRowIndex.get() ?: 0
                val isSyncWebDavRow = syncTypes?.getOrNull(syncIndex) == BACKUP_TYPE_WEBDAV
                if ((localLibraryIconDepth.get() ?: 0) > 0) {
                    getNativeAndroidOsVector()?.let { param.result = it }
                    return
                }
                if ((webDavIconDepth.get() ?: 0) <= 0 && !isSyncWebDavRow) return
                getComposeWebDavVector()?.let { param.result = it }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV row icon hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV row icon: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavYun115Icon() {
    runCatching {
        val method = cls(YUN115_ICON_CLASS).declaredMethods.first {
            it.name == YUN115_ICON_METHOD && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (
                    (webDavIconDepth.get() ?: 0) <= 0 &&
                    (onlineCompletionCloudTitleDepth.get() ?: 0) <= 0 &&
                    (localLibraryIconDepth.get() ?: 0) <= 0 &&
                    (webDavBackupCardDepth.get() ?: 0) <= 0 &&
                    (webDavRunningSyncAuthCardDepth.get() ?: 0) <= 0
                ) return
                if ((localLibraryIconDepth.get() ?: 0) > 0) {
                    getNativeAndroidOsVector()?.let { param.result = it }
                    return
                }
                getComposeWebDavVector()?.let { param.result = it }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV 115 icon replacement hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV 115 icon replacement: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavFileFolderIcon() {
    runCatching {
        val method = cls(FILE_FOLDER_ICON_CLASS).declaredMethods.first {
            it.name == FILE_FOLDER_ICON_METHOD && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if ((localLibraryCloudScreenDepth.get() ?: 0) > 0) {
                    getNativeAndroidOsVector()?.let { param.result = it }
                    return
                }
                if ((webDavCloudScreenDepth.get() ?: 0) <= 0) return
                getComposeWebDavVector()?.let { param.result = it }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV folder icon hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV folder icon: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavCloudTreeIcon() {
    runCatching {
        val cloudTreeMethod = cls(CLOUD_TREE_CLASS).declaredMethods.first {
            it.name == CLOUD_TREE_METHOD &&
                it.parameterTypes.size == 5 &&
                it.parameterTypes[0] == java.lang.Integer.TYPE
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(cloudTreeMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> webDavCloudTreeDepth.set((webDavCloudTreeDepth.get() ?: 0) + 1)
                    BACKUP_TYPE_LOCAL_LIBRARY -> localLibraryCloudTreeDepth.set((localLibraryCloudTreeDepth.get() ?: 0) + 1)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> {
                        val next = (webDavCloudTreeDepth.get() ?: 0) - 1
                        if (next <= 0) webDavCloudTreeDepth.remove() else webDavCloudTreeDepth.set(next)
                    }
                    BACKUP_TYPE_LOCAL_LIBRARY -> {
                        val next = (localLibraryCloudTreeDepth.get() ?: 0) - 1
                        if (next <= 0) localLibraryCloudTreeDepth.remove() else localLibraryCloudTreeDepth.set(next)
                    }
                }
            }
        })

        val androidOsMethod = cls(ANDROID_OS_ICON_CLASS).declaredMethods.first {
            it.name == ANDROID_OS_ICON_METHOD && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(androidOsMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if ((localLibraryCloudTreeDepth.get() ?: 0) > 0) return
                if ((webDavCloudTreeDepth.get() ?: 0) <= 0) return
                getComposeWebDavVector()?.let { param.result = it }
            }
        })
        logWebDav("cloud tree icon hook installed")
    }.onFailure {
        logWebDav("failed to hook cloud tree icon: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookImportCloudRowLabels() {
    listOf(CLOUD_UNAUTH_LABEL_METHOD, CLOUD_AUTHORIZED_LABEL_METHOD).forEach { labelMethod ->
        runCatching {
            val method = cls(BOOK_LIBRARY_SHEET_CLASS).declaredMethods.first {
                it.name == labelMethod &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if ((localLibraryIconDepth.get() ?: 0) > 0) {
                        param.args?.set(0, LOCAL_LIBRARY_TITLE)
                        return
                    }
                    if ((webDavIconDepth.get() ?: 0) <= 0) return
                    param.args?.set(0, WEBDAV_TITLE)
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV import row label hook installed: $labelMethod")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV import row label $labelMethod: ${it.stackTraceToString()}")
        }
    }
}

internal fun WebDavDriveHook.hookSyncAuthCard() {
    runCatching {
        val method = cls(AUTH_CARD_CLASS).declaredMethods.first {
            it.name == AUTH_CARD_METHOD && it.parameterTypes.size == 6
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                syncAuthCardRender.set(
                    SyncAuthCardRenderContext(
                        bookId = (param.args?.getOrNull(0) as? Number)?.toLong(),
                        onSetupDefaultDir = param.args?.getOrNull(2),
                        onPick = param.args?.getOrNull(3),
                    ),
                )
                syncWebDavAuthRenderedBeforeAvailable.remove()
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                syncAuthCardRender.remove()
                syncWebDavAuthRenderedBeforeAvailable.remove()
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV sync AuthCard hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV sync AuthCard: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookImportCloudRowDetail() {
    runCatching {
        val detail = cls(BOOK_LIBRARY_SHEET_CLASS).declaredMethods.first {
            it.name == CLOUD_AUTHORIZED_DETAIL_METHOD &&
                it.parameterTypes.size == 4 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(detail, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if ((localLibraryIconDepth.get() ?: 0) <= 0) return
                param.args?.set(0, LOCAL_LIBRARY_BROWSE_TEXT)
                param.args?.set(1, "")
                localLibraryAuthorizedDetailDepth.set((localLibraryAuthorizedDetailDepth.get() ?: 0) + 1)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val next = (localLibraryAuthorizedDetailDepth.get() ?: 0) - 1
                if (next <= 0) localLibraryAuthorizedDetailDepth.remove() else localLibraryAuthorizedDetailDepth.set(next)
            }
        })

        cls(MATERIAL3_TEXT_CLASS).declaredMethods.filter {
            it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == String::class.java
        }.forEach { textMethod ->
            textMethod.isAccessible = true
            XposedBridge.hookMethod(textMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    applyCloudBookExtendedDisplayText(param)
                    if ((localLibraryDefaultFolderDepth.get() ?: 0) > 0 &&
                        param.args?.getOrNull(0)?.toString().isNullOrEmpty()
                    ) {
                        param.result = null
                        return
                    }
                    if ((localLibraryAuthorizedDetailDepth.get() ?: 0) > 0 &&
                        param.args?.getOrNull(0)?.toString() == "$LOCAL_LIBRARY_BROWSE_TEXT / "
                    ) {
                        param.args?.set(0, LOCAL_LIBRARY_BROWSE_TEXT)
                    }
                }
            })
        }
        XposedBridge.log("$LOG_PREFIX local library import row detail hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook local library import row detail: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookCloudBookRowExtendedDisplay() {
    runCatching {
        val method = cls(CLOUD_BOOK_LIST_CLASS).declaredMethods.first {
            it.name == CLOUD_BOOK_ROW_METHOD &&
                it.parameterTypes.size == 7 &&
                it.parameterTypes[1].name == CLOUD_BOOK_CLASS
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!canUseCloudExtendedDisplay()) return
                val book = param.args?.getOrNull(1) ?: return
                if (isOnlineCompletionPath(cloudPathOf(book))) return
                val updatedAt = book.callLong("getUpdatedAt")
                if (updatedAt <= 0L) return
                cloudBookRowExtendedDisplay.set(
                    CloudBookRowExtendedDisplayContext(
                        updatedAt = updatedAt,
                        extensionLabel = cloudBookExtensionLabel(book.callString("getExtension")),
                    ),
                )
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                cloudBookRowExtendedDisplay.remove()
            }
        })
        XposedBridge.log("$LOG_PREFIX cloud book row extended display hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook cloud book row extended display: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookLocalLibraryFolderPickerResult() {
    runCatching {
        Activity::class.java.declaredMethods.filter {
            it.name == "onActivityResult" &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == java.lang.Integer.TYPE &&
                it.parameterTypes[1] == java.lang.Integer.TYPE &&
                it.parameterTypes[2] == Intent::class.java
        }.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val requestCode = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                    if (requestCode != REQUEST_LOCAL_LIBRARY_DIR) return
                    val resultCode = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                    val data = param.args?.getOrNull(2) as? Intent
                    val uri = data?.data
                    if (resultCode == Activity.RESULT_OK && uri != null) {
                        val activity = param.thisObject as? Activity ?: activityProvider()
                        val flags = data.flags and (
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            )
                        runCatching {
                            activity?.contentResolver?.takePersistableUriPermission(
                                uri,
                                flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
                            )
                        }
                        addLocalLibraryFolder(uri)
                        activity?.toast("已添加书库文件夹")
                    }
                    param.result = null
                }
            })
        }
        XposedBridge.log("$LOG_PREFIX local library folder picker hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook local library folder picker: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookSyncAuthCardContent() {
    runCatching {
        val method = cls(AUTH_CARD_CLASS).declaredMethods.first {
            it.name == AUTH_CARD_CONTENT_METHOD && it.parameterTypes.size == 11
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                val hasOuterContext = syncAuthCardRender.get() != null
                syncAuthCardContentOwnsContext.set(!hasOuterContext)
                if (!hasOuterContext) {
                    syncAuthCardRender.set(
                        SyncAuthCardRenderContext(
                            onSetupDefaultDir = args.getOrNull(6),
                            onPick = args.getOrNull(5),
                        ),
                    )
                    syncWebDavAuthRenderedBeforeAvailable.remove()
                }
                val backupType = (args.getOrNull(0) as? Number)?.toInt()
                val isRunning = args.getOrNull(8) as? Boolean == true
                val isWebDavBookRunning = syncAuthCardRender.get()?.bookId
                    ?.let { webDavRunningBackupBookIds.containsKey(it) } == true
                val isWebDavRunning = isRunning && (
                    (backupType == BACKUP_TYPE_YUN115 && (webDavRunningBackupPending.get() == true || isWebDavBookRunning)) ||
                        backupType == BACKUP_TYPE_WEBDAV
                    )
                if (isWebDavRunning) {
                    args[0] = BACKUP_TYPE_YUN115
                    webDavRunningBackupPending.set(true)
                    webDavRunningSyncAuthCardDepth.set((webDavRunningSyncAuthCardDepth.get() ?: 0) + 1)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if ((webDavRunningSyncAuthCardDepth.get() ?: 0) > 0) {
                    val next = (webDavRunningSyncAuthCardDepth.get() ?: 0) - 1
                    if (next <= 0) {
                        webDavRunningSyncAuthCardDepth.remove()
                    } else {
                        webDavRunningSyncAuthCardDepth.set(next)
                    }
                }
                webDavRunningBackupPending.remove()
                if (syncAuthCardContentOwnsContext.get() == true) {
                    syncAuthCardRender.remove()
                    syncWebDavAuthRenderedBeforeAvailable.remove()
                }
                syncAuthCardContentOwnsContext.remove()
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV sync AuthCard content hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV sync AuthCard content: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookDriveAuthCard() {
    runCatching {
        val method = cls(AUTH_CARD_CLASS).declaredMethods.first {
            it.name == DRIVE_AUTH_CARD_METHOD && it.parameterTypes.size == 8
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if ((webDavRunningSyncAuthCardDepth.get() ?: 0) <= 0) return
                val args = param.args ?: return
                val backupDir = currentWebDavBackupDir()
                getComposeWebDavVector()?.let { args[0] = it }
                args[1] = "上传到 $WEBDAV_TITLE"
                args[2] = webDavDisplayDir(backupDir)
                args[3] = true
                val context = syncAuthCardRender.get()
                args[4] = functionProxy("WebDavRunningDefaultDir", FUNCTION0_CLASS) {
                    if (backupDir.isBlank()) {
                        context?.onSetupDefaultDir?.let { invokeFunction1(it, BACKUP_TYPE_WEBDAV) }
                    } else {
                        context?.onPick?.let { invokeFunction2(it, BACKUP_TYPE_WEBDAV, backupDir) }
                    }
                    targetUnit()
                }
                args[5] = functionProxy("WebDavRunningPickDir", FUNCTION0_CLASS) {
                    context?.onPick?.let { invokeFunction2(it, BACKUP_TYPE_WEBDAV, null) }
                    targetUnit()
                }
                logWebDav("sync running DriveAuthCard mapped dir=$backupDir")
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (webDavRenderingSyncAuthCard.get() == true) return
                if (param.args?.getOrNull(3) as? Boolean == true) return
                val context = syncAuthCardRender.get() ?: return
                context.nativeAuthRowsSeen += 1
                if (context.bookId?.let { webDavRunningBackupBookIds.containsKey(it) } == true) return
                if (context.webDavRendered || !canShowWebDavEntry() || !hasWebDavLogin(currentContext())) return
                val composer = param.args?.getOrNull(6) ?: return
                context.webDavRendered = renderSyncWebDavAuthorizedCard(
                    composer = composer,
                    onSetupDefaultDir = context.onSetupDefaultDir,
                    onPick = context.onPick,
                )
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV sync auth drive card hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV sync auth drive card: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavSyncUploadRunningCard() {
    runCatching {
        val getBackupType = cls(AUTH_CARD_CLASS).declaredMethods.first {
            it.name == "getBackupType" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(getBackupType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val snapshot = param.args?.getOrNull(0) ?: return
                val kind = snapshot.callString("getKind")
                if (kind == "backup:$BACKUP_TYPE_WEBDAV") {
                    webDavRunningBackupPending.set(true)
                    param.result = BACKUP_TYPE_YUN115
                    logWebDav("sync running backup type mapped kind=$kind")
                }
            }
        })

        val yun115StateValue = cls(AUTH_CARD_CLASS).declaredMethods.first {
            it.name == "AuthCard\$lambda\$4" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(yun115StateValue, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if ((webDavRunningSyncAuthCardDepth.get() ?: 0) <= 0) return
                param.result = webDavYun115Auth()
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV sync running upload card hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV sync running upload card: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookDriveOtherAvailableCard() {
    runCatching {
        val method = cls(AUTH_CARD_CLASS).declaredMethods.first {
            it.name == DRIVE_OTHER_AVAILABLE_CARD_METHOD && it.parameterTypes.size == 4
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                val context = syncAuthCardRender.get()
                if (
                    hasWebDavLogin(currentContext()) &&
                    canShowWebDavEntry() &&
                    context != null &&
                    !context.webDavRendered &&
                    context.nativeAuthRowsSeen == 0 &&
                    context.bookId?.let { webDavRunningBackupBookIds.containsKey(it) } != true &&
                    syncWebDavAuthRenderedBeforeAvailable.get() != true &&
                    (webDavIconDepth.get() ?: 0) <= 0
                ) {
                    val rendered = renderSyncWebDavAuthorizedCard(
                        composer = args.getOrNull(2) ?: return,
                        onSetupDefaultDir = context.onSetupDefaultDir,
                        onPick = context.onPick,
                    )
                    if (rendered) {
                        context.webDavRendered = true
                        syncWebDavAuthRenderedBeforeAvailable.set(true)
                    }
                }
                val types = (args.getOrNull(0) as? Iterable<*>)?.mapNotNull {
                    (it as? Number)?.toInt()
                }.orEmpty()
                if ((webDavIconDepth.get() ?: 0) <= 0 && types.isNotEmpty()) {
                    val nativeTypes = types.filter { it != BACKUP_TYPE_WEBDAV }
                    val renderTypes = if (hasWebDavLogin(currentContext()) || !canShowWebDavEntry()) {
                        nativeTypes
                    } else {
                        nativeTypes + BACKUP_TYPE_WEBDAV
                    }
                    args[0] = renderTypes
                    syncAvailableTypes.set(renderTypes)
                    syncAvailableRowIndex.set(0)
                    if (BACKUP_TYPE_WEBDAV in renderTypes) {
                        syncAuthCardRender.get()?.webDavRendered = true
                    }
                }
                args[1]?.let { args[1] = wrapAuthorizeCallback(it) }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if ((webDavIconDepth.get() ?: 0) <= 0) {
                    syncAvailableTypes.remove()
                    syncAvailableRowIndex.remove()
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV sync available drive hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV sync available drive: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookImportAuthorizedList() {
    runCatching {
        val method = cls(BOOK_LIBRARY_SHEET_CLASS).declaredMethods.first {
            it.name == BOOK_LIBRARY_AUTH_LIST_METHOD && it.parameterTypes.size == 9
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                val rows = (args.getOrNull(1) as? Iterable<*>)?.toList().orEmpty()
                val rowTypes = rows.mapNotNull { row ->
                    runCatching {
                        val first = row?.javaClass?.methods?.firstOrNull {
                            it.name == "component1" && it.parameterTypes.isEmpty()
                        }?.apply { isAccessible = true }?.invoke(row) as? Number
                        first?.toInt()
                    }.getOrNull()
                }
                val additions = mutableListOf<Any>()
                if (canShowWebDavEntry() && hasWebDavLogin(currentContext()) && BACKUP_TYPE_WEBDAV !in rowTypes) {
                    additions.add(newKotlinPair(BACKUP_TYPE_WEBDAV, webDavAuth()))
                }
                if (additions.isNotEmpty()) {
                    args[1] = rows + additions
                }
                if (canShowLocalLibraryEntry()) {
                    importLocalLibraryRow.set(
                        ImportLocalLibraryRowContext(
                            navGraphScope = args.getOrNull(2),
                            coroutineScope = args.getOrNull(3),
                            sheetState = args.getOrNull(4),
                            intentReceiver = args.getOrNull(5),
                        ),
                    )
                } else {
                    importLocalLibraryRow.remove()
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                importLocalLibraryRow.remove()
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV import authorized list hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV import authorized list: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookImportLocalLibraryRow() {
    runCatching {
        val method = cls(BOOK_LIBRARY_SHEET_CLASS).declaredMethods.first {
            it.name == BOOK_LIBRARY_LOCAL_METHOD && it.parameterTypes.size == 3
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val composer = param.args?.getOrNull(1) ?: return
                if (!canShowLocalLibraryEntry()) return
                renderImportLocalLibraryRow(composer)
            }
        })
        XposedBridge.log("$LOG_PREFIX local library import row hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook local library import row: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookImportUnauthList() {
    runCatching {
        val method = cls(BOOK_LIBRARY_SHEET_CLASS).declaredMethods.first {
            it.name == BOOK_LIBRARY_UNAUTH_LIST_METHOD && it.parameterTypes.size == 8
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                val types = (args.getOrNull(0) as? Iterable<*>)?.mapNotNull {
                    (it as? Number)?.toInt()
                }.orEmpty()
                val loggedIn = hasWebDavLogin(currentContext())
                val webDavInNativeList = BACKUP_TYPE_WEBDAV in types
                val shouldAppendWebDav = canShowWebDavEntry() && !loggedIn && !webDavInNativeList && types.isNotEmpty()
                val renderTypes = if (loggedIn) {
                    types.filter {
                        (it != BACKUP_TYPE_WEBDAV || canShowWebDavEntry()) &&
                            (it != BACKUP_TYPE_LOCAL_LIBRARY || canShowLocalLibraryEntry())
                    }
                } else {
                    types.filter {
                        (it != BACKUP_TYPE_WEBDAV || canShowWebDavEntry()) &&
                            (it != BACKUP_TYPE_LOCAL_LIBRARY || canShowLocalLibraryEntry())
                    }
                }
                if (shouldAppendWebDav) {
                    args[0] = renderTypes + BACKUP_TYPE_WEBDAV
                } else if (renderTypes.size != types.size) {
                    args[0] = renderTypes
                }
                importUnauthRender.set(
                    ImportUnauthRenderContext(
                        expectedNativeRows = renderTypes.count { it != BACKUP_TYPE_WEBDAV },
                        webDavRendered = loggedIn || webDavInNativeList || shouldAppendWebDav,
                    ),
                )
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                importUnauthRender.remove()
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV import unauth list hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV import unauth list: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookImportCloudAuthorizedRow() {
    runCatching {
        val method = cls(BOOK_LIBRARY_SHEET_CLASS).declaredMethods.first {
            it.name == CLOUD_AUTHORIZED_ROW_METHOD && it.parameterTypes.size == 5
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> pushWebDavIcon()
                    BACKUP_TYPE_LOCAL_LIBRARY -> pushLocalLibraryIcon()
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> popWebDavIcon()
                    BACKUP_TYPE_LOCAL_LIBRARY -> popLocalLibraryIcon()
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV import authorized row hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV import authorized row: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookImportCloudUnauthRow() {
    runCatching {
        val method = cls(BOOK_LIBRARY_SHEET_CLASS).declaredMethods.first {
            it.name == CLOUD_UNAUTH_ROW_METHOD && it.parameterTypes.size == 4
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                val type = (args.getOrNull(0) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> pushWebDavIcon()
                    BACKUP_TYPE_LOCAL_LIBRARY -> pushLocalLibraryIcon()
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                val type = (args.getOrNull(0) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> {
                        popWebDavIcon()
                        return
                    }
                    BACKUP_TYPE_LOCAL_LIBRARY -> {
                        popLocalLibraryIcon()
                        return
                    }
                }
                val context = importUnauthRender.get() ?: return
                context.renderedNativeRows += 1
                if (!context.webDavRendered && context.renderedNativeRows >= max(context.expectedNativeRows, 1)) {
                    context.webDavRendered = true
                    val composer = args.getOrNull(2) ?: return
                    renderImportWebDavRow(composer)
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV import cloud row hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV import cloud row: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavStorageNavigate() {
    runCatching {
        val method = cls(NAV_GRAPH_SCOPE_CLASS).declaredMethods.first {
            it.name == NAVIGATE_METHOD && it.parameterTypes.size == 3
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val route = param.args?.getOrNull(0) ?: return
                if (route.javaClass.name != ROUTE_STORAGE_CLASS) return
                val type = route.javaClass.methods.firstOrNull { it.name == "getType" && it.parameterTypes.isEmpty() }
                    ?.invoke(route) as? Number ?: return
                if (type.toInt() == BACKUP_TYPE_LOCAL_LIBRARY) return
                if (type.toInt() != BACKUP_TYPE_WEBDAV && type.toInt() != BACKUP_TYPE_LOCAL_LIBRARY) return
                if (hasWebDavLogin(currentContext())) return
                param.args?.set(0, newThirdLoginRoute())
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV storage navigate hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV storage navigate: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookCloudStorageWebDavData() {
    runCatching {
        val repositoryClass = cls(CLOUD_STORAGE_REPOSITORY_CLASS)
        repositoryClass.declaredMethods.filter {
            it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == java.lang.Integer.TYPE &&
                it.name in setOf(CLOUD_STORAGE_GET_AUTH, CLOUD_STORAGE_GET_USER_INFO, CLOUD_STORAGE_GET_LIBRARY)
        }.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                    if (type != BACKUP_TYPE_WEBDAV && type != BACKUP_TYPE_LOCAL_LIBRARY) return
                    rememberCloudStorageRepository(param.thisObject)
                    param.result = when (method.name) {
                        CLOUD_STORAGE_GET_AUTH -> flowOf(if (type == BACKUP_TYPE_LOCAL_LIBRARY) localLibraryAuth() else webDavAuth())
                        CLOUD_STORAGE_GET_USER_INFO -> flowOf(
                            if (type == BACKUP_TYPE_LOCAL_LIBRARY) localLibraryCloudUserInfo() else webDavCloudUserInfo(),
                        )
                        CLOUD_STORAGE_GET_LIBRARY -> if (type == BACKUP_TYPE_LOCAL_LIBRARY) localLibraryFlow() else webDavLibraryFlow()
                        else -> null
                    }
                }
            })
        }
        repositoryClass.declaredMethods.filter {
            it.parameterTypes.size >= 2 &&
                it.parameterTypes[0] == java.lang.Integer.TYPE &&
                (
                    it.name.contains("openFolder") ||
                        it.name.contains("getFolderList") ||
                        it.name.contains("createFolder") ||
                        it.name.contains("move") ||
                        it.name.contains("rename") ||
                        it.name.contains("delete") ||
                        it.name.contains("getInfo") ||
                        it.name.contains("search") ||
                        it.name.contains("setupBackupDir")
                )
        }.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                    if (type != BACKUP_TYPE_WEBDAV && type != BACKUP_TYPE_LOCAL_LIBRARY) return
                    rememberCloudStorageRepository(param.thisObject)
                    param.result = webDavResult {
                        if (type == BACKUP_TYPE_LOCAL_LIBRARY) {
                            when {
                                method.name.contains("openFolder") -> {
                                    val path = localLibraryPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                    saveLocalLibraryBrowseDir(path)
                                    updateLocalLibraryStorageTrees(path)
                                    refreshLocalLibrary(path)
                                    targetUnit()
                                }
                                method.name.contains("getFolderList") -> {
                                    val path = localLibraryPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                    listLocalLibrary(path)
                                        .filter { it.isDirectory }
                                        .map { newLocalLibraryCloudFolder(it) }
                                }
                                method.name.contains("createFolder") -> {
                                    val parentPath = localLibraryPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                    val name = param.args?.getOrNull(2)?.toString().orEmpty()
                                    createLocalLibraryFolder(parentPath, name)
                                    refreshLocalLibrary(parentPath)
                                    targetUnit()
                                }
                                method.name.contains("move") -> {
                                    val path = localLibraryPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                    val dest = localLibraryPathArg(param.args?.getOrNull(2)?.toString().orEmpty())
                                    moveLocalLibraryEntry(path, dest)
                                    refreshLocalLibrary(dest)
                                    targetUnit()
                                }
                                method.name.contains("rename") -> {
                                    val book = param.args?.getOrNull(1)
                                    val path = localLibraryPathArg(cloudPathOf(book))
                                    val newName = param.args?.getOrNull(2)?.toString().orEmpty()
                                    renameLocalLibraryEntry(path, newName)
                                    refreshLocalLibrary(parentLocalLibraryPath(path))
                                    targetUnit()
                                }
                                method.name.contains("delete") -> {
                                    val target = param.args?.getOrNull(1)
                                    val path = localLibraryPathArg(cloudPathOf(target).ifBlank { target?.toString().orEmpty() })
                                    deleteLocalLibraryEntry(path)
                                    refreshLocalLibrary(parentLocalLibraryPath(path))
                                    targetUnit()
                                }
                                method.name.contains("getInfo") -> {
                                    val path = localLibraryPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                    val entry = localLibraryEntry(path) ?: syntheticLocalLibraryBookEntry(path)
                                    newLocalLibraryCloudBook(entry)
                                }
                                method.name.contains("search") -> {
                                    val query = param.args?.getOrNull(1)?.toString().orEmpty()
                                    searchLocalLibraryBooks(query)
                                }
                                method.name.contains("setupBackupDir") -> {
                                    launchLocalLibraryFolderPicker()
                                    targetUnit()
                                }
                                else -> null
                            }
                        } else when {
                        method.name.contains("openFolder") -> {
                            val path = webDavPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                            saveWebDavBrowseDir(path)
                            updateWebDavStorageTrees(path)
                            refreshWebDavLibrary(path)
                            targetUnit()
                        }
                        method.name.contains("getFolderList") -> {
                            val path = webDavPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                            listWebDav(path)
                                .filter { it.isDirectory }
                                .map { newCloudFolder(it) }
                        }
                        method.name.contains("createFolder") -> {
                            val parentPath = webDavPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                            val name = param.args?.getOrNull(2)?.toString().orEmpty()
                            webDavMkcol(childWebDavPath(parentPath, name))
                            refreshWebDavLibrary(parentPath)
                            targetUnit()
                        }
                        method.name.contains("move") -> {
                            val path = param.args?.getOrNull(1)?.toString().orEmpty()
                            val dest = param.args?.getOrNull(2)?.toString().orEmpty()
                            webDavMove(path, childWebDavPath(dest, normalizeWebDavPath(path).substringAfterLast('/')))
                            refreshWebDavLibrary(parentWebDavPath(dest))
                            targetUnit()
                        }
                        method.name.contains("rename") -> {
                            val book = param.args?.getOrNull(1)
                            val path = cloudPathOf(book)
                            val newName = param.args?.getOrNull(2)?.toString().orEmpty()
                            webDavMove(path, childWebDavPath(parentWebDavPath(path), newName))
                            refreshWebDavLibrary(parentWebDavPath(path))
                            targetUnit()
                        }
                        method.name.contains("delete") -> {
                            val path = webDavPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                            webDavDelete(path)
                            refreshWebDavLibrary(parentWebDavPath(path))
                            targetUnit()
                        }
                        method.name.contains("getInfo") -> {
                            val path = webDavPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                            val entry = listWebDav(parentWebDavPath(path))
                                .firstOrNull {
                                    normalizeWebDavPath(it.path) == normalizeWebDavPath(path) &&
                                        !it.isDirectory &&
                                        isSupportedBookFile(it.name)
                                }
                                ?: syntheticWebDavBookEntry(path)
                            logWebDav("getInfo path=$path found=${entry.path}")
                            newCloudBook(entry)
                        }
                        method.name.contains("search") -> {
                            val query = param.args?.getOrNull(1)?.toString().orEmpty()
                            searchWebDavBooks(query)
                        }
                        method.name.contains("setupBackupDir") -> {
                            val folders = (param.args?.getOrNull(1) as? List<*>).orEmpty()
                            val path = folders.lastOrNull()?.let { folder ->
                                folder.javaClass.methods.firstOrNull {
                                    it.name == "getPath" && it.parameterTypes.isEmpty()
                                }?.apply { isAccessible = true }?.invoke(folder)?.toString()
                            }.orEmpty()
                            if (path.isNotBlank()) {
                                saveWebDavBackupDir(path)
                            }
                            targetUnit()
                        }
                        else -> null
                        }
                    }
                }
            })
        }
        XposedBridge.log("$LOG_PREFIX WebDAV cloud storage data hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud storage data: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookHomeViewModelDependencies() {
    runCatching {
        cls(HOME_VIEW_MODEL_CLASS).declaredConstructors.forEach { constructor ->
            constructor.isAccessible = true
            XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    rememberHomeViewModelDependencies(param.thisObject)
                }
            })
        }
        XposedBridge.log("$LOG_PREFIX home viewModel dependency hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook home viewModel dependencies: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookHomeWebDavSearch() {
    runCatching {
        val method = cls(HOME_VIEW_MODEL_CLASS).declaredMethods.first {
            it.name == HOME_SEARCH_METHOD &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                rememberHomeViewModelDependencies(param.thisObject)
                val query = param.args?.getOrNull(0)?.toString().orEmpty()
                val seq = webDavHomeSearchSeq.incrementAndGet()
                val localSeq = localLibraryHomeSearchSeq.incrementAndGet()
                val onlineSeq = onlineCompletionHomeSearchSeq.incrementAndGet()
                rememberHomeSearchSnapshot(emptyList(), emptyList())
                if (query.isBlank()) return
                updateHomeOnlineCompletionSearchResults(
                    param.thisObject,
                    onlineCompletionPendingSearchGroups(query),
                )
                Thread({
                    runCatching {
                        if (query.isNotBlank()) {
                            Thread.sleep(HOME_SEARCH_DEBOUNCE_MS)
                            if (onlineCompletionHomeSearchSeq.get() != onlineSeq) return@runCatching
                        }
                        if (query.isBlank()) {
                            if (onlineCompletionHomeSearchSeq.get() != onlineSeq) return@runCatching
                            Handler(Looper.getMainLooper()).post {
                                if (onlineCompletionHomeSearchSeq.get() == onlineSeq) {
                                    updateHomeOnlineCompletionSearchResults(param.thisObject, emptyList())
                                }
                            }
                            return@runCatching
                        }
                        val onlineResults = searchOnlineCompletionSourcesProgressively(query) { snapshot ->
                            if (onlineCompletionHomeSearchSeq.get() != onlineSeq) return@searchOnlineCompletionSourcesProgressively
                            Handler(Looper.getMainLooper()).post {
                                if (onlineCompletionHomeSearchSeq.get() == onlineSeq) {
                                    updateHomeOnlineCompletionSearchResults(param.thisObject, snapshot)
                                }
                            }
                        }
                        logWebDav("home online search query=$query sources=${onlineResults.size}")
                    }.onFailure {
                        XposedBridge.log("$LOG_PREFIX online completion home search failed: ${it.stackTraceToString()}")
                    }
                }, "ReaMicroOnlineCompletionHomeSearch").start()
                Thread({
                    runCatching {
                        if (query.isNotBlank()) {
                            Thread.sleep(HOME_SEARCH_DEBOUNCE_MS)
                            if (webDavHomeSearchSeq.get() != seq) return@runCatching
                        }
                        val webDavResults = if (query.isBlank() || !hasWebDavLogin(currentContext())) {
                            emptyList()
                        } else {
                            searchWebDavBooks(query)
                        }
                        if (webDavHomeSearchSeq.get() != seq) return@runCatching
                        val localSnapshot = lastHomeSearchLocalResults
                        rememberHomeSearchSnapshot(webDavResults, localSnapshot)
                        Handler(Looper.getMainLooper()).post {
                            if (webDavHomeSearchSeq.get() == seq) {
                                updateHomeWebDavSearchResults(param.thisObject, webDavResults, localSnapshot, null)
                            }
                        }
                        scheduleLocalLibrarySearchRefresh(param.thisObject, query, webDavResults, seq, localSeq, LOCAL_LIBRARY_SEARCH_REFRESH_DELAY_MS)
                        scheduleLocalLibrarySearchRefresh(param.thisObject, query, webDavResults, seq, localSeq, LOCAL_LIBRARY_SEARCH_LATE_REFRESH_DELAY_MS)
                        logWebDav("home WebDAV search query=$query results=${webDavResults.size}")
                    }.onFailure {
                        XposedBridge.log("$LOG_PREFIX WebDAV home search failed: ${it.stackTraceToString()}")
                    }
                }, "ReaMicroWebDavHomeSearch").start()
                Thread({
                    runCatching {
                        if (query.isNotBlank()) {
                            Thread.sleep(HOME_SEARCH_DEBOUNCE_MS)
                            if (localLibraryHomeSearchSeq.get() != localSeq) return@runCatching
                        }
                        val localResults = if (query.isBlank() || !canShowLocalLibraryEntry()) {
                            emptyList()
                        } else {
                            searchLocalLibraryBooks(query)
                        }
                        if (localLibraryHomeSearchSeq.get() != localSeq) return@runCatching
                        val webDavSnapshot = lastHomeSearchWebDavResults
                        rememberHomeSearchSnapshot(webDavSnapshot, localResults)
                        Handler(Looper.getMainLooper()).post {
                            if (
                                webDavHomeSearchSeq.get() == seq &&
                                localLibraryHomeSearchSeq.get() == localSeq
                            ) {
                                updateHomeWebDavSearchResults(param.thisObject, webDavSnapshot, localResults, null)
                            }
                        }
                        logWebDav("home local search query=$query results=${localResults.size}")
                    }.onFailure {
                        XposedBridge.log("$LOG_PREFIX local library home search failed: ${it.stackTraceToString()}")
                    }
                }, "ReaMicroLocalLibraryHomeSearch").start()
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV home search hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV home search: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookHomeSearchResultWebDavSection() {
    runCatching {
        val method = cls(HOME_SEARCH_BAR_CLASS).declaredMethods.first {
            it.name == HOME_SEARCH_RESULT_LAZY_METHOD && it.parameterTypes.size == 5
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val map = param.args?.getOrNull(2) as? Map<*, *> ?: return
                val sections = buildList {
                    (map[BACKUP_TYPE_WEBDAV] as? List<*>)?.takeIf { it.isNotEmpty() }?.let {
                        add(HomeSearchSection(type = BACKUP_TYPE_WEBDAV, results = it))
                    }
                    (map[BACKUP_TYPE_LOCAL_LIBRARY] as? List<*>)?.takeIf {
                        canShowLocalLibraryEntry() && it.isNotEmpty()
                    }?.let {
                        add(HomeSearchSection(type = BACKUP_TYPE_LOCAL_LIBRARY, results = it))
                    }
                    (map[BACKUP_TYPE_ONLINE_COMPLETION] as? List<*>)?.forEach { item ->
                        val group = item as? OnlineSearchGroup ?: return@forEach
                        runCatching {
                            val title = onlineCompletionGroupTitle(group)
                            val renderType = onlineCompletionRenderTypeForSource(group.source, title)
                            add(
                                HomeSearchSection(
                                    type = renderType,
                                    title = title,
                                    results = onlineCompletionGroupVisibleCloudBooks(group),
                                ),
                            )
                        }.onFailure { error ->
                            XposedBridge.log(
                                "$LOG_PREFIX online completion render section failed source=${group.source.name} " +
                                    "results=${group.results.size} first=${group.results.firstOrNull()?.name.orEmpty()} " +
                                    "error=${error.stackTraceToString()}",
                            )
                        }
                    }
                }
                // 关键：把我们的补全条目（OnlineSearchGroup，非 host CloudBook 类型）
                // 以及任何 host 原生无法渲染的空/异类条目从 map 中剔除，避免 host 原生
                // CloudResultList 对其做 list.get(0) 触发 IndexOutOfBounds 崩溃
                // （不同 host 版本容忍度不同，2.1.0 会直接崩）。我们自有的 footer hook
                // 仍会从 sections 里正常渲染补全结果。
                sanitizeHostCloudSearchResults(param, map)
                if (sections.isEmpty()) return
                val intentReceiver = param.args?.getOrNull(3) ?: return
                homeWebDavSearchRender.set(HomeSearchRenderContext(sections, intentReceiver))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                homeWebDavSearchRender.remove()
            }
        })

        val footer = cls(FOOTER_CLASS).declaredMethods.first {
            it.name == FOOTER_METHOD && it.parameterTypes.size == 2
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(footer, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = homeWebDavSearchRender.get() ?: return
                if (context.rendered) return
                val lazyListScope = param.args?.getOrNull(0) ?: return
                context.rendered = true
                context.sections.forEach { section ->
                    addHomeWebDavSearchSection(lazyListScope, section.type, section.title, section.results, context.intentReceiver)
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV home search result hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV home search result: ${it.stackTraceToString()}")
    }
}

/**
 * 把 host 原生 CloudResultList 无法安全渲染的条目从 cloudSearchResults map 中剔除，
 * 再回写到方法参数，避免 host 自身对我们注入的补全条目（OnlineSearchGroup）做
 * list.get(0) 触发 IndexOutOfBounds（v2.1.0 等版本会直接崩溃）。
 * 补全结果由我们自己的 footer hook 从 sections 渲染，因此剔除不影响展示。
 */

internal fun WebDavDriveHook.hookHomeSearchTapCancellation() {
    runCatching {
        val method = homeSearchTapMethod()
        XposedBridge.hookMethod(method, object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val book = param.args?.getOrNull(1) ?: return
                val type = book.callInt("getType")
                if (isOnlineCompletionPath(cloudPathOf(book))) {
                    handleOnlineCompletionSearchTap(book)
                    param.result = targetUnit()
                    return
                }
                if (tryHandleRunningCloudDownloadTap(book, type)) {
                    param.result = targetUnit()
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX home search download cancellation hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook home search download cancellation: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavCloudDownload() {
    runCatching {
        val method = cls(WORKER_MANAGER_CLASS).declaredMethods.first {
            it.name == WORKER_ENQUEUE_DOWNLOAD_METHOD &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name == CLOUD_BOOK_CLASS
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val book = param.args?.getOrNull(0) ?: return
                val type = book.javaClass.methods.firstOrNull {
                    it.name == "getType" && it.parameterTypes.isEmpty()
                }?.apply { isAccessible = true }?.invoke(book) as? Number ?: return
                if (type.toInt() != BACKUP_TYPE_WEBDAV && type.toInt() != BACKUP_TYPE_LOCAL_LIBRARY) return

                param.result = runCatching {
                    if (type.toInt() == BACKUP_TYPE_LOCAL_LIBRARY) {
                        enqueueLocalLibraryImport(param.thisObject, book)
                    } else {
                        enqueueWebDavDownload(param.thisObject, book)
                    }
                }.getOrElse {
                    XposedBridge.log("$LOG_PREFIX cloud download/import failed: ${it.stackTraceToString()}")
                    val title = if (type.toInt() == BACKUP_TYPE_LOCAL_LIBRARY) LOCAL_LIBRARY_TITLE else WEBDAV_TITLE
                    newWorkHandle(UUID.randomUUID().toString(), newWorkState("Error", 100, it.message ?: "import failed", null, title))
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val book = param.args?.getOrNull(0) ?: return
                val type = book.callInt("getType")
                if (type !in NATIVE_CLOUD_DOWNLOAD_TYPES) return
                val handle = param.result ?: return
                registerNativeCloudDownload(param.thisObject, book, handle, type)
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV cloud download hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud download: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookNativeCloudDownloadCancellation() {
    runCatching {
        val method = cls(WORK_TRACKER_CLASS).declaredMethods.first {
            it.name == "setState" && it.parameterTypes.size == 2
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val id = param.args?.getOrNull(0)?.toString().orEmpty()
                val token = runningNativeCloudDownloadsById[id] ?: return
                val state = param.args?.getOrNull(1) ?: return
                val status = workStateStatusName(state)
                if (token.cancelRequested && (status == "Running" || status == "Success")) {
                    throw CloudDownloadCancelledException()
                }
                if (token.cancelRequested && status == "Error") {
                    param.args?.set(1, newWorkState("Cancelled", 100, null, null, token.name))
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val id = param.args?.getOrNull(0)?.toString().orEmpty()
                val token = runningNativeCloudDownloadsById[id] ?: return
                val state = param.args?.getOrNull(1) ?: return
                when (workStateStatusName(state)) {
                    "Success", "Error", "Cancelled" -> unregisterNativeCloudDownload(token)
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX native cloud download cancellation hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook native cloud download cancellation: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavImportBookSource() {
    runCatching {
        val repositoryClass = cls(BOOKSHELF_REPOSITORY_CLASS)
        // 2.2.0 importBook 6 参、2.3.0 起 7 参（新增进度回调）。按方法名 + 末参 Continuation 匹配，
        // 兼容新旧签名；hook 内只改 args[3]=url、args[4]=size，两下标在新版仍不变。
        repositoryClass.declaredMethods.filter {
            it.name == BOOKSHELF_IMPORT_BOOK_METHOD &&
                it.parameterTypes.size >= 6 &&
                it.parameterTypes.last().name == KOTLIN_CONTINUATION_CLASS
        }.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    rememberBookshelfRepository(param.thisObject)
                    val platformFile = param.args?.getOrNull(0) ?: return
                    val keys = platformFileKeys(platformFile)
                    val source = keys.firstNotNullOfOrNull { pendingWebDavImports[it] } ?: return
                    if (source.sourceUrl.isNotBlank()) {
                        param.args?.set(3, source.sourceUrl)
                    }
                    source.sourceSize?.takeIf { it > 0L }?.let {
                        param.args?.set(4, java.lang.Long.valueOf(it))
                    }
                    logWebDav("importBook source override url=${source.sourceUrl.redactWebDavUrl()} size=${source.sourceSize ?: 0}")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val platformFile = param.args?.getOrNull(0) ?: return
                    platformFileKeys(platformFile).forEach { pendingWebDavImports.remove(it) }
                }
            })
        }
        XposedBridge.log("$LOG_PREFIX WebDAV import source hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV import source: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavCloudBackup() {
    runCatching {
        val method = cls(WORKER_MANAGER_CLASS).declaredMethods.first {
            it.name == WORKER_ENQUEUE_BACKUP_METHOD &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == java.lang.Long.TYPE &&
                it.parameterTypes[1] == java.lang.Integer.TYPE &&
                it.parameterTypes[2] == String::class.java
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val bookId = (param.args?.getOrNull(0) as? Number)?.toLong() ?: return
                val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                if (type != BACKUP_TYPE_WEBDAV) return
                val dir = param.args?.getOrNull(2)?.toString().orEmpty().ifBlank { currentWebDavBackupDir() }
                logWebDav("enqueueBackup intercepted bookId=$bookId dir=$dir")
                param.result = runCatching {
                    enqueueWebDavBackup(param.thisObject, bookId, dir)
                }.getOrElse {
                    XposedBridge.log("$LOG_PREFIX WebDAV backup failed: ${it.stackTraceToString()}")
                    newWorkHandle(UUID.randomUUID().toString(), newWorkState("Error", 100, it.message ?: "WebDAV backup failed", null, WEBDAV_TITLE))
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV cloud backup hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud backup: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookBookBackupViewModelState() {
    runCatching {
        cls(BOOK_BACKUP_VIEW_MODEL_CLASS).declaredConstructors
            .filter { it.parameterTypes.size == 3 && it.parameterTypes[0] == java.lang.Long.TYPE }
            .forEach { constructor ->
                constructor.isAccessible = true
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        rememberCloudStorageRepository(param.args?.getOrNull(2))
                        val viewModel = param.thisObject ?: return
                        synchronized(webDavBackupViewModels) {
                            webDavBackupViewModels.removeAll { it.get() == null || it.get() === viewModel }
                            webDavBackupViewModels.add(WeakReference(viewModel))
                        }
                        logWebDav("BookBackupViewModel registered bookId=${param.args?.getOrNull(0)}")
                    }
                })
            }
        XposedBridge.log("$LOG_PREFIX WebDAV book backup viewModel hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV book backup viewModel: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookBookBackupWebDavCard() {
    runCatching {
        val method = method(BOOK_BACKUP_SCREEN_CLASS, BOOK_BACKUP_CONTENT_METHOD, 9)
        XposedBridge.hookMethod(method, object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val book = param.args?.getOrNull(0)
                val cloudBook = param.args?.getOrNull(1)
                val type = cloudBook?.callInt("getType") ?: 0
                val bookBackupType = bookBackupTypeOf(book)
                val backupId = bookBackupIdOf(book)
                val bookId = book?.callLong("getId") ?: 0L
                val recent = webDavRecentBackups[bookId]
                val seq = bookBackupContentLogSeq.incrementAndGet()
                if (seq <= 12) {
                    logWebDav(
                        "backup content render#$seq bookId=$bookId " +
                            "bookType=$bookBackupType backupId=$backupId cloudType=$type " +
                            "cloudPath=${cloudBook?.callString("getPath").orEmpty()} recent=${recent != null}",
                    )
                }
                if (recent != null && bookBackupType != BACKUP_TYPE_WEBDAV && type != BACKUP_TYPE_WEBDAV) {
                    param.args?.set(0, recent.book)
                    param.args?.set(1, copyCloudBookWithType(recent.backup, BACKUP_TYPE_YUN115))
                    logWebDav("backup content recent WebDAV card path=${recent.backup.callString("getPath")}")
                    pushWebDavBackupCard()
                    return
                }
                if (bookBackupType == BACKUP_TYPE_WEBDAV || type == BACKUP_TYPE_WEBDAV) {
                    logWebDav(
                        "backup content bookType=$bookBackupType cloudType=$type " +
                            "name=${cloudBook?.callString("getName").orEmpty()} path=${cloudBook?.callString("getPath").orEmpty()}",
                    )
                }
                when {
                    type == BACKUP_TYPE_WEBDAV -> {
                        cloudBook ?: return
                        param.args?.set(1, copyCloudBookWithType(cloudBook, BACKUP_TYPE_YUN115))
                        logWebDav("backup content mapped WebDAV card path=${cloudBook.callString("getPath")}")
                        pushWebDavBackupCard()
                    }
                    bookBackupType == BACKUP_TYPE_WEBDAV -> {
                        if (backupId.isBlank()) return
                        val fallbackBook = newCloudBook(syntheticWebDavBookEntry(backupId))
                        param.args?.set(1, copyCloudBookWithType(fallbackBook, BACKUP_TYPE_YUN115))
                        logWebDav("backup content synthesized WebDAV card path=$backupId")
                        pushWebDavBackupCard()
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if ((webDavBackupCardDepth.get() ?: 0) > 0) {
                    popWebDavBackupCard()
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV backup card hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV backup card: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavYun115BackupCard() {
    runCatching {
        val method = method(DRIVE_CARD_CLASS, YUN115_NET_DISK_CARD_METHOD, 5)
        XposedBridge.hookMethod(method, object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cloudBook = param.args?.getOrNull(0) ?: return
                if (cloudBook.callInt("getType") != BACKUP_TYPE_WEBDAV) return
                param.args?.set(0, copyCloudBookWithType(cloudBook, BACKUP_TYPE_YUN115))
                pushWebDavBackupCard()
                logWebDav("Yun115 card mapped WebDAV path=${cloudBook.callString("getPath")}")
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if ((webDavBackupCardDepth.get() ?: 0) > 0) {
                    popWebDavBackupCard()
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV Yun115 backup card hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV Yun115 backup card: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookCloudStorageWebDavStrings() {
    runCatching {
        cls(STRING_RESOURCES_KT_CLASS).declaredMethods.filter {
            it.name == STRING_RESOURCE_METHOD && it.returnType == String::class.java
        }.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    when {
                        (localLibraryDefaultFolderDepth.get() ?: 0) > 0 -> {
                            val index = localLibraryDefaultFolderStringIndex.get() ?: 0
                            localLibraryDefaultFolderStringIndex.set(index + 1)
                            param.result = if (index == 0) LOCAL_LIBRARY_FOLDER_TITLE else ""
                        }
                        (localLibraryCloudTitleDepth.get() ?: 0) > 0 -> param.result = LOCAL_LIBRARY_TITLE
                        (webDavCloudTitleDepth.get() ?: 0) > 0 -> param.result = WEBDAV_TITLE
                        (onlineCompletionCloudTitleDepth.get() ?: 0) > 0 -> {
                            param.result = onlineCompletionCloudTitleText.get().orEmpty().ifBlank { ONLINE_COMPLETION_TITLE }
                        }
                        webDavNotAuthTipPending.get() == true -> {
                            webDavNotAuthTipPending.set(false)
                            param.result = WEBDAV_AUTH_TIPS
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val key = param.args?.getOrNull(0)?.callString("getKey").orEmpty()
                    if ((webDavRunningSyncAuthCardDepth.get() ?: 0) > 0 && key == STRING_KEY_UPLOAD_TO_115) {
                        param.result = "上传到 $WEBDAV_TITLE"
                    }
                }
            })
        }
        XposedBridge.log("$LOG_PREFIX WebDAV cloud storage string hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud storage strings: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookCloudStorageWebDavTitle() {
    runCatching {
        val method = cls(CLOUD_STORAGE_SCREEN_CLASS).declaredMethods.first {
            it.name == CLOUD_STORAGE_BAR_METHOD && it.parameterTypes.size == 5
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> webDavCloudTitleDepth.set((webDavCloudTitleDepth.get() ?: 0) + 1)
                    BACKUP_TYPE_LOCAL_LIBRARY -> localLibraryCloudTitleDepth.set((localLibraryCloudTitleDepth.get() ?: 0) + 1)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> {
                        val next = (webDavCloudTitleDepth.get() ?: 0) - 1
                        if (next <= 0) webDavCloudTitleDepth.remove() else webDavCloudTitleDepth.set(next)
                    }
                    BACKUP_TYPE_LOCAL_LIBRARY -> {
                        val next = (localLibraryCloudTitleDepth.get() ?: 0) - 1
                        if (next <= 0) localLibraryCloudTitleDepth.remove() else localLibraryCloudTitleDepth.set(next)
                    }
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV cloud storage title hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud storage title: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookCloudStorageWebDavScreenScope() {
    runCatching {
        val method = cls(CLOUD_STORAGE_SCREEN_CLASS).declaredMethods.first {
            it.name == CLOUD_STORAGE_SCREEN_METHOD && it.parameterTypes.size == 4
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> webDavCloudScreenDepth.set((webDavCloudScreenDepth.get() ?: 0) + 1)
                    BACKUP_TYPE_LOCAL_LIBRARY -> localLibraryCloudScreenDepth.set((localLibraryCloudScreenDepth.get() ?: 0) + 1)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> {
                        val next = (webDavCloudScreenDepth.get() ?: 0) - 1
                        if (next <= 0) webDavCloudScreenDepth.remove() else webDavCloudScreenDepth.set(next)
                        refreshCloudStorageScreen(type)
                    }
                    BACKUP_TYPE_LOCAL_LIBRARY -> {
                        val next = (localLibraryCloudScreenDepth.get() ?: 0) - 1
                        if (next <= 0) localLibraryCloudScreenDepth.remove() else localLibraryCloudScreenDepth.set(next)
                        refreshCloudStorageScreen(type)
                    }
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV cloud storage screen scope hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud storage screen scope: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookCloudStorageWebDavAuthTips() {
    runCatching {
        val method = cls(NOT_AUTH_CLASS).declaredMethods.first {
            it.name == NOT_AUTH_METHOD && it.parameterTypes.size == 4
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                if (type == BACKUP_TYPE_LOCAL_LIBRARY) {
                    param.result = targetUnit()
                    return
                }
                if (type != BACKUP_TYPE_WEBDAV) return
                param.args?.set(0, BACKUP_TYPE_BAIDU)
                webDavNotAuthDepth.set((webDavNotAuthDepth.get() ?: 0) + 1)
                webDavNotAuthTipPending.set(true)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val next = (webDavNotAuthDepth.get() ?: 0) - 1
                if (next <= 0) {
                    webDavNotAuthDepth.remove()
                    webDavNotAuthTipPending.remove()
                } else {
                    webDavNotAuthDepth.set(next)
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV cloud not-auth hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud not-auth: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookCloudStorageWebDavViewModelState() {
    runCatching {
        val constructor = cls(CLOUD_STORAGE_VIEW_MODEL_CLASS).declaredConstructors.first {
            it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == java.lang.Integer.TYPE
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                if (type != BACKUP_TYPE_WEBDAV && type != BACKUP_TYPE_LOCAL_LIBRARY) return
                val viewModel = param.thisObject ?: return
                synchronized(webDavStorageViewModels) {
                    webDavStorageViewModels.removeAll { it.get() == null || it.get() === viewModel }
                    webDavStorageViewModels.add(WeakReference(viewModel))
                }
                if (type == BACKUP_TYPE_LOCAL_LIBRARY) {
                    updateLocalLibraryStorageTree(viewModel, currentLocalLibraryBrowseDir())
                    refreshLocalLibraryAsync(currentLocalLibraryBrowseDir(), force = true)
                    logWebDav("local library cloud storage viewModel registered")
                } else {
                    updateWebDavStorageTree(viewModel, currentWebDavBrowseDir())
                    refreshWebDavLibraryAsync(currentWebDavBrowseDir())
                    logWebDav("WebDAV cloud storage viewModel registered")
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV cloud storage state hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud storage state: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavCloudTap() {
    runCatching {
        val method = cls(CLOUD_STORAGE_VIEW_MODEL_CLASS).declaredMethods.first {
            it.name == CLOUD_STORAGE_ON_INTENT_METHOD &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name == CLOUD_STORAGE_UI_EVENT_CLASS
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val viewModel = param.thisObject ?: return
                val type = viewModel.javaClass.methods.firstOrNull {
                    it.name == "getType" && it.parameterTypes.isEmpty()
                }?.apply { isAccessible = true }?.invoke(viewModel) as? Number ?: return
                val typeInt = type.toInt()

                val intent = param.args?.getOrNull(0) ?: return
                if (intent.javaClass.name != CLOUD_STORAGE_UI_EVENT_TAP_CLASS) return
                val book = intent.javaClass.methods.firstOrNull {
                    it.name == "getBook" && it.parameterTypes.isEmpty()
                }?.apply { isAccessible = true }?.invoke(intent) ?: return

                if (tryHandleRunningCloudDownloadTap(book, typeInt)) {
                    param.result = null
                    return
                }
                if (typeInt != BACKUP_TYPE_WEBDAV) return

                Thread({
                    runCatching {
                        val workerManager = viewModel.javaClass.methods.first {
                            it.name == "getWorkerManager" && it.parameterTypes.isEmpty()
                        }.apply { isAccessible = true }.invoke(viewModel)
                        val enqueueDownload = this@hookWebDavCloudTap.method(
                            WORKER_MANAGER_CLASS,
                            WORKER_ENQUEUE_DOWNLOAD_METHOD,
                            1,
                        )
                        enqueueDownload.invoke(workerManager, book)
                        logWebDav("tap queued cloud import type=$typeInt path=${cloudPathOf(book)}")
                    }.onFailure {
                        XposedBridge.log("$LOG_PREFIX cloud tap download failed: ${it.stackTraceToString()}")
                    }
                }, "ReaMicroWebDavTap").start()
                param.result = null
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV cloud tap hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud tap: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookThirdAccountWebDavRoute() {
    runCatching {
        val methods = setupRouteMethods()
        methods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val backStackEntry = param.args?.getOrNull(2) ?: return
                    if (!isRouteDestination(backStackEntry, ROUTE_THIRD_ACCOUNT_CLASS)) return
                    val route = runCatching { navBackStackEntryToThirdAccount(backStackEntry) }.getOrNull() ?: return
                    val type = route.javaClass.methods.firstOrNull { it.name == "getType" && it.parameterTypes.isEmpty() }
                        ?.invoke(route) as? Number ?: return
                    val navGraphScope = param.args?.getOrNull(0) ?: return
                    if (type.toInt() == BACKUP_TYPE_LOCAL_LIBRARY) {
                        clearWebDavAccountContext()
                        markLocalLibraryAccountContext(navGraphScope)
                        val composer = param.args?.getOrNull(3) ?: return
                        if (renderLocalLibraryAccountRoute(navGraphScope, composer)) {
                            param.result = targetUnit()
                        }
                        return
                    }
                    if (type.toInt() != BACKUP_TYPE_WEBDAV) {
                        clearWebDavAccountContext()
                        clearLocalLibraryAccountContext()
                        return
                    }
                    clearLocalLibraryAccountContext()
                    markWebDavAccountContext(navGraphScope)
                    val composer = param.args?.getOrNull(3) ?: return
                    if (renderWebDavAccountRoute(navGraphScope, composer)) {
                        param.result = targetUnit()
                    }
                }
            })
        }
        XposedBridge.log("$LOG_PREFIX WebDAV third account route hook installed: ${methods.size} setup lambdas")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV third account route: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavAccountScreenReuse() {
    runCatching {
        val method = method(BAIDU_ACCOUNT_SCREEN_CLASS, BAIDU_ACCOUNT_SCREEN_METHOD, 3)
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (webDavAccountNavGraphScope.get() == null) return
                webDavAccountScreenDepth.set((webDavAccountScreenDepth.get() ?: 0) + 1)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val next = (webDavAccountScreenDepth.get() ?: 0) - 1
                if (next <= 0) {
                    webDavAccountScreenDepth.remove()
                } else {
                    webDavAccountScreenDepth.set(next)
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV account screen reuse hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV account screen reuse: ${it.stackTraceToString()}")
    }
    runCatching {
        val method = method(Y115_ACCOUNT_SCREEN_CLASS, Y115_ACCOUNT_SCREEN_METHOD, 3)
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (localLibraryAccountNavGraphScope.get() == null) return
                localLibraryAccountScreenDepth.set((localLibraryAccountScreenDepth.get() ?: 0) + 1)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val next = (localLibraryAccountScreenDepth.get() ?: 0) - 1
                if (next <= 0) {
                    localLibraryAccountScreenDepth.remove()
                } else {
                    localLibraryAccountScreenDepth.set(next)
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX local library account screen reuse hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook local library account screen reuse: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavAccountSettingComponents() {
    runCatching {
        val defaultFolder = method(BAIDU_ACCOUNT_SCREEN_CLASS, BAIDU_ACCOUNT_DEFAULT_FOLDER_METHOD, 4)
        XposedBridge.hookMethod(defaultFolder, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isWebDavAccountContext()) return
                val navGraphScope = webDavAccountNavGraphScope.get()
                    ?: webDavAccountNavGraphScopeStrong
                    ?: webDavAccountNavGraphScopeRef?.get()
                param.args?.set(0, currentWebDavBackupDir())
                param.args?.set(1, functionProxy("WebDavAccountDefaultFolder", FUNCTION0_CLASS) {
                    navGraphScope?.let { navigateCloudFolder(it) }
                    targetUnit()
                })
            }
        })

        val logout = method(BAIDU_ACCOUNT_SCREEN_CLASS, BAIDU_ACCOUNT_LOGOUT_METHOD, 3)
        XposedBridge.hookMethod(logout, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isWebDavAccountContext()) return
                val navGraphScope = webDavAccountNavGraphScope.get()
                    ?: webDavAccountNavGraphScopeStrong
                    ?: webDavAccountNavGraphScopeRef?.get()
                param.args?.set(0, functionProxy("WebDavAccountLogout", FUNCTION0_CLASS) {
                    clearWebDavLogin()
                    clearWebDavAccountContext()
                    navGraphScope?.let { scope ->
                        if (!navigateHome(scope)) {
                            popBackStack(scope)
                        }
                    }
                    targetUnit()
                })
            }
        })

        listOf(BAIDU_ACCOUNT_QUERY_ORDER_BY_METHOD, BAIDU_ACCOUNT_QUERY_ORDER_DIRECTION_METHOD).forEach { methodName ->
            val query = method(BAIDU_ACCOUNT_SCREEN_CLASS, methodName, 4)
            XposedBridge.hookMethod(query, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isWebDavAccountContext()) return
                    val isOrderBy = methodName == BAIDU_ACCOUNT_QUERY_ORDER_BY_METHOD
                    param.args?.set(0, if (isOrderBy) currentWebDavOrderBy() else currentWebDavOrderDirection())
                    param.args?.set(1, functionProxy("WebDavAccountQuery", FUNCTION1_CLASS) { args ->
                        val value = args?.getOrNull(0)?.toString().orEmpty()
                        if (isOrderBy) {
                            saveWebDavOrderBy(value)
                        } else {
                            saveWebDavOrderDirection(value)
                        }
                        updateWebDavAccountAuthFlow()
                        refreshWebDavLibraryAsync(currentWebDavBrowseDir())
                        targetUnit()
                    })
                }
            })
        }
        val y115DefaultFolder = method(Y115_ACCOUNT_SCREEN_CLASS, Y115_ACCOUNT_DEFAULT_FOLDER_METHOD, 4)
        XposedBridge.hookMethod(y115DefaultFolder, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isLocalLibraryAccountContext()) return
                param.args?.set(0, localLibraryPickerDir())
                param.args?.set(1, functionProxy("LocalLibraryDefaultFolder", FUNCTION0_CLASS) {
                    launchLocalLibraryFolderPicker()
                    targetUnit()
                })
                localLibraryDefaultFolderDepth.set((localLibraryDefaultFolderDepth.get() ?: 0) + 1)
                localLibraryDefaultFolderStringIndex.set(0)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val next = (localLibraryDefaultFolderDepth.get() ?: 0) - 1
                if (next <= 0) {
                    localLibraryDefaultFolderDepth.remove()
                    localLibraryDefaultFolderStringIndex.remove()
                } else {
                    localLibraryDefaultFolderDepth.set(next)
                }
                if (!isLocalLibraryAccountContext()) return
                val composer = param.args?.getOrNull(2) ?: return
                renderLocalLibraryFolderRows(composer)
            }
        })
        val y115Logout = method(Y115_ACCOUNT_SCREEN_CLASS, Y115_ACCOUNT_LOGOUT_METHOD, 3)
        XposedBridge.hookMethod(y115Logout, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isLocalLibraryAccountContext()) return
                param.result = targetUnit()
            }
        })
        listOf(Y115_ACCOUNT_QUERY_ORDER_BY_METHOD, Y115_ACCOUNT_QUERY_ORDER_DIRECTION_METHOD).forEach { methodName ->
            val query = method(Y115_ACCOUNT_SCREEN_CLASS, methodName, 4)
            XposedBridge.hookMethod(query, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isLocalLibraryAccountContext()) return
                    val isOrderBy = methodName == Y115_ACCOUNT_QUERY_ORDER_BY_METHOD
                    param.args?.set(0, if (isOrderBy) currentLocalLibraryOrderBy() else currentLocalLibraryOrderDirection())
                    param.args?.set(1, functionProxy("LocalLibraryAccountQuery", FUNCTION1_CLASS) { args ->
                        val value = args?.getOrNull(0)?.toString().orEmpty()
                        if (isOrderBy) {
                            saveLocalLibraryOrderBy(value)
                        } else {
                            saveLocalLibraryOrderDirection(value)
                        }
                        updateLocalLibraryAccountAuthFlow()
                        refreshLocalLibraryAsync(currentLocalLibraryBrowseDir())
                        targetUnit()
                    })
                }
            })
        }
        XposedBridge.log("$LOG_PREFIX WebDAV account setting component hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV account setting components: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavAccountAuthFlow() {
    runCatching {
        val method = method(BAIDU_VIEW_MODEL_CLASS, BAIDU_VIEW_MODEL_GET_AUTH_METHOD, 0)
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isWebDavAccountContext()) return
                param.result = webDavAccountAuthFlow()
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV account auth flow hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV account auth flow: ${it.stackTraceToString()}")
    }
    runCatching {
        val method = method(Y115_VIEW_MODEL_CLASS, Y115_VIEW_MODEL_GET_AUTH_METHOD, 0)
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isLocalLibraryAccountContext()) return
                param.result = localLibraryAccountAuthFlow()
            }
        })
        XposedBridge.log("$LOG_PREFIX local library account auth flow hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook local library account auth flow: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavAccountTopBarTitle() {
    runCatching {
        // 2.3.0 beta 起 AppTopBar 参数重排并新增 Function3 尾随槽（8→9 参），旧的固定 8 参定位会失败。
        // 按名字 + 首参 String 匹配、取参数最多者，兼容签名变化；hook 仅改 args[0]（title 仍是首个 String 参）。
        val method = cls(APP_TOP_BAR_CLASS).declaredMethods
            .filter { it.name == APP_TOP_BAR_METHOD && it.parameterTypes.firstOrNull() == String::class.java }
            .maxByOrNull { it.parameterTypes.size }
            ?.apply { isAccessible = true }
            ?: error("$APP_TOP_BAR_CLASS.$APP_TOP_BAR_METHOD not found")
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val title = param.args?.getOrNull(0)?.toString().orEmpty()
                if (isLocalLibraryAccountContext()) {
                    param.args?.set(0, LOCAL_LIBRARY_TITLE)
                    return
                }
                if (!isWebDavAccountContext() || !isBaiduTitle(title)) return
                param.args?.set(0, WEBDAV_TITLE)
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV account top bar title hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV account top bar title: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookWebDavAccountDefaultFolderLambda() {
    runCatching {
        val method = cls(BAIDU_ACCOUNT_SCREEN_CLASS).declaredMethods.first {
            it.name == BAIDU_ACCOUNT_DEFAULT_FOLDER_LAMBDA_METHOD && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val navGraphScope = param.args?.getOrNull(0) ?: return
                if (
                    (webDavAccountScreenDepth.get() ?: 0) <= 0 &&
                    webDavAccountNavGraphScopeStrong !== navGraphScope &&
                    webDavAccountNavGraphScopeRef?.get() !== navGraphScope
                ) return
                navigateCloudFolder(navGraphScope)
                param.result = targetUnit()
            }
        })
        XposedBridge.log("$LOG_PREFIX WebDAV account default-folder lambda hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV account default-folder lambda: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.hookThirdLoginWebDavRoute() {
    runCatching {
        val methods = setupRouteMethods()
        methods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val backStackEntry = param.args?.getOrNull(2) ?: return
                    if (!isRouteDestination(backStackEntry, ROUTE_THIRD_LOGIN_CLASS)) return
                    val route = runCatching { navBackStackEntryToThirdLogin(backStackEntry) }.getOrNull() ?: return
                    val type = route.javaClass.methods.firstOrNull { it.name == "getType" && it.parameterTypes.isEmpty() }
                        ?.invoke(route) as? Number ?: return
                    if (type.toInt() != BACKUP_TYPE_WEBDAV) return
                    val navGraphScope = param.args?.getOrNull(0) ?: return
                    val composer = param.args?.getOrNull(3) ?: return
                    renderWebDavLoginRoute(navGraphScope, composer)
                    param.result = targetUnit()
                }
            })
        }
        XposedBridge.log("$LOG_PREFIX WebDAV third login route hook installed: ${methods.size} setup lambdas")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to hook WebDAV third login route: ${it.stackTraceToString()}")
    }
}
