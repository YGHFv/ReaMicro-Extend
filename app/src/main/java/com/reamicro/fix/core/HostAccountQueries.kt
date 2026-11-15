package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Account records, preference transactions and native eligibility checks from the installed APK. */
internal object HostAccountQueries {
    private const val P = "app.zhendong.reamicro."
    val PREFERENCES = setOf(
        "token", "dynamic_color", "smart_recognition", "illustration_optimization", "relate_download_cover",
        "calling_card_color", "dark_mode", "backup_type", "bookshelf_type", "theme", "mipmap", "background",
        "text_size", "line_height", "family", "embedded_fonts", "built_in_fonts", "padding", "padding_top",
        "padding_bottom", "padding_left", "padding_right", "padding_top_outer", "padding_bottom_outer",
        "only_next_page", "volume_key", "time_battery", "screen_always_on", "system_bars", "agreement",
        "baidu_auth", "aliyun_auth", "yun115_auth",
        "s3_sync_provider", "s3_sync_endpoint", "s3_sync_bucket", "s3_sync_region", "s3_sync_access_key",
        "s3_sync_secret_key", "s3_sync_prefix", "s3_sync_cloud_config_enabled", "s3_sync_baseline_cbor",
        "s3_sync_pending_deletions",
    )
    val USER_FIELDS = listOf("id", "token", "data", "updated")
    val INFO_FIELDS = listOf("id", "nickName", "email", "thirdBinds", "vipExpireTime", "userType")
    val MARGINS = listOf("top", "bottom", "left", "right", "topOuter", "bottomOuter")
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Account $role is missing or ambiguous")

    class Discovery(private val bridge: DexKitBridge, private val apk: String, private val base: Map<String, String>) {
        private val flow by lazy { HostDexCalls(apk) }
        private fun type(logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
        private fun anchor(vararg strings: String) = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*strings)))
        private fun ClassData.isA(parent: ClassData): Boolean = name == parent.name ||
            interfaces.any { it.isA(parent) } || superClass?.isA(parent) == true
        private val user by lazy { type(P + "data.db.entity.User") }
        private val info by lazy { type(P + "data.res.user.UserInfo") }
        private val repository by lazy { type(P + "repository.UserRepository") }
        private val session by lazy { type(P + "repository.core.Session") }
        private val continuation by lazy { type("kotlin.coroutines.Continuation") }
        private val prefs by lazy { HostHomeQueries.Discovery(bridge, apk, base, emptyMap()).query("preferences") }
        private fun suspendMethod(m: MethodData, args: List<String>) = !Modifier.isStatic(m.modifiers) && m.isMethod &&
            m.returnTypeName == "java.lang.Object" && m.paramCount == args.size + 1 &&
            m.paramTypeNames.dropLast(1) == args && m.paramTypes.last().isA(continuation)
        fun query(group: String): Map<String, String> = when (group) {
            "identity" -> identity()
            "preferences" -> preferences()
            "advanced" -> advanced()
            "ui" -> ui()
            else -> error("Unknown account group: $group")
        }
        private fun ui(): Map<String, String> = buildMap {
            val state = type(P + "ui.setting.SettingsUIState")
            val composer = type("androidx.compose.runtime.Composer")
            val function = type("kotlin.jvm.functions.Function1")
            val deleting = base.getValue("field:${P}ui.setting.SettingsUIState#deletingAccount")
            val infoMethod = requireNotNull(bridge.getMethodData(base.getValue("method:${P}data.db.entity.User#info")))
            val content = infoMethod.callers.filter { m -> !Modifier.isStatic(m.modifiers) &&
                m.paramTypeNames == List(3) { "java.lang.Object" } && m.returnTypeName == "java.lang.Object" &&
                m.usingFields.any { it.field.descriptor == deleting } && m.invokes.any { call ->
                    Modifier.isStatic(call.modifiers) && call.paramCount == 12 && call.returnTypeName == "void" &&
                        call.paramTypeNames[8] == function.name && call.paramTypeNames.takeLast(3) == listOf(composer.name, "int", "int")
                } }.one("account lazy content")
            val contentClass = requireNotNull(content.declaredClass)
            val viewModel = contentClass.fields.filter { !Modifier.isStatic(it.modifiers) }.map { it.type }.filter { cls ->
                cls.methods.any { ctor -> ctor.isConstructor && "app.zhendong.reamicro.data.db.AppDatabase" in ctor.paramTypeNames &&
                    ctor.invokes.any { it.declaredClassName == state.name } }
            }.distinctBy { it.descriptor }.single()
            val builderClass = requireNotNull(content.invokes.filter { ctor -> ctor.isConstructor && ctor.declaredClass?.let { cls ->
                cls.isA(function) && cls.methods.any { m -> m.isMethod && !Modifier.isStatic(m.modifiers) &&
                    m.paramTypeNames == listOf("java.lang.Object") && m.returnTypeName == "java.lang.Object" &&
                    flow.casts(m.descriptor).map { it.type }.containsAll(listOf(info.descriptor, viewModel.descriptor)) }
            } == true }
                .one("account list allocation").declaredClass)
            val builder = builderClass.methods.filter { !Modifier.isStatic(it.modifiers) && it.isMethod &&
                it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object" }.one("account list builder")
            // Identify the captured values by their use in the builder, not by a merged-lambda
            // branch number or the order of Object parameters in its constructor.
            for ((role, cls) in listOf("info" to info, "viewModel" to viewModel)) {
                val captured = flow.casts(builder.descriptor).filter { it.type == cls.descriptor }.mapNotNull { it.value as? HostDexCalls.Field }
                    .map { it.descriptor }.distinct().single()
                val field = requireNotNull(bridge.getFieldData(captured))
                check(!Modifier.isStatic(field.modifiers) && field.declaredClassName == builderClass.name && field.typeName == "java.lang.Object")
                put("field:builder.$role", captured)
            }
            val root = contentClass.methods.filter { it.isConstructor }.flatMap { it.callers }.filter { m ->
                Modifier.isStatic(m.modifiers) && m.returnTypeName == "void" && m.paramCount == 3 &&
                    m.paramTypeNames.takeLast(2) == listOf(composer.name, "int")
            }.one("account security screen")
            val itemDefault = builder.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 4 &&
                it.paramTypeNames.takeLast(3) == listOf("java.lang.String", "kotlin.jvm.functions.Function3", "int") &&
                it.returnTypeName == "void" }.one("account list item default")
            val item = itemDefault.invokes.filter { !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("java.lang.Object", "java.lang.Object", "kotlin.jvm.functions.Function3") &&
                it.returnTypeName == "void" }.one("account list item")
            for ((role, cls) in listOf("info" to info, "viewModel" to viewModel, "builder" to builderClass,
                "content" to contentClass, "composer" to composer, "scope" to root.paramTypes.first(),
                "listScope" to requireNotNull(item.declaredClass), "function" to function)) put("class:$role", cls.descriptor)
            put("method:root", root.descriptor); put("method:content", content.descriptor)
            put("method:builder", builder.descriptor); put("method:item", item.descriptor)
        }
        private fun MutableMap<String, String>.record(role: String, logical: String, cls: ClassData, names: List<String>) {
            val fields = names.associateWith { base.getValue("field:$logical#$it") }
            check(fields.values.toSet() == cls.fields.filter { !Modifier.isStatic(it.modifiers) }.map { it.descriptor }.toSet())
            val candidates = cls.methods.filter { it.isConstructor && it.paramCount == names.size }.mapNotNull { ctor ->
                val writes = flow.writes(ctor.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) }
                val order = (1..ctor.paramCount).map { p -> writes.filter { it.value == HostDexCalls.Parameter(p) }.map { it.field }.distinct().singleOrNull() }
                if (order.all { it != null } && order.toSet() == fields.values.toSet()) ctor to order else null
            }
            val (ctor, order) = candidates.single()
            put("constructor:$role", ctor.descriptor)
            fields.forEach { (name, field) ->
                put("field:$role.$name", field); put("value:$role.$name", order.indexOf(field).toString())
            }
        }
        private fun MutableMap<String, String>.activityRepository() {
            val activity = requireNotNull(bridge.getClassData("Lapp/zhendong/reamicro/MainActivity;"))
            val candidates = activity.fields.filter { !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) }
                .flatMap { field ->
                    field.readers.flatMap { reader -> flow.casts(reader.descriptor) }
                        .filter { it.type == repository.descriptor }
                        .mapNotNull { cast ->
                            val call = cast.value as? HostDexCalls.Call ?: return@mapNotNull null
                            if (call.arguments != listOf(HostDexCalls.Field(field.descriptor))) return@mapNotNull null
                            val getter = bridge.getMethodData(call.descriptor) ?: return@mapNotNull null
                            if (!getter.isMethod || Modifier.isStatic(getter.modifiers) || getter.paramCount != 0 ||
                                getter.returnTypeName != "java.lang.Object" || getter.declaredClassName != field.typeName ||
                                !Modifier.isInterface(field.type.modifiers)) return@mapNotNull null
                            field to getter
                        }
                }.distinctBy { (field, getter) -> field.descriptor to getter.descriptor }
            val (field, getter) = candidates.singleOrNull()
                ?: error("Account activity repository property is missing or ambiguous")
            put("class:activity", activity.descriptor)
            put("class:repositoryProperty", field.type.descriptor)
            put("field:activity.repository", field.descriptor)
            put("method:repositoryValue", getter.descriptor)
        }
        private fun identity(): Map<String, String> = buildMap {
            val storage = type(P + "arch.fs.UserStorage")
            val shelf = type(P + "repository.BookshelfRepository")
            val cachedData = type("ReaMicroHost.CachedUserData")
            val cachedUser = requireNotNull(bridge.getFieldData(base.getValue("field:ReaMicroHost.CachedUserData#user")))
            val save = cachedUser.readers.filter { m -> Modifier.isStatic(m.modifiers) && m.paramCount == 1 &&
                cachedData.isA(m.paramTypes.single()) && m.returnTypeName == "void" && flow.writes(m.descriptor).any { w ->
                    w.receiver == null && w.value == HostDexCalls.Field(cachedUser.descriptor) &&
                        bridge.getFieldData(w.field)?.typeName == user.name
                } }.one("cache publication")
            val current = flow.writes(save.descriptor).filter { it.receiver == null && it.value == HostDexCalls.Field(cachedUser.descriptor) &&
                bridge.getFieldData(it.field)?.typeName == user.name }.map { it.field }.distinct().single()
            val daos = repository.fields.filter { !Modifier.isStatic(it.modifiers) && it.type.methods.any { m -> suspendMethod(m, listOf(user.name)) } }
            val dao = daos.single()
            val upsert = dao.type.methods.filter { suspendMethod(it, listOf(user.name)) }.one("user upsert")
            check(dao.type.methods.any { "users" in it.usingStrings })
            activityRepository()
            for ((role, cls) in listOf("user" to user, "info" to info, "repository" to repository, "session" to session,
                "storage" to storage, "shelf" to shelf, "cachedData" to cachedData, "dao" to dao.type, "continuation" to continuation)) put("class:$role", cls.descriptor)
            record("user", P + "data.db.entity.User", user, USER_FIELDS)
            INFO_FIELDS.forEach { put("field:info.$it", base.getValue("field:${P}data.res.user.UserInfo#$it")) }
            put("method:info", base.getValue("method:${P}data.db.entity.User#info"))
            put("method:saveCache", save.descriptor); put("field:cachedUser", current)
            put("field:cachedData.user", cachedUser.descriptor)
            put("constructor:cachedData", cachedData.methods.filter { it.isConstructor && it.paramTypeNames == listOf(user.name) }.one("cached user constructor").descriptor)
            put("method:upsert", upsert.descriptor); put("field:repository.dao", dao.descriptor)
            for ((role, owner, cls) in listOf(Triple("shelf.storage", shelf, storage), Triple("shelf.session", shelf, session),
                Triple("storage.repository", storage, repository), Triple("repository.session", repository, session))) {
                put("field:$role", owner.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == cls.name }.descriptor)
            }
            for ((role, cls) in listOf("repository" to repository, "session" to session)) {
                cls.methods.filter { it.isConstructor }.sortedBy { it.descriptor }.forEachIndexed { i, ctor -> put("constructor:$role.$i", ctor.descriptor) }
            }
            val token = prefs.getValue("field:token")
            val clear = session.methods.filter { suspendMethod(it, emptyList()) && flow.writes(it.descriptor).any { w ->
                w.field == token && w.receiver == HostDexCalls.Parameter(0) && w.value == HostDexCalls.Text("")
            } }.map { it.descriptor }.toSet()
            check(clear.isNotEmpty()) { "No session identity reset" }
            val signOut = repository.methods.filter { suspendMethod(it, emptyList()) && it.invokes.any { call -> call.descriptor in clear } &&
                it.invokes.any { call -> call.descriptor == upsert.descriptor } }.one("sign out")
            put("method:signOut", signOut.descriptor)
        }
        private fun preferences(): Map<String, String> = buildMap {
            putAll(prefs)
            val keyCtor = prefs.getValue("constructor:key")
            PREFERENCES.forEach { name ->
                val fields = anchor(name).filter { it.name == "<clinit>" }.flatMap { init ->
                    val allocations = flow.calls(init.descriptor).filter { it.descriptor == keyCtor &&
                        it.arguments.getOrNull(1) == HostDexCalls.Text(name) }.map { it.arguments.first() }.toSet()
                    flow.writes(init.descriptor).filter { it.receiver == null && it.value in allocations }.map { it.field }
                }.distinct()
                val field = requireNotNull(bridge.getFieldData(fields.single()))
                check(Modifier.isStatic(field.modifiers) && field.type.descriptor == prefs.getValue("class:key"))
                put("field:key.$name", field.descriptor)
            }
            val margins = type(P + "repository.core.ReaderMargins")
            put("class:margins", margins.descriptor)
            record("margins", P + "repository.core.ReaderMargins", margins, MARGINS)
            put("method:margins", session.methods.filter { suspendMethod(it, listOf(margins.name)) }.one("atomic margin update").descriptor)
        }
        private fun advanced(): Map<String, String> = buildMap {
            val predicate = anchor("0000-00-00 00:00:00").filter { Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf(info.name) && it.returnTypeName == "boolean" &&
                it.usingFields.map { f -> f.field.descriptor }.containsAll(listOf(
                    base.getValue("field:${P}data.res.user.UserInfo#vipExpireTime"), base.getValue("field:${P}data.res.user.UserInfo#userType"))) }.one("native member predicate")
            val member = predicate.callers.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(user.name) &&
                it.returnTypeName == "boolean" && it.invokes.any { call -> call.descriptor == base.getValue("method:${P}data.db.entity.User#info") } }.one("cached user member predicate")
            val settings = requireNotNull(member.declaredClass)
            val checks = listOf(
                "repository" to repository.methods.filter { suspendMethod(it, listOf(info.name)) && it.invokes.any { call -> call.descriptor == predicate.descriptor } }.one("repository eligibility check"),
                "settings" to settings.methods.filter { suspendMethod(it, listOf(user.name)) && it.invokes.any { call -> call.descriptor == member.descriptor } }.one("settings eligibility check"),
            )
            val function = type("kotlin.jvm.functions.Function2")
            val store = requireNotNull(bridge.getClassData(prefs.getValue("class:store")))
            val edit = session.methods.flatMap { it.invokes }.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 3 &&
                it.paramTypeNames.take(2) == listOf(store.name, function.name) && it.paramTypes.last().isA(continuation) &&
                it.returnTypeName == "java.lang.Object" }.one("DataStore transaction")
            val mutable = requireNotNull(anchor("Do mutate preferences once returned to DataStore.").one("mutable preferences guard").declaredClass)
            put("constructor:mutable", mutable.methods.filter { it.isConstructor &&
                it.paramTypeNames == listOf("java.util.LinkedHashMap", "boolean") }.one("mutable preferences construction").descriptor)
            put("method:storeUpdate", store.methods.filter { !Modifier.isStatic(it.modifiers) &&
                it.paramCount == 2 && it.paramTypeNames.first() == function.name && it.paramTypes.last().isA(continuation) &&
                it.returnTypeName == "java.lang.Object" }.one("DataStore atomic transform").descriptor)
            val key = requireNotNull(bridge.getClassData(prefs.getValue("class:key")))
            val set = mutable.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(key.name, "java.lang.Object") &&
                it.returnTypeName == "void" && it.invokes.any { call -> call.descriptor == "Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" } }.one("preference value write")
            val read = mutable.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(key.name) &&
                it.returnTypeName == "java.lang.Object" && it.invokes.any { call -> call.descriptor in setOf(
                    "Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;", "Ljava/util/LinkedHashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;") } }.one("mutable preference read")
            val remove = mutable.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(key.name) &&
                it.returnTypeName == "void" && it.invokes.any { call -> call.descriptor in setOf(
                    "Ljava/util/Map;->remove(Ljava/lang/Object;)Ljava/lang/Object;", "Ljava/util/LinkedHashMap;->remove(Ljava/lang/Object;)Ljava/lang/Object;") } }.one("preference value removal")
            val baseContinuation = type("kotlin.coroutines.jvm.internal.BaseContinuationImpl")
            val unit = requireNotNull(anchor("kotlin.Unit").filter { it.paramCount == 0 && it.returnTypeName == "java.lang.String" }.one("Unit").declaredClass)
            val suspended = requireNotNull(anchor("COROUTINE_SUSPENDED", "UNDECIDED", "RESUMED").filter { it.name == "<clinit>" }.one("suspension marker").declaredClass)
            for ((role, cls) in listOf("user" to user, "info" to info, "session" to session, "repository" to repository,
                "settings" to settings, "continuation" to continuation, "baseContinuation" to baseContinuation,
                "function" to function, "store" to store, "key" to key, "mutable" to mutable, "unit" to unit, "suspended" to suspended)) put("class:$role", cls.descriptor)
            for ((role, m) in listOf("predicate" to predicate, "member" to member, "edit" to edit, "read" to read, "set" to set, "remove" to remove)) put("method:$role", m.descriptor)
            put("method:invoke", function.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(2) { "java.lang.Object" } &&
                it.returnTypeName == "java.lang.Object" }.one("transaction callback").descriptor)
            put("method:update", prefs.getValue("method:update")); put("method:info", base.getValue("method:${P}data.db.entity.User#info"))
            put("field:info.id", base.getValue("field:${P}data.res.user.UserInfo#id")); put("field:user.token", base.getValue("field:${P}data.db.entity.User#token"))
            put("field:completion", baseContinuation.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == continuation.name }.descriptor)
            put("field:unit", unit.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == unit.name }.descriptor)
            checks.forEach { (role, m) -> put("method:check.$role", m.descriptor) }
            checks.flatMap { it.second.invokes }.filter { it.declaredClassName == session.name &&
                suspendMethod(it, listOf("boolean")) }.distinctBy { it.descriptor }.sortedBy { it.descriptor }
                .forEachIndexed { i, m -> put("method:setter.$i", m.descriptor) }
        }
    }
}
