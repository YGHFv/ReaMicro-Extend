package com.reamicro.fix.hook
import com.reamicro.fix.core.hostAbiMethodByArity

import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

/** B4 菜单顶栏的深浅色按钮仍存在；仅绑定其重组闭包，不改目录原生「搜本书」。 */
internal class B4ReaderSearchEntryBridge(private val reader: ReaderHook) {
    fun install() {
        check(HostSymbols.abiProfileId == "2.3.3-b4")
        val loader = reader.classLoader
        val content = loader.hostClass("ReaMicroHost.ReaderMenuActions")
        val render = content.hostAbiMethod("y", Any::class.java, Any::class.java, Any::class.java, Any::class.java)
        XposedBridge.hookMethod(render, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val closure = param.thisObject ?: return
                if (content.hostAbiField("a").getByte(closure) != 3.toByte()) return
                val receiver = content.hostAbiField("g").get(closure) ?: return
                if (receiver !== reader.currentViewModelRef?.get()) return
                val book = content.hostAbiField("b").get(closure) ?: return
                reader.beginReaderSearchBar(receiver, book)
                param.setObjectExtra("reamicro.menu.search.scope", true)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.getObjectExtra("reamicro.menu.search.scope") == true) reader.endReaderSearchBar()
            }
        })
        val clickable = loader.hostClass("androidx.compose.foundation.ClickableKt")
            .hostAbiMethodByArity("g", 6)
        XposedBridge.hookMethod(clickable, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!reader.readerBottomBarComposeScope.active) return
                val original = param.args[4] ?: return
                val replacement = reader.bindReaderThemeClick(original)
                if (replacement !== original) {
                    XposedBridge.logAlways("ReaMicro B4 reader menu theme callback bound to module search")
                    param.args[4] = replacement
                }
            }
        })
        XposedBridge.deoptimize(render)
        XposedBridge.deoptimize(content.hostAbiMethod(
            "a", Any::class.java, Any::class.java, Any::class.java, Any::class.java,
        ))
        XposedBridge.logAlways("ReaMicro B4 module search replaces reader menu theme control; native search untouched")
    }
}
