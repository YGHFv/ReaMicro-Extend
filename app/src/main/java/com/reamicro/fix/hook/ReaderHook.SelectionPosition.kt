package com.reamicro.fix.hook

import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostNameMatches

import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.HostExactRecordCopy
import com.reamicro.fix.epub.editor.ReaderPositionCopy
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.reflect.Proxy

internal fun ReaderHook.persistSelectionReadingPosition(viewModel: Any, epub: Any, anchor: Any) {
    checkSelectionEditSession(viewModel, epub)
    val raw = anchor.toString()
    require(raw.isNotBlank()) { "当前阅读锚点为空" }
    val b4 = HostSymbols.abiProfileId == "2.3.3-b4"
    val flow = XposedHelpers.getObjectField(viewModel, if (b4) "uiState" else "bookFlow")
        ?: error("宿主图书状态不存在")
    val flowType = classLoader.hostClass("kotlinx.coroutines.flow.Flow")
    val continuation = classLoader.hostClass("kotlin.coroutines.Continuation")
    val first = if (b4) classLoader.hostClass("ReaMicroHost.FlowFirst")
        .hostAbiMethod("e", flowType, continuation)
    else classLoader.hostClass("kotlinx.coroutines.flow.FlowKt").hostMethod("firstOrNull", flowType, continuation)
    val firstValue = invokeSelectionHostSuspend { cont -> first.invoke(null, flow, cont) }
        ?: error("无法取得本地阅读进度")
    val book = if (b4) requireNotNull(callNoArg(firstValue, "getBook")) else firstValue
    checkSelectionEditSession(viewModel, epub)
    val repository = callNoArg(viewModel, "getRepository") ?: error("宿主阅读仓库不存在")
    val page = (XposedHelpers.getObjectField(viewModel, "virtualPages") as? Map<*, *>)
        ?.values?.filterNotNull()?.firstOrNull { p ->
            runCatching {
                val range = callNoArg(p, "getRange") ?: return@runCatching false
                XposedHelpers.callMethod(range, "contains", anchor) == true
            }.getOrDefault(false)
        }
    val percent = (page?.let { callNoArg(it, "getPercentage") } as? Number)?.toFloat()
        ?: (callNoArg(book, "getProgress") as? Number)?.toFloat() ?: 0f
    val chapter = page?.let { callString(it, "getChapter") } ?: callString(book, "getChapter")
    val previousVersion = (callNoArg(book, "getUpdated") as? Number)?.toLong() ?: 0L
    val versionMethod = viewModel.javaClass.hostMethod(
        "nextReadingStateVersion", Long::class.javaPrimitiveType,
    ).apply { isAccessible = true }
    val version = (versionMethod.invoke(viewModel, previousVersion) as Number).toLong()
    val patch = ReaderPositionCopy.copy(book, raw, chapter, percent, version)
    val save = if (b4) repository.javaClass.hostAbiMethod(
        "K", book.javaClass, Boolean::class.javaPrimitiveType,
        java.lang.Long::class.java, classLoader.hostClass("kotlin.coroutines.jvm.internal.ContinuationImpl"),
    ) else repository.javaClass.hostMethod(
        "saveReadingState", book.javaClass, Boolean::class.javaPrimitiveType,
        java.lang.Long::class.java, continuation,
    )
    val saved = if (b4) HostCoroutines.invokeTimed(classLoader, save, repository, 10_000L, patch, true, previousVersion)
    else invokeSelectionHostSuspend { cont ->

        save.invoke(repository, patch, true, previousVersion, cont)
    } ?: error("宿主没有返回保存后的阅读进度")
    check(callString(saved, "getUuid") == callString(book, "getUuid") &&
        callString(saved, "getEpubcfi") == raw) {
        "阅读进度已被其他操作更新，当前锚点未确认保存，请重新选择"
    }
    checkSelectionEditSession(viewModel, epub)
    XposedHelpers.setObjectField(viewModel, "lastVisibleCfi", raw)

    val f1 = classLoader.hostClass("kotlin.jvm.functions.Function1")
    val update = Proxy.newProxyInstance(classLoader, arrayOf(f1)) { proxy, method, args ->
        when (com.reamicro.fix.core.HostSymbols.logicalMethodName(method)) {
            "invoke" -> {
                val state = requireNotNull(args?.firstOrNull())
                val currentBook = callNoArg(state, "getBook")
                if (callString(currentBook, "getUuid") != callString(saved, "getUuid")) state
                else if (b4) {
                    // a..p 的顺序由 B4 主构造器与 toString 逐字段核实，保留其余十五项。
                    HostExactRecordCopy(state.javaClass, ('a'..'p').map(Char::toString))
                        .copy(state, mapOf("a" to saved))
                }
                else {
                    val copy = state.javaClass.methods.single { it.hostNameMatches("copy") && it.parameterTypes.size == 12 }
                    val values = Array<Any?>(12) { i -> state.javaClass.hostMethod("component${i + 1}").invoke(state) }
                    values[0] = saved
                    copy.invoke(state, *values)
                }
            }
            "toString" -> "ReaMicroSelectionPositionCheckpoint"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> null
        }
    }
    XposedHelpers.callMethod(viewModel, "updateUiState", update)
}
