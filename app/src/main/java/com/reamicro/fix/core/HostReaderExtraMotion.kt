package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Additional motion symbols are optional and resolved from the reader's call graph. */
internal object HostReaderExtraMotion {
    internal fun query(bridge: DexKitBridge): Map<String, String> {
        val result = linkedMapOf<String, String>()
        fun feature(label: String, query: () -> Map<String, String>) {
            runCatching(query).onSuccess { result.putAll(it) }.onFailure {
                HookRuntime.logError("ReaMicro $label motion unavailable", it)
            }
        }
        feature("scroll") { scroll(bridge) }
        feature("curl") { curl(bridge) }
        return result
    }

    private fun List<MethodData>.one(label: String): MethodData = distinctBy { it.descriptor }.let {
        it.singleOrNull() ?: error("$label: expected one candidate, found ${it.size}: ${it.map { m -> m.descriptor }}")
    }

    private fun DexKitBridge.find(matcher: MethodMatcher): List<MethodData> =
        findMethod(FindMethod.create().matcher(matcher))

    private fun scroll(bridge: DexKitBridge): Map<String, String> {
        fun anchor(text: String) = bridge.find(MethodMatcher.create().usingEqStrings(text)).one(text).className
        val state = anchor("ReaderPagerState(initialPage=")
        val flows = anchor("ReaderPagerFlows(bookProgress=")
        val actions = anchor("ReaderPagerActions(onJumpCompleted=")
        val root = bridge.find(MethodMatcher.create().paramCount(9).returnType("void")).filter {
            Modifier.isStatic(it.modifiers) && it.paramTypeNames.subList(3, 6) == listOf(state, flows, actions) &&
                it.paramTypeNames.last() == "int"
        }.one("scroll reader root")
        val composer = root.paramTypeNames[7]
        val factory = root.invokes.filter {
            Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("int", composer, "int", "int") &&
                it.returnTypeName != "void"
        }.one("reader list state factory")
        val listType = factory.returnTypeName
        val layout = root.invokes.filter { it.isConstructor && listType in it.paramTypeNames }
            .distinctBy { it.descriptor }.flatMap { it.declaredClass?.methods.orEmpty() }
            .filter { it.isMethod && it.paramCount == 2 && it.returnTypeName == "java.lang.Object" }
            .flatMap { it.invokes }.filter {
                val p = it.paramTypeNames
                Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && p.size == 12 &&
                    p[1] == listType && p[6] == "boolean" && p[8] == "kotlin.jvm.functions.Function1" &&
                    p[9] == composer && p.takeLast(2) == listOf("int", "int")
            }.one("reader LazyColumn")
        val layoutCaller = layout.callers.filter { caller ->
            caller.paramCount == 2 && caller.returnTypeName == "java.lang.Object" &&
                root.invokes.any { it.isConstructor && it.className == caller.className && listType in it.paramTypeNames }
        }.one("reader scroll layout closure")
        // The internal LazyList receives the remembered/default fling object on every composition.
        val list = layout.invokes.filter {
            val p = it.paramTypeNames
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && p.size == 16 &&
                p[1] == listType && p[3] == "boolean" && p[4] == layout.paramTypeNames[5] &&
                p[11] == "kotlin.jvm.functions.Function1" && p[12] == composer
        }.one("reader LazyList")
        val flings = layout.invokes.filter { it.isConstructor }.distinctBy { it.descriptor }
            .flatMap { it.declaredClass?.methods.orEmpty() }.filter {
                !Modifier.isStatic(it.modifiers) && it.paramCount == 3 && it.paramTypeNames[1] == "float" &&
                    it.returnTypeName == "java.lang.Object"
            }
        val fling = flings.one("default list fling execution")
        val continuation = bridge.getClassData(fling.paramTypeNames[2]) ?: error("Missing fling continuation")
        val resume = continuation.methods.filter {
            it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "void" &&
                !Modifier.isStatic(it.modifiers)
        }.one("fling continuation resume")
        val result = linkedMapOf(
            "extraScrollRoot" to root.descriptor,
            "extraScrollState" to factory.descriptor,
            "extraScrollColumn" to layout.descriptor,
            "extraScrollLayout" to layoutCaller.descriptor,
            "extraScrollList" to list.descriptor,
            "extraScrollFling" to fling.descriptor,
            "extraScrollResume" to resume.descriptor,
        )
        // R8 may narrow LazyColumn's parameter to the concrete class; callers still use the interface.
        val contracts = bridge.find(MethodMatcher.create().paramCount(3).returnType("java.lang.Object")).filter {
            it.paramTypeNames == fling.paramTypeNames && Modifier.isAbstract(it.modifiers)
        }
        (contracts.flatMap { it.callers } + fling.callers).distinctBy { it.descriptor }
            .filter { it.isMethod }.forEachIndexed { index, method ->
                result["extraScrollFlingCaller$index"] = method.descriptor
            }
        return result
    }

    private fun curl(bridge: DexKitBridge): Map<String, String> {
        val duration = bridge.find(MethodMatcher.create().paramTypes("int").returnType("void"))
            .filter { it.name == "setAnimationDurationTime" }.one("curl duration setter")
        val child = requireNotNull(duration.declaredClass)
        fun childMethod(name: String, params: List<String>, result: String) = child.methods.filter {
            it.name == name && it.paramTypeNames == params && it.returnTypeName == result
        }.one("curl $name")
        childMethod("getAnimationDurationTime", emptyList(), "int")
        val jump = childMethod("setPageJumpDurationTime", listOf("long"), "void")
        val getJump = childMethod("getPageJumpDurationTime", emptyList(), "long")
        childMethod("getCurlState", emptyList(), "int")
        childMethod("setCurrentIndex", listOf("int"), "void")
        val ownerConstructor = duration.callers.filter { candidate ->
            candidate.isConstructor && candidate.paramTypeNames == listOf("android.content.Context") &&
                candidate.declaredClass?.superClass?.name == "android.widget.FrameLayout" &&
                candidate.invokes.any { it.descriptor == jump.descriptor }
        }.one("reader curl controller constructor")
        val owner = requireNotNull(ownerConstructor.declaredClass)
        check(owner.methods.filter { it.isConstructor }.map { it.descriptor } == listOf(ownerConstructor.descriptor)) {
            "Unverified curl controller constructor"
        }
        check(owner.fields.count { it.typeName == child.name } == 1) { "Curl child field is ambiguous" }
        val dispatch = owner.methods.filter {
            it.name == "dispatchTouchEvent" && it.paramTypeNames == listOf("android.view.MotionEvent") &&
                it.returnTypeName == "boolean" && it.invokes.any { m -> m.descriptor == duration.descriptor }
        }.one("reader curl dispatch")
        val synthetic = owner.methods.filter {
            it.paramTypeNames == listOf("float", "float", "int", "long") && it.returnTypeName == "void" &&
                it.invokes.any { m -> m.className == "android.view.MotionEvent" && m.name == "obtain" }
        }.one("reader curl synthetic touch")
        val cleanup = owner.methods.filter {
            it.paramCount == 0 && it.returnTypeName == "void" &&
                it.invokes.any { m -> m.descriptor == getJump.descriptor } &&
                it.invokes.any { m -> m.name == "postDelayed" && m.className == "android.view.View" }
        }.one("reader curl overlay cleanup")
        fun runOf(constructor: MethodData) = constructor.declaredClass?.methods.orEmpty().filter {
            it.name == "run" && it.paramCount == 0 && it.returnTypeName == "void"
        }.one("curl owned Runnable")
        val firstConstructor = dispatch.invokes.filter {
            it.isConstructor && it.paramTypeNames == listOf(owner.name, "long", "float", "float", "float")
        }.one("curl first synthetic move")
        val first = runOf(firstConstructor)
        check(first.invokes.any { it.descriptor == synthetic.descriptor })
        val finishConstructor = first.invokes.filter {
            it.isConstructor && it.paramTypeNames == listOf(owner.name, "long", "float", "float", "byte")
        }.one("curl synthetic move/up")
        val finish = runOf(finishConstructor)
        check(finish.invokes.any { it.descriptor == synthetic.descriptor } &&
            finish.invokes.any { it.descriptor == duration.descriptor })
        val maintenanceConstructor = ownerConstructor.invokes.filter {
            it.isConstructor && it.paramTypeNames == listOf(owner.name, "byte")
        }.one("curl maintenance callbacks")
        val maintenance = runOf(maintenanceConstructor)
        check(maintenance.invokes.any { it.descriptor == duration.descriptor })
        val detach = owner.methods.filter {
            it.name == "onDetachedFromWindow" && it.paramCount == 0 && it.returnTypeName == "void"
        }.one("curl controller detach")
        return mapOf(
            "constructor:extraCurlOwner" to ownerConstructor.descriptor,
            "extraCurlDuration" to duration.descriptor,
            "extraCurlDispatch" to dispatch.descriptor,
            "extraCurlTouch" to synthetic.descriptor,
            "extraCurlCleanup" to cleanup.descriptor,
            "extraCurlFirst" to first.descriptor,
            "extraCurlFinish" to finish.descriptor,
            "extraCurlMaintenance" to maintenance.descriptor,
            "extraCurlDetach" to detach.descriptor,
            "field:extraCurlChild" to owner.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == child.name }.descriptor,
            "field:extraCurlFirstOwner" to requireNotNull(first.declaredClass).fields.single {
                !Modifier.isStatic(it.modifiers) && it.typeName == owner.name }.descriptor,
            "field:extraCurlFinishOwner" to requireNotNull(finish.declaredClass).fields.single {
                !Modifier.isStatic(it.modifiers) && it.typeName == owner.name }.descriptor,
            "field:extraCurlMaintenanceOwner" to requireNotNull(maintenance.declaredClass).fields.single {
                !Modifier.isStatic(it.modifiers) && it.typeName == owner.name }.descriptor,
        )
    }
}
