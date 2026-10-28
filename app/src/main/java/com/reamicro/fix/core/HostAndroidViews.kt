package com.reamicro.fix.core

import java.lang.reflect.Proxy

/** B4 保留可复用 View 的八参入口；旧六参入口的默认槽不能直接移位复用。 */
class HostAndroidViews(private val loader: ClassLoader) {
    private val type by lazy { loader.hostClass("androidx.compose.ui.viewinterop.AndroidView_androidKt") }
    private val noOp by lazy {
        Proxy.newProxyInstance(loader, arrayOf(loader.hostClass("kotlin.jvm.functions.Function1"))) { proxy, method, args ->
            when (HostSymbols.logicalMethodName(method)) {
                "invoke" -> loader.hostClass("kotlin.Unit").hostField("INSTANCE").get(null)
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "ReaMicroAndroidViewNoOp"
                else -> null
            }
        }
    }
    private val render by lazy {
        if (HostSymbols.abiProfileId == "2.3.3-b4") {
            type.getDeclaredMethod("b", loader.hostClass("kotlin.jvm.functions.Function1"),
                loader.hostClass("androidx.compose.ui.Modifier"),
                loader.hostClass("kotlin.jvm.functions.Function1"),
                loader.hostClass("kotlin.jvm.functions.Function1"),
                loader.hostClass("kotlin.jvm.functions.Function1"),
                loader.hostClass("androidx.compose.runtime.Composer"),
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        } else type.hostMethodsNamed("AndroidView").single { it.parameterCount == 6 }
    }

    fun render(factory: Any, modifier: Any, composer: Any, update: Any? = null) {
        if (render.parameterCount == 8) {
            render.invoke(null, factory, modifier, null, noOp, update ?: noOp, composer, 0, 4)
        } else {
            render.invoke(null, factory, modifier, update, composer, 0, if (update == null) 4 else 0)
        }
    }
}
