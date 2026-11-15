package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** SDK roles are identified by protocol messages and their actual call graph. */
internal object HostCrashQueries {
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Crash reporting $role is missing or ambiguous")
    private fun ClassData.isA(name: String): Boolean = this.name == name || superClass?.isA(name) == true
    fun query(bridge: DexKitBridge, apk: String): Map<String, String> {
        fun anchor(vararg text: String) = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*text)))
        val android = anchor("Fatal error during SentryAndroid.init(...)").one("Android SDK init")
        val core = anchor("Error in the 'OptionsConfiguration.configure' callback.", "Sentry has been already initialized. Previous configuration will be overwritten.").one("SDK init")
        val ndk = anchor("A sentry-native setup failure occurred").one("native SDK init")
        val androidNdk = anchor("maven:io.sentry:sentry-android-ndk").filter { it.isMethod &&
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" }.one("Android native SDK init")
        val preloadProvider = anchor("io.sentry.ndk.preload", "SentryNdk.preload() completed").one("native preload provider")
        val preload = preloadProvider.invokes.filter { it.className == ndk.className &&
            Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "void" }.one("native preload")
        val performance = anchor("App. Context from ContentProvider is null", "app_start_profiling_config").filter {
            it.paramCount == 0 && it.returnTypeName == "boolean" && it.declaredClass?.isA("android.content.ContentProvider") == true
        }.one("performance provider")
        val initProviders = android.callers.filter { it.paramCount == 0 && it.returnTypeName == "boolean" &&
            it.declaredClass?.isA("android.content.ContentProvider") == true }.distinctBy { it.descriptor }
        val response = anchor("Envelope sent successfully.", "Error reading and logging the response stream").one("transport response")
        val send = requireNotNull(response.declaredClass).methods.filter {
            !Modifier.isStatic(it.modifiers) && it.paramCount == 1 && it.returnTypeName == response.returnTypeName &&
                it.usingStrings.containsAll(listOf("POST", "application/x-sentry-envelope")) &&
                it.invokes.any { call -> call.descriptor == response.descriptor }
        }.one("envelope transport")
        val success = response.usingFields.map { it.field }.distinctBy { it.descriptor }.single {
            Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) &&
                it.type?.isA(response.returnTypeName) == true
        }
        val predicate = requireNotNull(success.type).methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramCount == 0 && it.returnTypeName == "boolean" && it.invokes.isEmpty() && it.usingFields.isEmpty()
        }.one("successful transport result")
        check(HostDexCalls(apk).literals(predicate.descriptor) == listOf(1L))
        val methods = linkedMapOf("androidInit" to android, "coreInit" to core, "ndkInit" to ndk,
            "androidNdkInit" to androidNdk, "preload" to preload, "performanceProvider" to performance,
            "preloadProvider" to preloadProvider, "send" to send, "successPredicate" to predicate)
        initProviders.sortedBy { it.descriptor }.forEachIndexed { index, method -> methods["initProvider$index"] = method }
        val deopt = methods.filterKeys { it != "successPredicate" }.values.flatMap { it.callers }.filter {
            it.isMethod && !Modifier.isAbstract(it.modifiers) && !Modifier.isNative(it.modifiers)
        }.distinctBy { it.descriptor }.sortedBy { it.descriptor }
        return linkedMapOf<String, String>().apply {
            methods.forEach { (role, method) -> put("method:$role", method.descriptor) }
            put("field:success", success.descriptor)
            put("value:initProviders", initProviders.size.toString())
            put("value:deopt", deopt.size.toString())
            deopt.forEachIndexed { index, method -> put("method:deopt$index", method.descriptor) }
        }
    }
}
