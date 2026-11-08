package com.reamicro.fix.hook

import com.reamicro.fix.core.B2NativeUi
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostMethodsNamed
import com.reamicro.fix.core.hostReleaseField
import com.reamicro.fix.core.hostReleaseMethod
import com.reamicro.fix.core.hostStaticMethod
import java.lang.reflect.Proxy

/** Both book actions use the exact native book-title Edit vector in the native toolbar. */
internal class HostBookEditAction(private val loader: ClassLoader) {
    private val ui = B2NativeUi(loader)
    private val vector by lazy {
        requireNotNull(loader.hostStaticMethod("androidx.compose.material.icons.outlined.EditKt", "getEdit").invoke(null))
    }
    private val icon by lazy {
        loader.hostClass("androidx.compose.material3.IconKt").hostMethodsNamed("Icon-ww6aTOc")
            .single { it.parameterCount == 7 &&
                it.parameterTypes[0] == loader.hostClass("androidx.compose.ui.graphics.vector.ImageVector") }
    }
    private val size by lazy {
        loader.hostClass("ReaMicroHost.Size").hostAbiMethod("o",
            loader.hostClass("androidx.compose.ui.Modifier"), Float::class.javaPrimitiveType)
    }
    private val circle by lazy {
        loader.hostClass("ReaMicroShelf233.Circle").hostReleaseField("a").get(null)
    }
    private val clip by lazy {
        loader.hostClass("ReaMicroShelf233.Clip").hostReleaseMethod("a",
            loader.hostClass("androidx.compose.ui.Modifier"),
            loader.hostClass("androidx.compose.ui.graphics.Shape"))
    }

    fun render(label: String, composer: Any, onClick: () -> Unit) {
        EmbeddedHostUi.captureTypography(loader, composer)
        BookImageDialogStyle.capture(loader, composer)
        val scheme = ui.theme(composer, "getColorScheme")
        val color = loader.hostClass("app.zhendong.reamicro.arch.theme.ThemeKt")
            .hostMethod("getOnBackgroundVariant", scheme.javaClass).invoke(null, scheme) as Long
        val click = Proxy.newProxyInstance(loader,
            arrayOf(loader.hostClass("kotlin.jvm.functions.Function0"))) { self, method, args ->
            when (HostSymbols.logicalMethodName(method)) {
                "invoke" -> { onClick(); loader.hostClass("kotlin.Unit").hostField("INSTANCE").get(null) }
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.firstOrNull()
                "toString" -> "ReaMicroBookEditAction"
                else -> null
            }
        }
        val clipped = requireNotNull(clip.invoke(null, ui.modifier(), circle))
        val clickable = ui.clickable(clipped, click)
        val sized = requireNotNull(size.invoke(null, clickable, 44f))
        val modifier = ui.padding(sized, 12f, 12f, 12f, 12f)
        icon.invoke(null, vector, label, modifier, color, composer, 0, 0)
    }
}
