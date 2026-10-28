package com.reamicro.fix.core

import com.reamicro.fix.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method

class HostReflect(
    private val classLoader: ClassLoader,
) {
    private val classCache = HashMap<String, Class<*>>()
    private val methodCache = HashMap<String, Method>()
    private val fieldCache = HashMap<String, Field>()
    private val instanceMethodCache = HashMap<String, Method?>()
    private val accessorCache = HashMap<Pair<Class<*>, String>, HostAccessor?>()

    fun accessor(target: Any, getter: String): Any? {
        val key = target.javaClass to getter
        val bound = synchronized(accessorCache) {
            if (!accessorCache.containsKey(key)) {
                accessorCache[key] = HostAccessor.bind(target.javaClass, getter)
            }
            accessorCache[key]
        }
        return bound?.get(target)
    }

    fun cls(className: String): Class<*> =
        synchronized(classCache) {
            classCache.getOrPut(className) { XposedHelpers.findClass(className, classLoader) }
        }

    fun method(className: String, methodName: String, parameterCount: Int): Method {
        val resolvedName = HostSymbols.resolveMethodName(className, methodName)
        val cacheKey = "$className#$methodName/$parameterCount"
        return synchronized(methodCache) {
            methodCache.getOrPut(cacheKey) {
                cls(className).declaredMethods.firstOrNull {
                    it.name == resolvedName && it.parameterTypes.size == parameterCount
                }?.apply { isAccessible = true }
                    ?: cls(className).methods.firstOrNull {
                        it.name == resolvedName && it.parameterTypes.size == parameterCount
                    }?.apply { isAccessible = true }
                    ?: error("$className.$methodName/$parameterCount not found")
            }
        }
    }

    fun field(className: String, fieldName: String): Field {
        // 调用方可能传入运行时实际类名（例如 ContentDom 的 hv1），先反查逻辑名再解析字段。
        val logicalClass = HostSymbols.logicalClass(className)
        val resolvedName = HostSymbols.resolveFieldName(logicalClass, fieldName)
        val cacheKey = "$className#$fieldName"
        return synchronized(fieldCache) {
            fieldCache.getOrPut(cacheKey) {
                cls(className).run {
                    runCatching { getDeclaredField(resolvedName) }
                        .recoverCatching { getField(resolvedName) }
                        .getOrThrow()
                        .apply { isAccessible = true }
                }
            }
        }
    }

    fun instanceMethod(target: Any, methodName: String, parameterCount: Int): Method? {
        val resolvedName = HostSymbols.resolveMethodName(target.javaClass, methodName)
        val cacheKey = "${target.javaClass.name}#$methodName/$parameterCount"
        synchronized(instanceMethodCache) {
            if (instanceMethodCache.containsKey(cacheKey)) return instanceMethodCache[cacheKey]
        }
        val found = target.javaClass.methods.firstOrNull {
            it.name == resolvedName && it.parameterTypes.size == parameterCount
        }?.apply { isAccessible = true }
        synchronized(instanceMethodCache) {
            instanceMethodCache[cacheKey] = found
        }
        return found
    }

    fun fieldObjectOrNull(className: String, fieldName: String): Any? =
        runCatching {
            val logicalClass = HostSymbols.logicalClass(className)
            val resolvedName = HostSymbols.resolveFieldName(logicalClass, fieldName)
            cls(className).run {
                runCatching { getDeclaredField(resolvedName) }
                    .recoverCatching { getField(resolvedName) }
                    .getOrThrow()
                    .apply { isAccessible = true }
                    .get(null)
            }
        }.getOrNull()

    fun contentDomClassCandidates(): List<String> =
        ReaderHighlight.CONTENT_DOM_CLASS_CANDIDATES.map(HostSymbols::resolveClass)

    fun contentDomOnContentMethods(className: String): List<Method> {
        val type = cls(className)
        val onContentNames = linkedSetOf(
            HostSymbols.resolveMethodName(type, ReaderHighlight.CONTENT_DOM_ON_CONTENT_METHOD),
            HostSymbols.resolveMethodName(
                ReaderHighlight.CONTENT_DOM_CLASS,
                ReaderHighlight.CONTENT_DOM_ON_CONTENT_METHOD,
            ),
            ReaderHighlight.CONTENT_DOM_ON_CONTENT_METHOD,
        )
        val onContent = type.declaredMethods.filter { method ->
            method.name in onContentNames &&
                method.parameterTypes.size == ReaderHighlight.CONTENT_DOM_ON_CONTENT_PARAMETER_COUNT
        }
        if (onContent.isNotEmpty()) {
            return onContent.onEach { it.isAccessible = true }
        }
        // R8 可能把 onContent 内联后删除；B2 实际布局入口是 onMeasure。
        val onMeasureNames = linkedSetOf(
            HostSymbols.resolveMethodName(type, ReaderHighlight.CONTENT_DOM_ON_MEASURE_METHOD),
            HostSymbols.resolveMethodName(
                ReaderHighlight.CONTENT_DOM_CLASS,
                ReaderHighlight.CONTENT_DOM_ON_MEASURE_METHOD,
            ),
            ReaderHighlight.CONTENT_DOM_ON_MEASURE_METHOD,
        )
        return type.declaredMethods
            .filter { method ->
                method.name in onMeasureNames &&
                    method.parameterTypes.size == ReaderHighlight.CONTENT_DOM_ON_MEASURE_PARAMETER_COUNT
            }
            .onEach { it.isAccessible = true }
    }

    fun justifyTextMethods(): List<Method> {
        val justifyTextClass = cls(ReaderHighlight.JUSTIFY_TEXT_CLASS)
        val annotatedStringClass = cls(ReaderHighlight.ANNOTATED_STRING_CLASS)
        return justifyTextClass.declaredMethods
            .filter { method ->
                method.name == HostSymbols.resolveMethodName(
                    ReaderHighlight.JUSTIFY_TEXT_CLASS,
                    ReaderHighlight.JUSTIFY_TEXT_METHOD,
                ) &&
                    method.parameterTypes.size == (
                        if (HostSymbols.abiProfileId == "2.3.3-b4") 7 else ReaderHighlight.JUSTIFY_TEXT_PARAMETER_COUNT
                    ) &&
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
                method.hostNameStartsWith(ReaderHighlight.BASIC_TEXT_METHOD_NAME_PART) &&
                    method.parameterTypes.size >= ReaderHighlight.BASIC_TEXT_MIN_PARAMETER_COUNT &&
                    method.parameterTypes[0] == annotatedStringClass &&
                    method.parameterTypes[1] == modifierClass
            }
            .onEach { it.isAccessible = true }
    }

    object ReaderHighlight {
        const val CONTENT_DOM_CLASS = HostClasses.Epub.CONTENT_DOM
        const val SPAN_STYLE_CLASS = HostClasses.Compose.SPAN_STYLE
        const val ANNOTATED_STRING_CLASS = HostClasses.Compose.ANNOTATED_STRING
        const val ANNOTATED_STRING_BUILDER_CLASS = "androidx.compose.ui.text.AnnotatedString\$Builder"
        const val ANNOTATED_RANGE_CLASS = "androidx.compose.ui.text.AnnotatedString\$Range"
        const val ANNOTATED_STRING_EXT_CLASS = HostClasses.Epub.ANNOTATED_STRING_EXT
        const val JUSTIFY_TEXT_CLASS = HostClasses.Host.JUSTIFY_TEXT
        const val BASIC_TEXT_CLASS = HostClasses.Compose.BASIC_TEXT
        const val DRAW_MODIFIER_KT_CLASS = HostClasses.Compose.DRAW_MODIFIER_KT
        const val ANDROID_CANVAS_KT_CLASS = HostClasses.Compose.ANDROID_CANVAS_KT
        const val COLOR_KT_CLASS = HostClasses.Compose.COLOR_KT
        const val MODIFIER_CLASS = HostClasses.Compose.MODIFIER
        const val FONT_PROVIDER_CLASS = HostClasses.Epub.FONT_PROVIDER
        const val FONT_FAMILY_CLASS = HostClasses.Compose.FONT_FAMILY
        const val FONT_FAMILY_KT_CLASS = HostClasses.Compose.FONT_FAMILY_KT
        const val FONT_WEIGHT_CLASS = HostClasses.Compose.FONT_WEIGHT
        const val CONTENT_DOM_ON_CONTENT_METHOD = "onContent"
        const val CONTENT_DOM_ON_CONTENT_PARAMETER_COUNT = 2
        const val CONTENT_DOM_ON_MEASURE_METHOD = "onMeasure"
        const val CONTENT_DOM_ON_MEASURE_PARAMETER_COUNT = 2
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
