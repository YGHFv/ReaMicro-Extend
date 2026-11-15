package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Native task records and their synchronization boundary, resolved from callers and values. */
internal object HostWorkQueries {
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Native work $role is missing or ambiguous")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val code = HostDexCalls(apk)
        fun type(role: String, logical: String): ClassData =
            requireNotNull(bridge.getClassData(base.getValue("class:$logical"))).also { result["class:$role"] = it.descriptor }
        fun method(role: String, m: MethodData): MethodData = m.also {
            result["${if (it.isConstructor) "constructor" else "method"}:$role"] = it.descriptor
        }
        fun field(role: String, descriptor: String) {
            requireNotNull(bridge.getFieldData(descriptor))
            result["field:$role"] = descriptor
        }
        val manager = type("manager", "app.zhendong.reamicro.arch.WorkerManager")
        val tracker = type("tracker", "app.zhendong.reamicro.arch.WorkTracker")
        val state = type("state", "app.zhendong.reamicro.arch.WorkState")
        val status = type("status", "app.zhendong.reamicro.arch.WorkStatus")
        val handle = type("handle", "app.zhendong.reamicro.arch.WorkHandle")
        val bookshelf = type("bookshelf", "app.zhendong.reamicro.repository.BookshelfRepository")
        val cloud = type("cloud", "app.zhendong.reamicro.data.storage.CloudBook")
        val platform = type("platform", "ReaMicroHost.PlatformFile")
        val home = type("home", "app.zhendong.reamicro.ui.home.HomeViewModel")
        val storage = type("storage", "app.zhendong.reamicro.repository.storage.CloudStorageRepository")
        check(status.superClass?.name == "java.lang.Enum")
        check(status.methods.single { it.name == "<clinit>" }.usingStrings.containsAll(HostWorkBindings.STATUSES))
        val snapshot = requireNotNull(bridge.findMethod(FindMethod.create().matcher(
            MethodMatcher.create().name("toString").paramCount(0).returnType("java.lang.String")
                .usingEqStrings("WorkSnapshot(id=", ", kind=", ", label=")
        )).one("snapshot labels").declaredClass)
        result["class:snapshot"] = snapshot.descriptor
        val records = HostRecordQueries.query(apk, mapOf("class:state" to state.descriptor,
            "class:handle" to handle.descriptor, "class:snapshot" to snapshot.descriptor))
        for ((role, names) in mapOf("state" to HostWorkBindings.STATE_FIELDS,
            "handle" to listOf("id", "state"), "snapshot" to HostWorkBindings.SNAPSHOT_FIELDS)) {
            for (name in names) field("$role.$name", records.getValue("field:$role#$name"))
        }
        val flowType = requireNotNull(bridge.getFieldData(result.getValue("field:handle.state"))).type
        result["class:stateFlow"] = flowType.descriptor
        fun recordConstructor(role: String, owner: ClassData, names: List<String>) {
            val fields = names.map { result.getValue("field:$role.$it") }
            val matches = owner.methods.filter { it.isConstructor && it.paramCount == fields.size }.filter { ctor ->
                val writes = code.writes(ctor.descriptor)
                fields.all { f -> writes.singleOrNull { it.field == f && it.receiver == HostDexCalls.Parameter(0) }
                    ?.value is HostDexCalls.Parameter }
            }
            val ctor = method(role, matches.one("$role record constructor"))
            val writes = code.writes(ctor.descriptor)
            val slots = names.map { name ->
                val f = requireNotNull(bridge.getFieldData(result.getValue("field:$role.$name")))
                val slot = (writes.single { it.field == f.descriptor && it.receiver == HostDexCalls.Parameter(0) }
                    .value as HostDexCalls.Parameter).index - 1
                check(slot in ctor.paramTypeNames.indices && ctor.paramTypeNames[slot] == f.typeName)
                result["value:$role.$name"] = slot.toString()
                slot
            }
            check(slots.toSet().size == names.size)
        }
        recordConstructor("state", state, HostWorkBindings.STATE_FIELDS)
        recordConstructor("handle", handle, listOf("id", "state"))
        val create = method("create", tracker.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.String", "java.lang.String", "java.lang.String", state.name) &&
            it.returnTypeName == flowType.name }.one("create task"))
        val update = method("update", tracker.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.String", state.name) && it.returnTypeName == "void" &&
            it.invokes.any { call -> call.descriptor == "Ljava/util/concurrent/locks/ReentrantLock;->lock()V" }
        }.one("locked task update"))
        val publish = update.invokes.filter { it.declaredClassName == tracker.name &&
            it.paramTypeNames == update.paramTypeNames && it.returnTypeName == "void" }.one("publish task snapshot")
        check(create.invokes.any { it.descriptor == publish.descriptor })
        check(publish.invokes.any { it.isConstructor && it.declaredClassName == snapshot.name })
        val get = method("flow.get", publish.invokes.filter { it.declaredClassName == flowType.name &&
            it.paramCount == 0 && it.returnTypeName == "java.lang.Object" }.one("snapshot flow read"))
        val worksRead = code.calls(publish.descriptor).single { it.descriptor == get.descriptor }
        val works = (worksRead.arguments.single() as HostDexCalls.Field).descriptor
        check(works.startsWith(tracker.descriptor + "->"))
        field("tracker.works", works)
        val lockCall = code.calls(update.descriptor).single {
            it.descriptor == "Ljava/util/concurrent/locks/ReentrantLock;->lock()V"
        }
        val lock = requireNotNull(bridge.getFieldData((lockCall.arguments.single() as HostDexCalls.Field).descriptor))
        field("gate.lock", lock.descriptor)
        val gate = requireNotNull(lock.declaredClass)
        result["class:gate"] = gate.descriptor
        val gates = update.usingFields.map { it.field }.filter { it.declaredClassName == tracker.name && it.typeName == gate.name }
            .distinctBy { it.descriptor }
        field("tracker.gate", gates.single().descriptor)
        val entryRead = code.calls(update.descriptor).single { it.descriptor in setOf(
            "Ljava/util/LinkedHashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;",
            "Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;") }
        check(entryRead.arguments[1] == HostDexCalls.Parameter(1))
        val tasks = (entryRead.arguments[0] as HostDexCalls.Field).descriptor
        check(tasks.startsWith(tracker.descriptor + "->"))
        field("tracker.tasks", tasks)
        val workflow = method("unique", tracker.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.String") && it.returnTypeName != "void" &&
            it.usingFields.any { f -> f.field.descriptor == works } }.one("unique work flow"))
        result["class:flow"] = requireNotNull(workflow.returnType).descriptor
        method("flow.new", create.invokes.filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == flowType.name }.one("task state flow factory"))
        for ((role, target) in mapOf("tracker" to tracker, "bookshelf" to bookshelf)) {
            val f = manager.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == target.name }
            field("manager.$role", f.descriptor)
        }
        method("manager", manager.methods.filter { it.isConstructor && it.paramTypeNames.containsAll(listOf(tracker.name, bookshelf.name)) }
            .one("worker manager constructor"))
        method("tracker", tracker.methods.filter { it.isConstructor && it.paramCount == 0 }.one("tracker constructor"))
        for ((role, owner, dependencies) in listOf(Triple("home", home, mapOf("bookshelf" to bookshelf, "storage" to storage, "manager" to manager)),
            Triple("storage", storage, mapOf("bookshelf" to bookshelf, "manager" to manager)))) {
            val ctor = method(role, owner.methods.filter { it.isConstructor &&
                it.paramTypeNames.containsAll(dependencies.values.map { dep -> dep.name }) }.one("$role dependency constructor"))
            for ((name, dep) in dependencies) {
                val f = owner.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == dep.name }
                val argument = ctor.paramTypeNames.indices.single { ctor.paramTypeNames[it] == dep.name } + 1
                check(code.writes(ctor.descriptor).single { it.field == f.descriptor && it.receiver == HostDexCalls.Parameter(0) }
                    .value == HostDexCalls.Parameter(argument))
                field("$role.$name", f.descriptor)
            }
        }
        val backup = method("backup", manager.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("long", "int", "java.lang.String") && it.returnTypeName == handle.name &&
            it.usingStrings.containsAll(listOf("backup", "backup:")) }.one("backup enqueue"))
        val download = method("download", manager.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf(cloud.name, "java.lang.String") && it.returnTypeName == handle.name &&
            "download" in it.usingStrings }.one("download enqueue"))
        val imports = method("import", manager.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf(platform.name, "java.lang.String", "boolean") && it.returnTypeName == handle.name &&
            "import" in it.usingStrings }.one("import enqueue"))
        check(listOf(backup, download, imports).all { native -> native.invokes.any { it.descriptor == create.descriptor } })
        return result
    }
}
