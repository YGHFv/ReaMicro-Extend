package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Recover protocol types from their actual consumers, without any obfuscated-name hints. */
internal object HostFeatureTypes {
    fun query(bridge: DexKitBridge, known: Map<String, String>): Map<String, String> {
        val output = linkedMapOf<String, String>()
        fun type(logical: String, name: String?) {
            if (name != null && bridge.getClassData(name) != null) output["class:$logical"] = "L${name.replace('.', '/')};"
        }
        fun anchor(vararg strings: String): MethodData? = bridge.findMethod(
            FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*strings))).distinctBy { it.descriptor }.singleOrNull()
        fun existing(logical: String): ClassData? = (output["class:$logical"] ?: known["class:$logical"])?.let(bridge::getClassData)
        val content = anchor("翻页方式", "查看插图", "覆盖", "平移", "滚动")
        val title = content?.invokes?.filter { it.paramCount == 4 && it.paramTypeNames.first() == "java.lang.String" &&
            it.paramTypeNames.last() == "int" && it.returnTypeName == "void" }?.distinctBy { it.descriptor }?.singleOrNull()
        if (title != null) {
            type("androidx.compose.runtime.Composer", title.paramTypeNames[2])
            type("androidx.compose.ui.Modifier", title.paramTypeNames[1])
            val button = content.invokes.filter { it.paramCount == 5 && it.paramTypeNames.take(2) == listOf("java.lang.String", "boolean") &&
                it.paramTypeNames[3] == title.paramTypeNames[2] }.distinctBy { it.descriptor }.singleOrNull()
            type("kotlin.jvm.functions.Function0", button?.paramTypeNames?.get(2))
        }
        type("kotlin.jvm.functions.Function1", "kotlin.jvm.functions.Function1")
        existing("ReaMicroHost.ComposableLambda")?.interfaces?.forEach { iface ->
            val method = iface.methods.singleOrNull { it.isMethod && it.paramCount in 2..3 &&
                it.paramTypeNames.all { p -> p == "java.lang.Object" } && it.returnTypeName == "java.lang.Object" }
            if (method != null) type("kotlin.jvm.functions.Function${method.paramCount}", iface.name)
        }
        anchor("MutableState(value=", ")@")?.declaredClass?.let { concrete ->
            val interfaces = linkedMapOf<String, ClassData>()
            fun addInterfaces(type: ClassData) {
                type.interfaces.forEach { if (interfaces.putIfAbsent(it.name, it) == null) addInterfaces(it) }
            }
            generateSequence(concrete) { it.superClass }.forEach(::addInterfaces)
            val state = interfaces.values.singleOrNull { it.methods.any { m ->
                m.name == "setValue" && m.paramTypeNames == listOf("java.lang.Object") } &&
                it.interfaces.any { parent -> parent.methods.any { m -> m.name == "getValue" && m.paramCount == 0 } } }
            type("androidx.compose.runtime.MutableState", state?.name)
            type("androidx.compose.runtime.State", state?.interfaces?.singleOrNull()?.name)
        }
        anchor("create(Continuation) has not been overridden")?.let { create ->
            type("kotlin.coroutines.Continuation", create.paramTypeNames.singleOrNull())
            type("kotlin.coroutines.jvm.internal.BaseContinuationImpl", create.className)
        }
        existing("kotlin.coroutines.Continuation")?.methods?.filter { it.paramCount == 0 && it.returnTypeName != "void" }
            ?.singleOrNull()?.let { type("kotlin.coroutines.CoroutineContext", it.returnTypeName) }
        type("app.zhendong.reamicro.data.db.AppDatabase", "app.zhendong.reamicro.data.db.AppDatabase")
        anchor("SettingsUIState(currentUser=")?.let { type("app.zhendong.reamicro.ui.setting.SettingsUIState", it.className) }
        val workState = existing("app.zhendong.reamicro.arch.WorkState")
        if (workState != null) {
            // Follow injected dependencies and the locked state-update contract.
            // Import services can add constructor parameters without moving this role.
            val tracker = existing("app.zhendong.reamicro.arch.WorkerManager")?.methods.orEmpty()
                .filter { it.isConstructor }.flatMap { it.paramTypes }.filter { dependency ->
                    dependency.methods.any { method ->
                        method.isMethod && !Modifier.isStatic(method.modifiers) &&
                            method.paramTypeNames == listOf("java.lang.String", workState.name) &&
                            method.returnTypeName == "void" && method.invokes.any {
                                it.descriptor == "Ljava/util/concurrent/locks/ReentrantLock;->lock()V"
                            }
                    }
                }.distinctBy { it.name }.singleOrNull()
            type("app.zhendong.reamicro.arch.WorkTracker", tracker?.name)
        }

        val scope = anchor("androidx.lifecycle.viewmodel.internal.ViewModelCoroutineScope.JOB_KEY")
            ?.takeIf { Modifier.isStatic(it.modifiers) && it.paramCount == 1 }
        val lifecycle = scope?.paramTypes?.singleOrNull()
        type("androidx.lifecycle.ViewModel", lifecycle?.name)
        val reader = anchor("ReaderViewModel", "摘抄内容: ")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.paramCount == 1 && it.returnTypeName == "void" }
            ?.declaredClass
        val mvi = reader?.superClass?.takeIf { parent ->
            lifecycle != null && generateSequence(parent) { it.superClass }.any { it.name == lifecycle.name }
        }
        type("app.zhendong.reamicro.arch.MVIViewModel", mvi?.name)
        if (mvi != null) {
            // Follow the base state initializer override to its concrete state construction.
            val initializeState = mvi.methods.singleOrNull {
                it.isMethod && Modifier.isAbstract(it.modifiers) && it.paramCount == 0 && it.returnTypeName != "void"
            }
            listOf(
                Triple("BookPublishUIState(book=", "app.zhendong.reamicro.ui.book.BookPublishUIState", "app.zhendong.reamicro.ui.book.BookPublishViewModel"),
                Triple("BookDetailsUiState(barcode=", "app.zhendong.reamicro.ui.book.BookDetailsUiState", "app.zhendong.reamicro.ui.book.BookDetailsViewModel"),
                Triple("BookOverviewUiState(book=", "app.zhendong.reamicro.ui.home.BookOverviewUiState", "app.zhendong.reamicro.ui.home.BookOverviewViewModel"),
            ).forEach { (text, stateName, vmName) ->
                anchor(text)?.declaredClass?.let { state ->
                    type(stateName, state.name)
                    val initializers = state.methods.filter { it.isConstructor }.flatMap { it.callers }.filter {
                        it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
                            it.declaredClass?.superClass?.name == mvi.name &&
                            initializeState != null && it.name == initializeState.name &&
                            it.returnTypeName == initializeState.returnTypeName
                    }.distinctBy { it.descriptor }
                    type(vmName, initializers.singleOrNull()?.className)
                }
            }
        }
        backupName(bridge)?.let { type("app.zhendong.reamicro.constants.BackupType", it.className) }
        epubImport(bridge, existing("okio.Path")?.name)?.let { method ->
            // R8 can relocate each facade method independently; this owner belongs to import only.
            type("app.zhendong.reamicro.arch.EpubFileManager", method.className)
            type("kotlin.Pair", method.returnTypeName)
        }
        return output
    }

    fun queryMembers(bridge: DexKitBridge, known: Map<String, String>, group: String): Map<String, String> {
        val output = linkedMapOf<String, String>()
        fun existing(logical: String) = known["class:$logical"]?.let(bridge::getClassData)
        fun save(owner: String, name: String, method: MethodData?) {
            if (method != null && HostSemanticQueries.group(owner) == group) output["method:$owner#$name"] = method.descriptor
        }
        if (group == "runtime") {
            val owner = "androidx.lifecycle.ViewModel"
            existing(owner)?.let { lifecycle ->
                val clear = lifecycle.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                    Modifier.isFinal(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "void" &&
                    it.invokes.any { called -> called.descriptor == "Ljava/util/Set;->clear()V" } }
                    .singleOrNull()
                save(owner, "clear\$lifecycle_viewmodel", clear)
                val onCleared = clear?.invokes?.filter { it.declaredClassName == lifecycle.name &&
                    it.isMethod && !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) &&
                    it.paramCount == 0 && it.returnTypeName == "void" }
                    ?.distinctBy { it.descriptor }?.singleOrNull()
                save(owner, "onCleared", onCleared)
            }
        }
        if (group == "library") {
            val function0 = existing("kotlin.jvm.functions.Function0")?.name
            val composer = existing("androidx.compose.runtime.Composer")?.name
            if (function0 != null && composer != null) {
                val bookshelf = existing("app.zhendong.reamicro.ui.home.BookshelfScreenKt")?.name
                val empty = bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().usingEqStrings("Outlined.DataEmpty").paramCount(0)))
                    .flatMap { it.callers }.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                        it.paramTypeNames == listOf(function0, composer, "int") &&
                        (bookshelf == null || it.declaredClassName == bookshelf) }.distinctBy { it.descriptor }.singleOrNull()
                save("app.zhendong.reamicro.ui.home.BookshelfScreenKt", "DataEmptyBox", empty)
            }
            val owner = "app.zhendong.reamicro.arch.MVIViewModel"
            existing(owner)?.let { mvi ->
                val flow = existing("kotlinx.coroutines.flow.StateFlowImpl")?.name
                val state = mvi.methods.filter { it.isMethod && Modifier.isAbstract(it.modifiers) && it.paramCount == 0 }
                    .singleOrNull()?.returnTypeName
                val current = mvi.methods.filter { it.isMethod && Modifier.isFinal(it.modifiers) &&
                    !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == state &&
                    it.invokes.any { called -> called.name == "getValue" && called.paramCount == 0 &&
                        called.returnTypeName == "java.lang.Object" } }.singleOrNull()
                save(owner, "getCurrentState", current)
                val update = mvi.methods.filter { it.isMethod && Modifier.isFinal(it.modifiers) &&
                    !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("kotlin.jvm.functions.Function1") &&
                    it.returnTypeName == "void" &&
                    it.invokes.any { called -> called.descriptor == "Lkotlin/jvm/functions/Function1;->invoke(Ljava/lang/Object;)Ljava/lang/Object;" } &&
                    it.invokes.any { called -> called.declaredClassName == flow &&
                        called.paramTypeNames == List(2) { "java.lang.Object" } && called.returnTypeName == "boolean" }
                }.singleOrNull()
                save(owner, "updateUiState", update)
                val dispatch = existing("app.zhendong.reamicro.ui.reader.ReaderViewModel")?.methods?.filter {
                    it.usingStrings.containsAll(listOf("ReaderViewModel", "摘抄内容: ")) && it.paramCount == 1
                }?.singleOrNull()
                val intent = mvi.methods.filter { it.isMethod && Modifier.isFinal(it.modifiers) &&
                    !Modifier.isStatic(it.modifiers) && it.paramTypeNames == dispatch?.paramTypeNames &&
                    it.returnTypeName == "void" }.singleOrNull()
                save(owner, "intent", intent)
                update?.usingFields?.map { it.field }?.filter {
                    it.declaredClassName == mvi.name && !Modifier.isStatic(it.modifiers) && it.typeName == flow
                }?.distinctBy { it.descriptor }?.singleOrNull()?.let { output["field:$owner#_uiState"] = it.descriptor }
                current?.usingFields?.map { it.field }?.filter {
                    it.declaredClassName == mvi.name && !Modifier.isStatic(it.modifiers)
                }?.distinctBy { it.descriptor }?.singleOrNull()?.let { output["field:$owner#uiState"] = it.descriptor }
                val sharedFlow = existing("kotlinx.coroutines.flow.SharedFlowImpl")?.name
                val emitAction = mvi.methods.filter { it.isMethod && Modifier.isFinal(it.modifiers) &&
                    !Modifier.isStatic(it.modifiers) && it.paramCount == 1 && it.returnTypeName == "void" &&
                    it.invokes.any { called -> called.declaredClassName == sharedFlow &&
                        called.paramTypeNames == listOf("java.lang.Object") && called.returnTypeName == "boolean" }
                }.singleOrNull()
                save(owner, "emitAction", emitAction)
            }
            save("app.zhendong.reamicro.constants.BackupType", "getName", backupName(bridge))
            save("app.zhendong.reamicro.arch.EpubFileManager", "import", epubImport(bridge, existing("okio.Path")?.name))
        }
        return output
    }

    // Some hosts inline this entire function. An absent standalone method must stay unresolved.
    private fun backupName(bridge: DexKitBridge): MethodData? = bridge.findMethod(FindMethod.create().matcher(
        MethodMatcher.create().usingEqStrings("百度网盘", "阿里云盘", "115网盘", "本地存储")
            .paramTypes("java.lang.Integer").returnType("java.lang.String")))
        .filter { Modifier.isStatic(it.modifiers) }.distinctBy { it.descriptor }.singleOrNull()

    private fun epubImport(bridge: DexKitBridge, path: String?): MethodData? {
        if (path == null) return null
        return bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .usingEqStrings("manifest > item", "[src],[href],[data-src],[xlink:href]", "~slim.").paramCount(3)))
            .filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames.take(2) == listOf(path, path) && isPair(it.returnType) }
            .distinctBy { it.descriptor }.singleOrNull()
    }

    private fun isPair(type: ClassData?): Boolean = type != null &&
        type.interfaces.any { it.name == "java.io.Serializable" } &&
        type.fields.filter { !Modifier.isStatic(it.modifiers) }.let { fields ->
            fields.size == 2 && fields.all { Modifier.isFinal(it.modifiers) && it.typeName == "java.lang.Object" }
        } && type.methods.any { it.isConstructor && it.paramTypeNames == List(2) { "java.lang.Object" } } &&
        type.methods.any { it.name == "toString" && it.usingStrings.containsAll(listOf("(", ", ")) }
}
