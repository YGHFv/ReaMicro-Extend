package com.reamicro.fix.core

import android.app.Activity
import android.app.Dialog
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier
import java.util.UUID

/** Every member is restored by exact descriptor through the shared KavaRef resolver. */
internal object HostDialogBindings {
    const val KEY = "ui.dialog"
    @Volatile var current: Contract? = null
        private set
    @Volatile var initializationAttempted = false
        private set
    @Volatile private var descriptors: Map<String, String>? = null

    fun boundDescriptors() = descriptors?.toMap()

    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true
        current = null
        descriptors = null
        runCatching {
            HostQuerySession.use(loader, apk, dataDir) { session ->
                val values = query(session, false, requireNotNull(HostUiBindings.boundDescriptors()),
                    requireNotNull(HostCompositionBindings.boundDescriptors()))
                current = restore(values, loader)
                descriptors = values
            }
        }.onFailure { HookRuntime.logError("ReaMicro native dialog query unavailable", it) }
    }

    fun rescan(session: HostQuerySession.Session, ui: Map<String, String>, composition: Map<String, String>) =
        query(session, true, ui, composition)

    private fun query(
        session: HostQuerySession.Session,
        force: Boolean,
        ui: Map<String, String>,
        composition: Map<String, String>,
    ) = session.query(KEY, force, validate = { restore(it, session.loader) },
        find = { HostDialogQueries.query(it, session.apk, ui, composition) }).values.toMap()

    class Contract internal constructor(
        private val members: HostRuntimeBindings.Members,
        private val values: Map<String, String>,
    ) {
        fun type(role: String) = members.type(role)
        fun method(role: String) = members.method(role)
        fun constructor(role: String) = members.constructor(role)
        fun field(role: String) = members.field(role)
        fun number(role: String) = values.getValue("value:$role").toInt()
        fun renderCallers() = values.keys.count { it.startsWith("method:renderCaller.") }
            .let { count -> (0 until count).map { method("renderCaller.$it") } }
        fun themeCallers() = values.keys.count { it.startsWith("method:themeCaller.") }
            .let { count -> (0 until count).map { method("themeCaller.$it") } }
        fun rememberContext(composer: Any): Any =
            requireNotNull(method("rememberContext").invoke(null, composer))
        fun onCommit(callback: Any, composer: Any) { method("sideEffect").invoke(null, callback, composer) }
        fun renderThemed(content: Any, composer: Any) {
            check(type("lambda").isInstance(content))
            method("applicationTheme").invoke(null, content, composer, 0)
        }
        fun scopedContent(key: Int, render: (Any) -> Unit): Any {
            val ui = requireNotNull(HostUiBindings.current)
            val callback = ui.callback(type("function3")) { args ->
                val composer = args?.getOrNull(1)?.takeIf(type("composer")::isInstance) ?: return@callback
                render(composer)
            }
            return constructor("lambda").newInstance(key, true, callback)
        }
        fun render(title: String, dismiss: Any, content: Any, composer: Any) {
            check(type("lambda").isInstance(content) &&
                type("function3").isInstance(field("contentCallback").get(content))) {
                "Native dialog content must accept scope, composer and flags"
            }
            // Let the host construct its own padding, properties, surface and actual Dialog.
            method("wrapper").invoke(null, title, dismiss, null, content, composer, 0, number("paddingMask"))
        }
        fun sentenceContent(dismiss: Any): Any {
            val ui = requireNotNull(HostUiBindings.current)
            val state = requireNotNull(ui.call("state", ""))
            check(type("sentenceState").isInstance(state))
            return constructor("sentenceContent").newInstance(dismiss, state, ui.callback(ui.type("function1")) {})
        }
        fun renderSentence(dismiss: Any, content: Any, composer: Any) {
            check(type("lambda").isInstance(content))
            val properties = constructor("properties").newInstance(number("sentenceProperties"))
            method("nativeDialog").invoke(null, dismiss, properties, content, composer,
                number("sentenceFlags"), number("sentenceMask"))
        }
        fun themedContentArguments(template: Array<out Any?>, content: Any): Array<Any?> {
            check(template.size == method("theme").parameterCount && type("lambda").isInstance(content))
            val slot = number("themeContent")
            val changed = number("themeComposer") + 1
            return Array<Any?>(template.size) { template[it] }.also {
                it[slot] = content
                // The original content's static/unchanged flags describe a different callback.
                it[changed] = (it[changed] as Int) and (14 shl (slot * 3)).inv()
            }
        }
        fun sentenceActionArguments(template: Array<out Any?>, onClick: Any, content: Any): Array<Any?> {
            val button = method("sentenceButton")
            check(template.size == button.parameterCount && type("function0").isInstance(onClick))
            val slot = number("sentenceButtonContent")
            check(button.parameterTypes[slot].isInstance(content))
            return Array<Any?>(template.size) { template[it] }.also {
                it[0] = onClick
                // The native confirmation template is disabled while its unused sentence is empty.
                // Module actions already express their own eligibility in their callbacks.
                it[2] = true
                it[slot] = content
                val changed = number("sentenceButtonComposer") + 1
                // R8 may remove parameters without renumbering Compose change bits.
                // Let the host recompute changes for the replacement callbacks.
                it[changed] = 0
                it[changed + 1] = (it[changed + 1] as Int) and (1 shl 2).inv()
            }
        }
    }

    internal fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val keys = listOf("activity", "root", "composer", "function0", "function2", "function3", "lambda", "padding",
            "properties", "window", "parentContext", "rememberContext", "sentenceState").map { "class:$it" } +
            listOf("root", "lambda", "padding", "properties", "window", "sentenceContent").map { "constructor:$it" } +
            listOf("windowDismiss", "contentCallback").map { "field:$it" } +
            listOf("prompt", "wrapper", "contentInvoke", "nativeDialog", "rememberContext", "sideEffect", "setParent", "setContent",
                "dispose", "dpInt", "windowTouch", "sentenceBody", "sentenceField", "sentenceButton", "theme",
                "applicationTheme").map { "method:$it" } +
            listOf("paddingMask", "paddingSize", "promptFlags", "promptMask", "propertiesArgument", "sentenceProperties",
                "sentenceFlags", "sentenceMask", "sentenceFieldModifier", "sentenceFieldComposer",
                "sentenceButtonContent", "sentenceButtonComposer", "themeContent", "themeComposer").map { "value:$it" }
        val callerKeys = values.keys.filter { it.startsWith("method:renderCaller.") }.toSet()
        val themeCallerKeys = values.keys.filter { it.startsWith("method:themeCaller.") }.toSet()
        check(callerKeys.size in 1..32 &&
            callerKeys == callerKeys.indices.map { "method:renderCaller.$it" }.toSet() &&
            themeCallerKeys.size in 1..64 &&
            themeCallerKeys == themeCallerKeys.indices.map { "method:themeCaller.$it" }.toSet() &&
            values.keys == keys.toSet() + callerKeys + themeCallerKeys) { "Incomplete native dialog contract" }
        val s = Contract(HostRuntimeBindings.Members(values, loader), values)
        val root = s.type("root")
        val activity = s.type("activity")
        val composer = s.type("composer")
        val function0 = s.type("function0")
        val function2 = s.type("function2")
        val function3 = s.type("function3")
        val lambda = s.type("lambda")
        check(Activity::class.java.isAssignableFrom(activity) && ViewGroup::class.java.isAssignableFrom(root))
        check(function0.isInterface && function2.isInterface && function3.isInterface &&
            function2 != function3 && function2.isAssignableFrom(lambda) && function3.isAssignableFrom(lambda))
        check(s.constructor("root").let { it.declaringClass == root && it.parameterTypes.toList() == listOf(activity) })
        check(s.constructor("lambda").let { it.declaringClass == lambda &&
            it.parameterTypes.toList() == listOf(Integer.TYPE, java.lang.Boolean.TYPE, Any::class.java) })
        check(s.field("contentCallback").let { it.declaringClass == lambda && it.type == Any::class.java &&
            !Modifier.isStatic(it.modifiers) })
        check(s.method("contentInvoke").let { it.declaringClass == lambda && !Modifier.isStatic(it.modifiers) &&
            it.returnType == Any::class.java && it.parameterTypes.toList() == List(3) { Any::class.java } })
        check(s.constructor("padding").let { s.type("padding").isAssignableFrom(it.declaringClass) &&
            it.parameterTypes.toList() == List(4) { java.lang.Float.TYPE } })
        check(s.constructor("properties").let { it.declaringClass == s.type("properties") &&
            it.parameterTypes.toList() == listOf(Integer.TYPE) })
        val window = s.type("window")
        check(Dialog::class.java.isAssignableFrom(window))
        check(s.constructor("window").let { it.declaringClass == window && it.parameterCount == 6 &&
            it.parameterTypes.take(3) == listOf(function0, s.type("properties"), View::class.java) &&
            it.parameterTypes[3].isEnum && it.parameterTypes[5] == UUID::class.java })
        check(s.field("windowDismiss").let { it.declaringClass == window && it.type == function0 &&
            !Modifier.isStatic(it.modifiers) })
        check(s.method("windowTouch").let { it.declaringClass == window && !Modifier.isStatic(it.modifiers) &&
            it.returnType == java.lang.Boolean.TYPE && it.parameterTypes.toList() == listOf(MotionEvent::class.java) })
        fun static(role: String, result: Class<*>, vararg parameters: Class<*>) {
            check(s.method(role).let { Modifier.isStatic(it.modifiers) &&
                it.returnType == result && it.parameterTypes.toList() == parameters.toList() })
        }
        static("prompt", Void.TYPE, String::class.java, function0, composer, Integer.TYPE)
        static("wrapper", Void.TYPE, String::class.java, function0, s.type("padding"), lambda,
            composer, Integer.TYPE, Integer.TYPE)
        static("nativeDialog", Void.TYPE, function0, s.type("properties"), lambda, composer, Integer.TYPE, Integer.TYPE)
        check(s.renderCallers().let { callers -> callers.any { it == s.method("wrapper") } &&
            callers.all { !Modifier.isAbstract(it.modifiers) } && callers.distinct().size == callers.size })
        static("rememberContext", s.type("rememberContext"), composer)
        static("sideEffect", Void.TYPE, function0, composer)
        static("dpInt", java.lang.Float.TYPE, Integer.TYPE)
        check(s.type("parentContext").isAssignableFrom(s.type("rememberContext")))
        check(s.method("setParent").let { !Modifier.isStatic(it.modifiers) && it.declaringClass.isAssignableFrom(root) &&
            it.returnType == Void.TYPE && it.parameterTypes.toList() == listOf(s.type("parentContext")) })
        check(s.method("setContent").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == root &&
            it.returnType == Void.TYPE && it.parameterTypes.toList() == listOf(function2) })
        check(s.method("dispose").let { !Modifier.isStatic(it.modifiers) && it.declaringClass.isAssignableFrom(root) &&
            it.returnType == Void.TYPE && it.parameterCount == 0 })
        check(s.number("paddingMask").let { it > 0 && it.countOneBits() == 1 })
        check(s.number("paddingSize") in 0..128 && s.number("promptFlags") >= 0 && s.number("promptMask") >= 0)
        check(s.number("propertiesArgument") in 0..7)
        check(s.constructor("sentenceContent").let {
            function2.isAssignableFrom(it.declaringClass) &&
                it.parameterTypes.toList() == listOf(function0, s.type("sentenceState"), loader.hostClass("kotlin.jvm.functions.Function1"))
        })
        val ui = requireNotNull(HostUiBindings.current)
        val applicationTheme = s.method("applicationTheme")
        check(Modifier.isStatic(applicationTheme.modifiers) && applicationTheme.returnType == Void.TYPE &&
            applicationTheme.declaringClass == ui.method("backgroundAuto").declaringClass &&
            applicationTheme.parameterCount == 3 &&
            applicationTheme.parameterTypes[0] in listOf(lambda, function2) &&
            applicationTheme.parameterTypes.drop(1) == listOf(composer, Integer.TYPE))
        val theme = s.method("theme")
        check(Modifier.isStatic(theme.modifiers) && theme.returnType == Void.TYPE &&
            theme.parameterTypes.toList() == listOf(ui.method("colorScheme").returnType,
                theme.parameterTypes[1], ui.method("typography").returnType, function2, composer, Integer.TYPE, Integer.TYPE) &&
            !theme.parameterTypes[1].isPrimitive && s.number("themeContent") == 3 && s.number("themeComposer") == 4)
        check(s.themeCallers().let { callers -> callers.isNotEmpty() &&
            callers.all { !Modifier.isAbstract(it.modifiers) } && callers.distinct().size == callers.size &&
            applicationTheme in callers })
        check(s.type("sentenceState").let {
            it.isInterface && it == ui.method("stateSet").declaringClass &&
                it.isAssignableFrom(ui.method("state").returnType)
        })
        check(s.method("sentenceBody").let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == s.constructor("sentenceContent").declaringClass &&
                it.returnType == Any::class.java && it.parameterTypes.toList() == List(2) { Any::class.java }
        })
        for (role in listOf("sentenceField", "sentenceButton")) {
            check(s.method(role).let { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.count { type -> type == composer } == 1 })
        }
        check(s.method("sentenceField").parameterTypes[s.number("sentenceFieldModifier")] ==
            requireNotNull(HostUiBindings.current).modifierClass)
        check(s.method("sentenceField").parameterTypes[s.number("sentenceFieldComposer")] == composer)
        check(s.method("sentenceButton").let {
            it.parameterTypes.take(3) == listOf(function0, ui.modifierClass, java.lang.Boolean.TYPE) &&
                it.parameterTypes[s.number("sentenceButtonContent")] == function3 &&
                it.parameterTypes[s.number("sentenceButtonComposer")] == composer &&
                it.parameterTypes.drop(s.number("sentenceButtonComposer") + 1) == listOf(Integer.TYPE, Integer.TYPE)
        })
        check(s.number("sentenceProperties") in 0..7 && s.number("sentenceFlags") >= 0 && s.number("sentenceMask") >= 0)
        return s
    }
}
