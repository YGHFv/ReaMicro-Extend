package com.reamicro.fix.compat

import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method

class ReaMicroHostCompat(
    private val classLoader: ClassLoader,
) {
    private val classCache = HashMap<String, Class<*>>()
    private val methodCache = HashMap<String, Method>()
    private val fieldCache = HashMap<String, Field>()
    private val instanceMethodCache = HashMap<String, Method?>()

    fun cls(className: String): Class<*> =
        synchronized(classCache) {
            classCache.getOrPut(className) { XposedHelpers.findClass(className, classLoader) }
        }

    fun method(className: String, methodName: String, parameterCount: Int): Method {
        val cacheKey = "$className#$methodName/$parameterCount"
        return synchronized(methodCache) {
            methodCache.getOrPut(cacheKey) {
                cls(className).declaredMethods.firstOrNull {
                    it.name == methodName && it.parameterTypes.size == parameterCount
                }?.apply { isAccessible = true }
                    ?: cls(className).methods.firstOrNull {
                        it.name == methodName && it.parameterTypes.size == parameterCount
                    }?.apply { isAccessible = true }
                    ?: error("$className.$methodName/$parameterCount not found")
            }
        }
    }

    fun field(className: String, fieldName: String): Field {
        val cacheKey = "$className#$fieldName"
        return synchronized(fieldCache) {
            fieldCache.getOrPut(cacheKey) {
                cls(className).run {
                    runCatching { getDeclaredField(fieldName) }
                        .recoverCatching { getField(fieldName) }
                        .getOrThrow()
                        .apply { isAccessible = true }
                }
            }
        }
    }

    fun instanceMethod(target: Any, methodName: String, parameterCount: Int): Method? {
        val cacheKey = "${target.javaClass.name}#$methodName/$parameterCount"
        synchronized(instanceMethodCache) {
            if (instanceMethodCache.containsKey(cacheKey)) return instanceMethodCache[cacheKey]
        }
        val found = target.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == parameterCount
        }?.apply { isAccessible = true }
        synchronized(instanceMethodCache) {
            instanceMethodCache[cacheKey] = found
        }
        return found
    }

    fun fieldObjectOrNull(className: String, fieldName: String): Any? =
        runCatching {
            cls(className).run {
                runCatching { getDeclaredField(fieldName) }
                    .recoverCatching { getField(fieldName) }
                    .getOrThrow()
                    .apply { isAccessible = true }
                    .get(null)
            }
        }.getOrNull()

    fun contentDomClassCandidates(): List<String> =
        ReaderHighlight.CONTENT_DOM_CLASS_CANDIDATES

    fun contentDomOnContentMethods(className: String): List<Method> =
        cls(className).declaredMethods
            .filter { method ->
                method.name == ReaderHighlight.CONTENT_DOM_ON_CONTENT_METHOD &&
                    method.parameterTypes.size == ReaderHighlight.CONTENT_DOM_ON_CONTENT_PARAMETER_COUNT
            }
            .onEach { it.isAccessible = true }

    fun justifyTextMethods(): List<Method> {
        val justifyTextClass = cls(ReaderHighlight.JUSTIFY_TEXT_CLASS)
        val annotatedStringClass = cls(ReaderHighlight.ANNOTATED_STRING_CLASS)
        return justifyTextClass.declaredMethods
            .filter { method ->
                method.name == ReaderHighlight.JUSTIFY_TEXT_METHOD &&
                    method.parameterTypes.size == ReaderHighlight.JUSTIFY_TEXT_PARAMETER_COUNT &&
                    method.parameterTypes[0] == annotatedStringClass
            }
            .onEach { it.isAccessible = true }
    }

    fun basicTextMethods(): List<Method> {
        val basicTextClass = cls(ReaderHighlight.BASIC_TEXT_CLASS)
        val annotatedStringClass = cls(ReaderHighlight.ANNOTATED_STRING_CLASS)
        val modifierClass = cls(ReaderHighlight.MODIFIER_CLASS)
        return basicTextClass.declaredMethods
            .filter { method ->
                method.name.contains(ReaderHighlight.BASIC_TEXT_METHOD_NAME_PART) &&
                    method.parameterTypes.size >= ReaderHighlight.BASIC_TEXT_MIN_PARAMETER_COUNT &&
                    method.parameterTypes[0] == annotatedStringClass &&
                    method.parameterTypes[1] == modifierClass
            }
            .onEach { it.isAccessible = true }
    }

    object ReaderHighlight {
        const val SPAN_STYLE_CLASS = "androidx.compose.ui.text.SpanStyle"
        const val ANNOTATED_STRING_CLASS = "androidx.compose.ui.text.AnnotatedString"
        const val ANNOTATED_STRING_BUILDER_CLASS = "androidx.compose.ui.text.AnnotatedString\$Builder"
        const val ANNOTATED_RANGE_CLASS = "androidx.compose.ui.text.AnnotatedString\$Range"
        const val ANNOTATED_STRING_EXT_CLASS = "org.epub.utils.AnnotatedStringExtKt"
        const val JUSTIFY_TEXT_CLASS = "app.zhendong.reamicro.arch.components.JustifyText_androidKt"
        const val BASIC_TEXT_CLASS = "androidx.compose.foundation.text.BasicTextKt"
        const val DRAW_MODIFIER_KT_CLASS = "androidx.compose.ui.draw.DrawModifierKt"
        const val ANDROID_CANVAS_KT_CLASS = "androidx.compose.ui.graphics.AndroidCanvas_androidKt"
        const val COLOR_KT_CLASS = "androidx.compose.ui.graphics.ColorKt"
        const val MODIFIER_CLASS = "androidx.compose.ui.Modifier"
        const val FONT_PROVIDER_CLASS = "org.epub.FontProvider"
        const val FONT_FAMILY_CLASS = "androidx.compose.ui.text.font.FontFamily"
        const val FONT_FAMILY_KT_CLASS = "androidx.compose.ui.text.font.FontFamilyKt"
        const val FONT_WEIGHT_CLASS = "androidx.compose.ui.text.font.FontWeight"
        const val CONTENT_DOM_ON_CONTENT_METHOD = "onContent"
        const val CONTENT_DOM_ON_CONTENT_PARAMETER_COUNT = 2
        const val JUSTIFY_TEXT_METHOD = "JustifyText"
        const val JUSTIFY_TEXT_PARAMETER_COUNT = 8
        const val BASIC_TEXT_METHOD_NAME_PART = "BasicText"
        const val BASIC_TEXT_MIN_PARAMETER_COUNT = 12
        val CONTENT_DOM_CLASS_CANDIDATES = listOf(
            "org.epub.html.node.ContentDom",
            "app.zhendong.epub.node.ContentDom",
        )
    }
}
