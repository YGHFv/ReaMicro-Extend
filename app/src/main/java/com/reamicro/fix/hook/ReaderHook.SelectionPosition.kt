package com.reamicro.fix.hook

import com.reamicro.fix.epub.editor.ReaderPositionCopy
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.reflect.Proxy

internal fun ReaderHook.persistSelectionReadingPosition(viewModel: Any, epub: Any, anchor: Any) {
    checkSelectionEditSession(viewModel, epub)
    val raw = anchor.toString()
    require(raw.isNotBlank()) { "当前阅读锚点为空" }
    val flow = XposedHelpers.getObjectField(viewModel, "bookFlow") ?: error("宿主图书状态不存在")
    val flowType = classLoader.loadClass("kotlinx.coroutines.flow.Flow")
    val continuation = classLoader.loadClass("kotlin.coroutines.Continuation")
    val first = classLoader.loadClass("kotlinx.coroutines.flow.FlowKt")
        .getMethod("firstOrNull", flowType, continuation)
    val book = invokeSelectionHostSuspend { cont -> first.invoke(null, flow, cont) }
        ?: error("无法取得本地阅读进度")
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
    val versionMethod = viewModel.javaClass.getDeclaredMethod(
        "nextReadingStateVersion", Long::class.javaPrimitiveType,
    ).apply { isAccessible = true }
    val version = (versionMethod.invoke(viewModel, previousVersion) as Number).toLong()
    val patch = ReaderPositionCopy.copy(book, raw, chapter, percent, version)
    val save = repository.javaClass.getMethod(
        "saveReadingState", book.javaClass, Boolean::class.javaPrimitiveType,
        java.lang.Long::class.java, continuation,
    )
    val saved = invokeSelectionHostSuspend { cont ->

        save.invoke(repository, patch, true, previousVersion, cont)
    } ?: error("宿主没有返回保存后的阅读进度")
    check(callString(saved, "getUuid") == callString(book, "getUuid") &&
        callString(saved, "getEpubcfi") == raw) {
        "阅读进度已被其他操作更新，当前锚点未确认保存，请重新选择"
    }
    checkSelectionEditSession(viewModel, epub)
    XposedHelpers.setObjectField(viewModel, "lastVisibleCfi", raw)

    val f1 = classLoader.loadClass("kotlin.jvm.functions.Function1")
    val update = Proxy.newProxyInstance(classLoader, arrayOf(f1)) { proxy, method, args ->
        when (method.name) {
            "invoke" -> {
                val state = requireNotNull(args?.firstOrNull())
                val currentBook = callNoArg(state, "getBook")
                if (callString(currentBook, "getUuid") != callString(saved, "getUuid")) state
                else {
                    val copy = state.javaClass.methods.single { it.name == "copy" && it.parameterTypes.size == 12 }
                    val values = Array<Any?>(12) { i -> state.javaClass.getMethod("component${i + 1}").invoke(state) }
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
