package com.reamicro.fix.hook

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.reamicro.fix.core.B2NativeUi
import com.reamicro.fix.core.HostAndroidViews
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.reflect.Proxy

/** 合并后的图片工作表保留宿主容器与隐藏动画，只重接既有操作内容。 */
internal class B4BookImageSheetUi(private val loader: ClassLoader) {
    private val views = HostAndroidViews(loader)
    private val ui = B2NativeUi(loader)

    fun render(composer: Any, actions: List<BookImageSheetActions.Action>, onAction: (BookImageSheetActions.Id) -> Unit) {
        val factory = Proxy.newProxyInstance(loader, arrayOf(loader.hostClass("kotlin.jvm.functions.Function1"))) { proxy, method, args ->
            when (HostSymbols.logicalMethodName(method)) {
                "invoke" -> {
                    val context = args?.firstOrNull() as? Context ?: error("图片工作表上下文不可用")
                    val palette = requireNotNull(EmbeddedHostUi.snapshot(context))
                    val content = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        val inset = EmbeddedHostUi.dp(context, 20f).toInt()
                        setPadding(inset, 0, inset, inset)
                    }
                    actions.forEach { action ->
                        content.addView(TextView(context).apply {
                            text = action.label
                            textSize = 16f
                            setTextColor(palette.roles.getValue("OnBackground"))
                            EmbeddedHostUi.uiTypeface(context)?.let { typeface = it }
                            val inset = EmbeddedHostUi.dp(context, 18f).toInt()
                            setPadding(inset, inset, inset, inset)
                            setOnClickListener { onAction(action.id) }
                        }, LinearLayout.LayoutParams(-1, -2))
                    }
                    ScrollView(context).apply { addView(content) }
                }
                "toString" -> "ReaMicroImageSheet"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
        views.render(factory, ui.fillWidth(ui.modifier()), composer)
    }

    fun hide(scope: Any, sheetState: Any, finished: (Boolean, Throwable?) -> Unit) {
        // 挂起隐藏必须在宿主主调度器执行；等待放在工作线程，不阻塞 Compose 主线程。
        Thread {
            val result = runCatching {
                val hide = sheetState.javaClass.hostMethod("hide", loader.hostClass("kotlin.coroutines.Continuation"))
                val context = XposedHelpers.callMethod(scope, "getCoroutineContext")
                    ?: error("图片工作表协程上下文不可用")
                HostCoroutines.runBlocking(loader, context) { continuation ->
                    hide.invoke(sheetState, continuation)
                }
                XposedHelpers.callMethod(sheetState, "isVisible") == false
            }
            finished(result.getOrDefault(false), result.exceptionOrNull())
        }.apply { name = "ReaMicroImageSheetHide" }.start()
    }
}
