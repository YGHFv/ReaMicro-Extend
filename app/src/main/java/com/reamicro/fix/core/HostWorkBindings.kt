package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier
import java.util.concurrent.locks.ReentrantLock

internal object HostWorkBindings {
    const val KEY = "integration.work"
    val STATUSES = listOf("Idle", "Running", "Success", "Error", "Cancelled")
    val STATE_FIELDS = listOf("status", "progress", "error", "result", "name")
    val SNAPSHOT_FIELDS = listOf("id", "kind", "label", "status", "progress", "error", "result", "name")
    @Volatile var initializationAttempted = false; private set
    @Volatile var current: Contract? = null; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors() = snapshot?.toMap()
    fun requireCurrent() = requireNotNull(current) { "Native work contract unavailable" }
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            var bound: Contract? = null
            val result = session.query(KEY,
                validate = { bound = restore(it, loader) },
                find = { HostWorkQueries.query(it, apk, HostAutoScanner.queriedDescriptors().orEmpty()) })
            current = requireNotNull(bound); snapshot = result.values.toMap()
            HookRuntime.log("ReaMicro $KEY ${if (result.cached) "cached" else "resolved"}")
        } }.onFailure { HookRuntime.logError("ReaMicro native work unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) =
        session.query(KEY, true,
            validate = { restore(it, session.loader) }, find = { HostWorkQueries.query(it, session.apk, base) }).values.toMap()

    class Contract internal constructor(private val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun type(role: String) = members.type(role)
        fun field(role: String) = members.field(role)
        fun method(role: String) = members.method(role)
        fun constructor(role: String) = members.constructor(role)
        fun slot(role: String) = values.getValue("value:$role").toInt()
        fun tracker(manager: Any): Any = requireNotNull(field("manager.tracker").get(manager))
        fun bookshelf(manager: Any): Any = requireNotNull(field("manager.bookshelf").get(manager))
        fun handleId(handle: Any) = field("handle.id").get(handle) as String
        fun stateName(state: Any) = (field("state.status").get(state) as Enum<*>).name
        private val statuses by lazy { type("status").enumConstants.associateBy { (it as Enum<*>).name } }
        private fun record(role: String, properties: Map<String, Any?>): Any {
            val args = arrayOfNulls<Any>(constructor(role).parameterCount)
            properties.forEach { (name, value) -> args[slot("$role.$name")] = value }
            return constructor(role).newInstance(*args)
        }
        fun state(status: String, progress: Int, error: String?, result: String?, name: String?): Any =
            record("state", mapOf("status" to statuses.getValue(status), "progress" to progress.coerceIn(0, 100),
                "error" to error, "result" to result, "name" to name))
        fun handle(id: String, flow: Any) = record("handle", mapOf("id" to id, "state" to flow))
        fun stateFlow(state: Any): Any = requireNotNull(method("flow.new").invoke(null, state))
        fun create(tracker: Any, id: String, kind: String, label: String, state: Any): Any =
            requireNotNull(method("create").invoke(tracker, id, kind, label, state))
        fun unique(tracker: Any, name: String): Any = requireNotNull(method("unique").invoke(tracker, name))
        fun update(tracker: Any, id: String, state: Any) = locked(tracker) {
            // A completed network call must not resurrect a cancelled task. The read and
            // native update share the host's reentrant lock with cancel().
            if (!com.reamicro.fix.hook.ImportCompletionState.acceptsTransition(
                currentState(tracker, id)?.let(::stateName), stateName(state))) return@locked
            method("update").invoke(tracker, id, state)
            Unit
        }
        /** Only called after the native import handle reports durable Success. */
        fun completeImport(tracker: Any, id: String, result: String?, name: String?) = locked(tracker) {
            method("update").invoke(tracker, id, state("Success", 100, null, result, name))
            Unit
        }
        private fun <T> locked(tracker: Any, action: () -> T): T {
            val lock = trackerLock(tracker)
            lock.lock()
            try { return action() } finally { lock.unlock() }
        }
        fun trackerLock(tracker: Any): ReentrantLock =
            field("gate.lock").get(requireNotNull(field("tracker.gate").get(tracker))) as ReentrantLock
        fun currentState(tracker: Any, id: String): Any? {
            val tasks = field("tracker.tasks").get(tracker) as Map<*, *>
            val flow = tasks[id] ?: return null
            return method("flow.get").invoke(flow)
        }
        fun isImport(tracker: Any, id: String): Boolean {
            val flow = requireNotNull(field("tracker.works").get(tracker))
            val works = method("flow.get").invoke(flow) as Iterable<*>
            return works.filterNotNull().any {
                field("snapshot.id").get(it) == id && field("snapshot.kind").get(it) == "import"
            }
        }
        /** Native Success is emitted after work completes, even when cancellation raced with it. */
        fun acceptsNativeUpdate(tracker: Any, id: String, state: Any): Boolean =
            com.reamicro.fix.hook.ImportCompletionState.acceptsTransition(
                currentState(tracker, id)?.let(::stateName), stateName(state), completedImport = true)
        fun cancelled(tracker: Any, id: String): Boolean = locked(tracker) {
            currentState(tracker, id)?.let(::stateName) == "Cancelled"
        }
        fun cancel(tracker: Any, id: String) = locked(tracker) {
            val current = currentState(tracker, id) ?: return@locked
            if (stateName(current) !in setOf("Running", "Idle")) return@locked
            val properties = STATE_FIELDS.associateWith { field("state.$it").get(current) }.toMutableMap()
            properties["status"] = statuses.getValue("Cancelled")
            // The host lock is reentrant; its updater publishes both task flow and snapshot list.
            update(tracker, id, record("state", properties))
        }
        fun cancelBackups(tracker: Any, bookId: Long) = locked(tracker) {
            val flow = requireNotNull(field("tracker.works").get(tracker))
            val works = method("flow.get").invoke(flow) as Iterable<*>
            val ids = works.filterNotNull().filter { work ->
                (field("snapshot.kind").get(work) as String).startsWith("backup") &&
                    field("snapshot.label").get(work) == bookId.toString()
            }.map { field("snapshot.id").get(it) as String }
            ids.forEach { cancel(tracker, it) }
        }
    }

    private fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val c = Contract(values.toMap(), HostRuntimeBindings.Members(values, loader))
        val string = String::class.java; val any = Any::class.java; val void = Void.TYPE
        fun field(role: String, owner: String, type: Class<*>) {
            val f = c.field(role)
            check(f.declaringClass == c.type(owner) && f.type == type && !Modifier.isStatic(f.modifiers))
        }
        fun method(role: String, owner: String?, args: List<Class<*>>, result: Class<*>, static: Boolean = false) {
            val m = c.method(role)
            check((owner == null || m.declaringClass == c.type(owner)) && m.parameterTypes.toList() == args &&
                m.returnType == result && Modifier.isStatic(m.modifiers) == static)
        }
        check(c.type("status").isEnum && c.type("status").enumConstants.map { (it as Enum<*>).name }.toSet() == STATUSES.toSet())
        for ((role, names) in mapOf("state" to STATE_FIELDS, "snapshot" to SNAPSHOT_FIELDS, "handle" to listOf("id", "state"))) {
            for (name in names) field("$role.$name", role, when (name) {
                "status" -> c.type("status"); "progress" -> Integer.TYPE; "state" -> c.type("stateFlow"); else -> string
            })
            check(names.map { c.field("$role.$it") }.distinct().size == names.size)
            if (role == "snapshot") continue
            val ctor = c.constructor(role)
            check(ctor.declaringClass == c.type(role) && ctor.parameterCount == names.size)
            check(names.map { c.slot("$role.$it") }.toSet() == names.indices.toSet())
            names.forEach { check(ctor.parameterTypes[c.slot("$role.$it")] == c.field("$role.$it").type) }
        }
        field("manager.tracker", "manager", c.type("tracker")); field("manager.bookshelf", "manager", c.type("bookshelf"))
        field("tracker.works", "tracker", c.type("stateFlow")); field("tracker.gate", "tracker", c.type("gate"))
        field("tracker.tasks", "tracker", java.util.LinkedHashMap::class.java)
        field("gate.lock", "gate", ReentrantLock::class.java)
        method("create", "tracker", listOf(string, string, string, c.type("state")), c.type("stateFlow"))
        method("update", "tracker", listOf(string, c.type("state")), void)
        method("unique", "tracker", listOf(string), c.type("flow"))
        method("flow.get", "stateFlow", emptyList(), any)
        method("flow.new", null, listOf(any), c.type("stateFlow"), true)
        check(c.type("flow").isAssignableFrom(c.type("stateFlow")))
        method("backup", "manager", listOf(java.lang.Long.TYPE, Integer.TYPE, string), c.type("handle"))
        method("download", "manager", listOf(c.type("cloud"), string), c.type("handle"))
        method("import", "manager", listOf(c.type("platform"), string, java.lang.Boolean.TYPE), c.type("handle"))
        check(c.constructor("tracker").let { it.declaringClass == c.type("tracker") && it.parameterCount == 0 })
        check(c.constructor("manager").let { it.declaringClass == c.type("manager") &&
            it.parameterTypes.count { t -> t == c.type("tracker") } == 1 &&
            it.parameterTypes.count { t -> t == c.type("bookshelf") } == 1 })
        for ((role, dependencies) in mapOf("home" to listOf("bookshelf", "storage", "manager"),
            "storage" to listOf("bookshelf", "manager"))) {
            val ctor = c.constructor(role)
            check(ctor.declaringClass == c.type(role))
            for (dep in dependencies) {
                field("$role.$dep", role, c.type(dep))
                check(ctor.parameterTypes.count { it == c.type(dep) } == 1)
            }
        }
        return c
    }
}
