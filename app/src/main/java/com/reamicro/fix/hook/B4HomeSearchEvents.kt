package com.reamicro.fix.hook

import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.core.hostField
import com.reamicro.fix.hook.webdav.BOOKSHELF_REPOSITORY_CLASS
import android.os.Handler
import android.os.Looper
import com.reamicro.fix.hook.webdav.CLOUD_BOOK_CLASS
import com.reamicro.fix.hook.webdav.HOME_VIEW_MODEL_CLASS
import com.reamicro.fix.hook.webdav.LOG_PREFIX
import com.reamicro.fix.hook.webdav.callInt
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

/** B4 搜索已内联到 onIntent；不能把 liveBookByGroup(String) 当成搜索入口。 */
internal class B4HomeSearchEvents(private val drive: WebDavDriveHook) {
    private val viewModel = drive.cls(HOME_VIEW_MODEL_CLASS)
    private val event = drive.cls("ReaMicroHost.HomeEvent")
    private val search = drive.cls("ReaMicroHost.HomeSearchEvent")
    private val close = drive.cls("ReaMicroHost.HomeSearchClose")
    private val query = search.hostAbiField("a").apply { isAccessible = true }
    val method = viewModel.hostAbiMethod("p", event).apply { isAccessible = true }

    init {
        check(HostSymbols.abiProfileId == "2.3.3-b4")
        check(query.type == String::class.java && method.returnType == Void.TYPE)
    }

    fun query(intent: Any?): String? = when {
        search.isInstance(intent) -> query.get(intent) as String
        close.isInstance(intent) -> ""
        else -> null
    }

    fun deoptimize() {
        XposedBridge.deoptimize(method)
        viewModel.hostAbiMethod("i", drive.cls("app.zhendong.reamicro.arch.UiIntent"))
            .let(XposedBridge::deoptimize)
    }

    fun installTapGuard() {
        val tap = drive.cls("ReaMicroHost.HomeCloudTap")
        val bookField = tap.hostAbiField("a").apply { isAccessible = true }
        check(event.isAssignableFrom(tap) && bookField.type == drive.cls(CLOUD_BOOK_CLASS))
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val intent = param.args[0] ?: return
                if (!tap.isInstance(intent)) return
                val book = bookField.get(intent)
                XposedBridge.logAlways(
                    "$LOG_PREFIX B4 home cloud event receiver=${param.thisObject.javaClass.name} " +
                        "event=${intent.javaClass.name} path=${cloudPathOf(book)}",
                )
                if (drive.tryHandleRunningCloudDownloadTap(book, book.callInt("getType"))) {
                    param.result = null
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val intent = param.args[0] ?: return
                if (tap.isInstance(intent) && param.throwable != null) {
                    XposedBridge.logAlways(
                        "$LOG_PREFIX B4 home cloud event failed: ${param.throwable.stackTraceToString()}",
                    )
                }
            }
        })
        deoptimize()
    }

    companion object {
        fun open(drive: WebDavDriveHook, receiver: Any, book: Any, scope: Any) {
            val url = book.javaClass.hostField("url").get(book) as String
            val path = cloudPathOf(book)
            // 不在主线程阻塞 Room；命中后复用 pv(byte=11) 的原生阅读路由。
            Thread({
                runCatching {
                    val repository = receiver.javaClass.hostField("repository").get(receiver)
                    val query = drive.cls(BOOKSHELF_REPOSITORY_CLASS).hostAbiMethod(
                        "j", String::class.java, drive.cls("ReaMicroHost.SuspendContinuation"),
                    )
                    val local = HostCoroutines.invokeTimed(drive.classLoader, query, repository, 15_000L, url)
                    XposedBridge.logAlways(
                        "$LOG_PREFIX B4 home search lookup receiver=${receiver.javaClass.name} url=$url " +
                            "path=$path local=${local?.javaClass?.name}",
                    )
                    Handler(Looper.getMainLooper()).post {
                        runCatching {
                            if (local == null) {
                                tap(drive, receiver, book)
                            } else {
                                val id = local.javaClass.hostField("id").getLong(local)
                                check(id > 0)
                                val route = drive.cls("ReaMicroHost.LocalReaderRoute")
                                    .getDeclaredConstructor(Long::class.javaPrimitiveType).newInstance(id)
                                HostNavigation.navigate(drive.classLoader, scope, route)
                                XposedBridge.logAlways(
                                    "$LOG_PREFIX B4 home search reader opened id=$id url=$url path=$path",
                                )
                            }
                        }.onFailure { reportOpenFailure(drive, receiver, url, path, it) }
                    }
                }.onFailure { reportOpenFailure(drive, receiver, url, path, it) }
            }, "ReaMicroB4SearchOpen").start()
        }

        private fun reportOpenFailure(drive: WebDavDriveHook, receiver: Any, url: String, path: String, error: Throwable) {
            XposedBridge.logAlways(
                "$LOG_PREFIX B4 home search open failed receiver=${receiver.javaClass.name} " +
                    "url=$url path=$path error=${error.stackTraceToString()}",
            )
            drive.showToast("搜索结果无法打开，请查看模块日志")
        }

        fun tap(drive: WebDavDriveHook, receiver: Any, book: Any) {
            val receiverName = receiver.javaClass.name
            val bookName = book.javaClass.name
            val path = cloudPathOf(book)
            var eventName = "<unresolved>"
            var constructorName = "<unresolved>"
            var dispatchName = "<unresolved>"
            XposedBridge.logAlways(
                "$LOG_PREFIX B4 home search tap received receiver=$receiverName book=$bookName path=$path",
            )
            try {
                check(HostSymbols.abiProfileId == "2.3.3-b4")
                val receiverClass = drive.cls("app.zhendong.reamicro.arch.IntentReceiver")
                val intentClass = drive.cls("app.zhendong.reamicro.arch.UiIntent")
                val bookClass = drive.cls(CLOUD_BOOK_CLASS)
                val eventClass = drive.cls("ReaMicroHost.HomeCloudTap")
                eventName = eventClass.name
                check(receiverClass.isInstance(receiver)) { "搜索接收者类型不符：$receiverName" }
                check(drive.cls(HOME_VIEW_MODEL_CLASS).isInstance(receiver)) { "搜索接收者不是主页模型：$receiverName" }
                check(bookClass.isInstance(book)) { "搜索书籍类型不符：$bookName" }
                check(intentClass.isAssignableFrom(eventClass)) { "搜索点击事件未实现 UiIntent：$eventName" }
                check(drive.cls("ReaMicroHost.HomeEvent").isAssignableFrom(eventClass)) {
                    "搜索点击事件未实现 HomeEvent：$eventName"
                }
                val constructor = eventClass.getDeclaredConstructor(bookClass).apply { isAccessible = true }
                constructorName = constructor.toGenericString()
                val event = constructor.newInstance(book)
                // 双源确认 hi0(byte=1) -> uc4(rd1) -> v61(byte=24)，按 URL 查本地书后打开阅读。
                val dispatch = receiverClass.hostAbiMethod("h", intentClass).apply { isAccessible = true }
                dispatchName = dispatch.toGenericString()
                check(dispatch.returnType == Void.TYPE) { "搜索点击分发返回类型不符：$dispatchName" }
                XposedBridge.logAlways(
                    "$LOG_PREFIX B4 home search tap dispatch receiver=$receiverName event=$eventName " +
                        "constructor=$constructorName method=$dispatchName path=$path",
                )
                dispatch.invoke(receiver, event)
                XposedBridge.logAlways(
                    "$LOG_PREFIX B4 home search tap dispatched receiver=$receiverName event=$eventName path=$path",
                )
            } catch (error: Throwable) {
                XposedBridge.logAlways(
                    "$LOG_PREFIX B4 home search tap failed receiver=$receiverName book=$bookName " +
                        "event=$eventName constructor=$constructorName method=$dispatchName path=$path " +
                        "error=${error.stackTraceToString()}",
                )
                drive.showToast("搜索结果暂时无法打开，请查看模块日志")
                throw error
            }
        }
    }
}
