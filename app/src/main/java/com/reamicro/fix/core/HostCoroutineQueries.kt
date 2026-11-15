package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Contracts of the coroutine protocol, independent of file-facade owners and R8 short names. */
internal object HostCoroutineQueries {
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Coroutine $role is missing or ambiguous")
    /** Independently discover the complete runtime protocol, including lifecycle and cancellation. */
    fun runtime(bridge: DexKitBridge): Map<String, String> = buildMap {
        fun anchor(vararg text: String) = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*text)))
        fun find(vararg args: String, result: String) = bridge.findMethod(FindMethod.create().matcher(
            MethodMatcher.create().paramTypes(*args).returnType(result)))
        fun save(role: String, method: MethodData) { put("method:$role", method.descriptor) }
        val create = anchor("create(Continuation) has not been overridden").one("continuation base")
        val base = requireNotNull(create.declaredClass)
        val continuation = create.paramTypes.single()
        val getContext = continuation.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramCount == 0 && it.returnTypeName != "void" }.one("continuation context")
        val context = requireNotNull(getContext.returnType)
        val resumeWith = continuation.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "void" }.one("continuation completion")
        val failure = requireNotNull(anchor("Failure(").filter { candidate ->
            candidate.declaredClass?.fields?.filter { !Modifier.isStatic(it.modifiers) }?.let { fields ->
                fields.size == 1 && fields.single().typeName == "java.lang.Throwable"
            } == true
        }.one("result failure").declaredClass)
        val failureConstructor = failure.methods.filter { it.isConstructor &&
            it.paramTypeNames == listOf("java.lang.Throwable") }.one("result failure constructor")
        val fold = context.methods.filter { it.isMethod && it.paramCount == 2 && it.returnTypeName == "java.lang.Object" &&
            "java.lang.Object" in it.paramTypeNames }.one("context fold")
        val function = fold.paramTypes.single { it.name != "java.lang.Object" }
        val invoke = function.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == List(2) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" }.one("function invoke")
        val run = find(context.name, function.name, result = "java.lang.Object").filter { Modifier.isStatic(it.modifiers) &&
            it.invokes.any { call -> call.descriptor == "Ljava/util/concurrent/locks/LockSupport;->parkNanos(Ljava/lang/Object;J)V" } &&
            it.invokes.any { call -> call.descriptor == "Ljava/lang/Thread;->interrupted()Z" } }.one("runBlocking")
        val delay = find("long", continuation.name, result = "java.lang.Object").filter { Modifier.isStatic(it.modifiers) &&
            it.usingNumbers.any { n -> n.longValue() == Long.MAX_VALUE } && it.invokes.any { call ->
                call.paramCount == 2 && call.paramTypeNames.first() == "long" && call.returnTypeName == "void" &&
                    call.declaredClass?.let { Modifier.isInterface(it.modifiers) } == true } }.one("delay")
        val withContext = anchor("Already suspended").filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf(context.name, function.name, continuation.name) && it.returnTypeName == "java.lang.Object" }
            .one("withContext")
        val getJob = anchor("Current context doesn't contain Job in it: ").filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf(context.name) }.one("context job")
        val job = requireNotNull(getJob.returnType)
        val cancellation = job.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramCount == 0 && it.returnTypeName == "java.util.concurrent.CancellationException" }.one("job cancellation")
        val ensure = withContext.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(context.name) &&
            it.returnTypeName == "void" && it.invokes.any { call -> call.descriptor == cancellation.descriptor } }.one("active context guard")
        val active = ensure.invokes.filter { it.declaredClassName == job.name && it.paramCount == 0 && it.returnTypeName == "boolean" }
            .one("job activity")
        val cancel = job.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.util.concurrent.CancellationException") && it.returnTypeName == "void" }.one("job cancel")
        val viewModelScope = anchor("androidx.lifecycle.viewmodel.internal.ViewModelCoroutineScope.JOB_KEY")
            .filter { Modifier.isStatic(it.modifiers) && it.paramCount == 1 && it.returnTypeName != "void" }.one("viewmodel scope")
        val scope = requireNotNull(viewModelScope.returnType)
        val scopeContext = scope.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramCount == 0 && it.returnTypeName == context.name }.one("viewmodel scope context")
        val empty = requireNotNull(anchor("EmptyCoroutineContext").filter { it.name == "toString" && it.paramCount == 0 }
            .one("empty context").declaredClass)
        val emptyValue = empty.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == empty.name }
        val unit = requireNotNull(anchor("kotlin.Unit").filter { it.name == "toString" && it.paramCount == 0 }
            .one("unit").declaredClass)
        val unitValue = unit.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == unit.name }
        val singleton = requireNotNull(anchor("COROUTINE_SUSPENDED", "UNDECIDED", "RESUMED")
            .filter { it.name == "<clinit>" }.one("coroutine states").declaredClass)
        val mainInit = anchor("kotlinx.coroutines.fast.service.loader").filter { it.name == "<clinit>" }.one("main dispatcher loader")
        val main = requireNotNull(anchor("Dispatchers.Main", "Dispatchers.Main.immediate")
            .filter { it.name == "toString" && it.paramCount == 0 }.one("main dispatcher").declaredClass)
        val mainValue = requireNotNull(mainInit.declaredClass).fields.single { Modifier.isStatic(it.modifiers) && it.typeName == main.name }
        val parent = requireNotNull(find(result = "int").filter { it.name == "getArity" && it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.declaredClass?.let { owner ->
                owner.methods.any { ctor -> ctor.isConstructor && ctor.paramTypeNames == listOf("int", continuation.name) } &&
                    owner.superClass?.let { superclass -> superclass.fields.any { f -> !Modifier.isStatic(f.modifiers) && f.typeName == context.name } &&
                        generateSequence(superclass) { it.superClass }.any { it.name == base.name } } == true
            } == true }.one("suspend lambda").declaredClass)
        val constructor = parent.methods.filter { it.isConstructor && it.paramTypeNames == listOf("int", continuation.name) }.one("suspend lambda constructor")
        val resume = base.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object" }.one("invokeSuspend")
        for ((role, type) in listOf("continuation" to continuation, "context" to context, "function2" to function, "job" to job,
            "viewModel" to viewModelScope.paramTypes.single(), "scope" to scope, "empty" to empty, "unit" to unit,
            "states" to singleton, "main" to main, "parent" to parent, "base" to base)) put("class:$role", type.descriptor)
        for ((role, method) in listOf("runBlocking" to run, "delay" to delay, "withContext" to withContext, "getJob" to getJob,
            "active" to active, "cancel" to cancel, "viewModelScope" to viewModelScope, "scopeContext" to scopeContext,
            "continuationContext" to getContext, "invoke" to invoke, "resume" to resume)) save(role, method)
        put("constructor:parent", constructor.descriptor)
        put("class:failure", failure.descriptor)
        put("constructor:failure", failureConstructor.descriptor)
        save("resumeWith", resumeWith)
        put("field:empty", emptyValue.descriptor); put("field:unit", unitValue.descriptor); put("field:main", mainValue.descriptor)
    }
    fun query(bridge: DexKitBridge, types: Map<String, String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        fun name(logical: String) = types["class:$logical"]?.removePrefix("L")?.removeSuffix(";")?.replace('/', '.')
        fun method(logical: String, descriptor: String) { result["method:$logical"] = descriptor }
        val continuation = name("kotlin.coroutines.Continuation") ?: return result
        val context = name("kotlin.coroutines.CoroutineContext") ?: return result
        val function = name("kotlin.jvm.functions.Function2") ?: return result
        bridge.getClassData(function)?.methods?.singleOrNull { it.paramTypeNames == List(2) { "java.lang.Object" } }
            ?.let { method("kotlin.jvm.functions.Function2#invoke", it.descriptor) }
        bridge.getClassData(continuation)?.methods?.forEach {
            when {
                it.paramCount == 0 && it.returnTypeName == context -> method("kotlin.coroutines.Continuation#getContext", it.descriptor)
                it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "void" -> method("kotlin.coroutines.Continuation#resumeWith", it.descriptor)
            }
        }
        bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .paramTypes(context, function).returnType("java.lang.Object")))
            .filter { Modifier.isStatic(it.modifiers) && it.invokes.any { m ->
                m.descriptor == "Ljava/util/concurrent/locks/LockSupport;->parkNanos(Ljava/lang/Object;J)V"
            } && it.invokes.any { m -> m.descriptor == "Ljava/lang/Thread;->interrupted()Z" } }
            .distinctBy { it.descriptor }.singleOrNull()?.let { method("kotlinx.coroutines.BuildersKt#runBlocking", it.descriptor) }
        bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .paramTypes("long", continuation).returnType("java.lang.Object")))
            .filter { Modifier.isStatic(it.modifiers) && it.usingNumbers.any { n -> n.longValue() == Long.MAX_VALUE } &&
                it.invokes.any { m -> m.paramCount == 2 && m.paramTypeNames.first() == "long" &&
                    m.returnTypeName == "void" && m.declaredClass?.let { Modifier.isInterface(it.modifiers) } == true } }
            .distinctBy { it.descriptor }.singleOrNull()?.let { method("kotlinx.coroutines.DelayKt#delay", it.descriptor) }
        val empty = name("kotlin.coroutines.EmptyCoroutineContext")
        bridge.getClassData(empty ?: "java.lang.Void")?.fields?.singleOrNull { Modifier.isStatic(it.modifiers) && it.typeName == empty }
            ?.let { result["field:kotlin.coroutines.EmptyCoroutineContext#INSTANCE"] = it.descriptor }
        return result
    }
}
