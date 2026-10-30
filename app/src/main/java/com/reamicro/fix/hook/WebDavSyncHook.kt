package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.reamicro.fix.cloud.webdav.B2HostHttp
import com.reamicro.fix.cloud.webdav.CleartextScope
import com.reamicro.fix.cloud.webdav.WebDavSyncProtocol
import com.reamicro.fix.cloud.webdav.WebDavSyncTransport
import com.reamicro.fix.core.B2NativeUi
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.ui.ModuleUiKit
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 2.3.3 正式版经 REA/MT 双重核实的同步存储接口；不重写书架合并算法。 */
internal class WebDavSyncHook(
    private val loader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot,
    private val drive: WebDavDriveHook,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ReaMicroWebDavSync").apply { isDaemon = true } }
    private val configuring = AtomicBoolean()
    private var session = WeakReference<Any>(null)
    private val extraRow = ThreadLocal<Boolean>()
    private val ui = B2NativeUi(loader)
    private val http by lazy {
        B2HostHttp(loader, object : CleartextScope {
            override fun <T> run(block: () -> T): T = drive.withWebDavCleartextAllowed(block)
        })
    }
    private val configType by lazy { raw("dd9") }
    private val transportType by lazy { raw("uk6") }
    private val unit by lazy { drive.targetUnit() }

    private fun raw(name: String) = Class.forName(name, false, loader)
    private fun method(owner: String, name: String, count: Int): Method =
        raw(owner).declaredMethods.filter { it.name == name && it.parameterCount == count }
            .singleOrNull()?.apply { isAccessible = true }
            ?: error("WebDAV sync ABI missing: $owner.$name/$count")
    private fun field(value: Any, name: String): Any? =
        value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)

    fun install() {
        check(HostSymbols.profileId == "2.3.3")
        check(raw("bf9").declaredMethods.count { !java.lang.reflect.Modifier.isStatic(it.modifiers) } == 8)
        check(configType.declaredConstructors.any { it.parameterCount == 7 && it.parameterTypes[0] == raw("he9") })
        XposedBridge.hookAllConstructors(raw("w1a"), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                session = WeakReference(param.thisObject)
            }
        })
        hookEntry()
        hookTransport()
        hookLocalConfigSave()
        // 关闭扩展后暂停标记配置，不能落回 AWS 签名并把 WebDAV 密码发给别的服务。
        XposedBridge.hookMethod(method("dd9", "b", 0), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (marked(param.thisObject) && !settingsProvider().canRunWebDavSync) param.result = false
            }
        })
        XposedBridge.logAlways("ReaMicro WebDAV shelf sync installed: native manifest/merge/transport")
    }

    private fun hookEntry() {
        val other = method("zua", "b", 3)
        XposedBridge.hookMethod(other, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (extraRow.get() == true || !settingsProvider().canRunWebDavSync) return
                val composer = param.args[1] ?: return
                extraRow.set(true)
                try {
                    val callback = drive.functionProxy("WebDavSyncConfigure", "kotlin.jvm.functions.Function0") {
                        showConfiguration()
                        unit
                    }
                    other.invoke(null, callback, composer, 0)
                    ui.spacer(ui.height(ui.modifier(), ui.dp(12)), composer)
                } finally {
                    extraRow.remove()
                }
            }
        })
        XposedBridge.hookMethod(method("z6b", "b", 20), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val label = param.args[0] as? String ?: return
                if (extraRow.get() == true && label == "其他 S3 服务") param.args[0] = "使用 WebDAV"
            }
        })
        XposedBridge.hookMethod(method("vl4", "a", 7), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (extraRow.get() == true && param.args[1] == "其他 S3 服务") param.args[1] = "使用 WebDAV"
            }
        })
        val card = method("p1e", "a", 9)
        XposedBridge.hookMethod(card, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (extraRow.get() != true) return
                val original = requireNotNull(param.args[5])
                param.args[5] = ui.lambda(0x524d5744,
                    drive.functionProxy("WebDavSyncRowContent", "kotlin.jvm.functions.Function3") { args ->
                        val previous = extraRow.get()
                        extraRow.set(true)
                        try {
                            loader.hostClass("kotlin.jvm.functions.Function3").hostMethod(
                                "invoke", Any::class.java, Any::class.java, Any::class.java,
                            ).invoke(original, *requireNotNull(args))
                        } finally {
                            if (previous == null) extraRow.remove() else extraRow.set(previous)
                        }
                        unit
                    })
            }
        })
        listOf(other, card, method("ip1", "r", 3)).forEach(XposedBridge::deoptimize)
    }

    private fun marked(config: Any?): Boolean = configType.isInstance(config) &&
        field(requireNotNull(config), "c") == WebDavSyncProtocol.BUCKET

    private fun transport(config: Any): WebDavSyncTransport {
        val username = field(config, "e") as String
        val password = field(config, "f") as String
        val url = field(config, "b") as String
        drive.rememberCleartextHost(url)
        return WebDavSyncTransport(http, url,
            if (username == WebDavSyncProtocol.ANONYMOUS) "" else username,
            if (password == WebDavSyncProtocol.ANONYMOUS) "" else password,
        ) { code ->
            throw raw("za9").getConstructor(Int::class.javaPrimitiveType, String::class.java)
                .newInstance(code, "WebDAV 同步请求失败：HTTP $code") as Throwable
        }
    }

    private fun suspendOperation(param: XC_MethodHook.MethodHookParam, operation: () -> Any?) {
        val continuation = requireNotNull(param.args.last())
        worker.execute {
            val result = runCatching {
                HostCoroutines.runBlocking(loader) { operation() }
            }.getOrElse { error ->
                loader.hostClass("kotlin.ResultKt").hostMethod("createFailure", Throwable::class.java).invoke(null, error)
            }
            runCatching {
                loader.hostClass("kotlin.coroutines.Continuation").hostMethod("resumeWith", Any::class.java)
                    .invoke(continuation, result)
            }.onFailure { XposedBridge.logError("ReaMicro WebDAV sync continuation failed", it) }
        }
        param.result = loader.hostClass("kotlin.coroutines.intrinsics.CoroutineSingletons")
            .enumConstants!!.single { (it as Enum<*>).name == "COROUTINE_SUSPENDED" }
    }

    private fun hookLocalConfigSave() {
        // 原生 S3 编辑页面也可能保存当前配置，不能把标记的 WebDAV 凭据上传到账号。
        val save = method("ad9", "c", 3)
        XposedBridge.hookMethod(save, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = param.args[1] ?: return
                if (!marked(config)) return
                val current = requireNotNull(field(requireNotNull(param.thisObject), "b"))
                suspendOperation(param) {
                    check(settingsProvider().canRunWebDavSync) { "同步扩展已关闭" }
                    val names = listOf("provider", "endpoint", "bucket", "region", "access_key", "secret_key", "prefix")
                    val values = names.mapIndexed { index, name ->
                        val value = field(config, ('a' + index).toString())
                        "s3_sync_$name" to if (value is Enum<*>) value.name else value
                    }.toMap() + ("s3_sync_cloud_config_enabled" to false)
                    saveValues(current, values)
                    unit
                }
            }
        })
        XposedBridge.deoptimize(save)
    }

    private fun hookTransport() {
        val api = raw("bf9")
        api.declaredMethods.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach { contract ->
            val implementation = transportType.getDeclaredMethod(contract.name, *contract.parameterTypes)
            XposedBridge.hookMethod(implementation, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val target = param.thisObject ?: return
                    val config = field(target, "c") ?: return
                    if (!marked(config)) return
                    check(settingsProvider().canRunWebDavSync) { "同步扩展已关闭" }
                    val args = param.args.clone()
                    // 使用宿主协程恢复协议，不在主线程执行同步网络请求。
                    suspendOperation(param) {
                        check(settingsProvider().canRunWebDavSync) { "同步扩展已关闭" }
                        execute(contract.name, transport(config), args)
                    }
                }
            })
            XposedBridge.deoptimize(implementation)
        }
    }

    private fun execute(name: String, remote: WebDavSyncTransport, args: Array<Any?>): Any? {
        val key = args[0] as String
        return when (name) {
            "c" -> remote.get(key)?.let { response ->
                raw("gb9").getConstructor(String::class.java, ByteArray::class.java)
                    .newInstance(response.headers["ETag"], requireNotNull(response.bodyBytes))
            }
            "f" -> remote.head(key)?.let { response ->
                val size = response.headers["Content-Length"]?.toLongOrNull() ?: error("WebDAV 缺少对象大小")
                raw("hb9").getConstructor(Long::class.javaPrimitiveType, String::class.java)
                    .newInstance(size, response.headers["ETag"])
            }
            "i" -> remote.get(key)?.bodyBytes ?: throw raw("za9")
                .getConstructor(Int::class.javaPrimitiveType, String::class.java)
                .newInstance(404, "WebDAV 同步对象不存在") as Throwable
            "d" -> { remote.delete(key); unit }
            "e", "g" -> {
                val bytes = args[1] as ByteArray
                remote.put(key, bytes, null, args[2] as String, args[3] as? String)
                if (name == "g") progress(args[4], bytes.size.toLong())
                unit
            }
            "b" -> {
                val file = raw("dn7").getMethod("toFile").invoke(args[1]) as File
                remote.put(key, null, file, args[2] as String, args[3] as? String)
                progress(args[4], file.length())
                unit
            }
            "h" -> {
                val file = raw("dn7").getMethod("toFile").invoke(args[1]) as File
                remote.download(key, file)
                progress(args[2], file.length())
                unit
            }
            else -> error("未验证的 WebDAV 同步操作：$name")
        }
    }

    private fun progress(callback: Any?, size: Long) {
        if (callback == null) return
        val invoke = loader.hostClass("kotlin.jvm.functions.Function3").hostMethod(
            "invoke", Any::class.java, Any::class.java, Any::class.java,
        )
        // Function3 是 suspend (copied, total, continuation)，第三参不是进度值。
        HostCoroutines.runBlocking(loader) { continuation ->
            invoke.invoke(callback, size, size, continuation)
        }
    }

    private fun preferences(session: Any): Any {
        val store = requireNotNull(field(session, "a"))
        val flow = XposedHelpers.callMethod(store, "getData")
        return requireNotNull(HostCoroutines.invokeTimed(loader,
            method("avd", "c", 2), null, 15_000L, flow))
    }

    private fun nativeValues(session: Any): Map<String, Any?> {
        val prefs = preferences(session)
        return KEYS.associateWith { key ->
            val k = raw("cx7").getConstructor(String::class.java).newInstance(key)
            method("tn6", "c", 1).invoke(prefs, k)
        }
    }

    private fun saveValues(session: Any, values: Map<String, Any?>) {
        val change = drive.functionProxy("WebDavSyncPreferences", "kotlin.jvm.functions.Function2") { args ->
            val prefs = requireNotNull(args?.getOrNull(0))
            val scopeChanged = listOf("provider", "endpoint", "bucket", "prefix").any { name ->
                val keyName = "s3_sync_$name"
                val key = raw("cx7").getConstructor(String::class.java).newInstance(keyName)
                values.containsKey(keyName) && method("tn6", "c", 1).invoke(prefs, key) != values[keyName]
            }
            // 与原生 Session 保存一致：切换存储根后移除旧基线，避免按旧远端状态误判删除。
            if (scopeChanged && !values.containsKey("s3_sync_baseline_cbor")) {
                method("tn6", "d", 1).invoke(prefs,
                    raw("cx7").getConstructor(String::class.java).newInstance("s3_sync_baseline_cbor"))
            }
            values.forEach { (name, value) ->
                val key = raw("cx7").getConstructor(String::class.java).newInstance(name)
                if (value == null) method("tn6", "d", 1).invoke(prefs, key)
                else method("tn6", "e", 2).invoke(prefs, key, value)
            }
            prefs
        }
        HostCoroutines.invokeTimed(loader, method("nzd", "e", 3), null, 15_000L,
            requireNotNull(field(session, "a")), change)
        val actual = nativeValues(session)
        check(values.all {
            val read = actual[it.key]
            if (read is ByteArray && it.value is ByteArray) read.contentEquals(it.value as ByteArray) else read == it.value
        }) { "WebDAV 同步配置回读不一致" }
    }

    private fun showConfiguration() {
        val activity = activityProvider() ?: return
        val current = session.get() ?: return toast("同步设置尚未就绪")
        if (!configuring.compareAndSet(false, true)) return
        worker.execute {
            runCatching { nativeValues(current) }.onSuccess { values ->
                main.post { if (!activity.isFinishing) renderConfiguration(activity, current, values) else configuring.set(false) }
            }.onFailure { configuring.set(false); toast("读取同步设置失败") }
        }
    }

    private fun renderConfiguration(activity: Activity, current: Any, values: Map<String, Any?>) {
        val kit = ModuleUiKit(activity)
        val dialog = Dialog(activity)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val connected = values["s3_sync_bucket"] == WebDavSyncProtocol.BUCKET
        val fields = LinkedHashMap<String, EditText>()
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            background = kit.rounded(kit.palette.rowBackground, 18f)
            addView(kit.textView("WebDAV 同步", 20f, kit.palette.title, true))
            addView(kit.textView("同步书架、书籍、封面与阅读数据。配置仅保存在本机，不上传到阅微账号；其他设备需分别配置。", 13f, kit.palette.body))
            listOf("地址" to "s3_sync_endpoint", "用户名" to "s3_sync_access_key", "密码" to "s3_sync_secret_key").forEach { (label, key) ->
                addView(kit.textView(label, 14f, kit.palette.title))
                val stored = if (connected) values[key] as? String ?: "" else ""
                val edit = EditText(activity).apply {
                    setSingleLine(true)
                    inputType = if (label == "密码") InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setText(stored.takeUnless { it == WebDavSyncProtocol.ANONYMOUS } ?: "")
                    setTextColor(kit.palette.body)
                    setHintTextColor(kit.palette.body)
                    hint = if (label == "地址") "https://example.com/dav/同步目录" else "匿名服务可留空"
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    background = kit.rounded(kit.palette.pageBackground, 8f)
                }
                fields[label] = edit
                addView(edit)
            }
        }
        val busy = AtomicBoolean()
        val dismissed = AtomicBoolean()
        val save = kit.button("验证并启用") {
            if (busy.get()) return@button
            val url = runCatching { WebDavSyncProtocol.baseUrl(fields.getValue("地址").text.toString()) }
                .getOrElse { toast(it.message ?: "WebDAV 地址无效"); return@button }
            val username = fields.getValue("用户名").text.toString()
            val password = fields.getValue("密码").text.toString()
            if (username.contains(':') || username.contains('\n')) { toast("用户名不能包含冒号或换行"); return@button }
            val next = mapOf(
                "s3_sync_provider" to "S3", "s3_sync_endpoint" to url,
                "s3_sync_bucket" to WebDavSyncProtocol.BUCKET, "s3_sync_region" to "auto",
                "s3_sync_access_key" to username.ifEmpty { WebDavSyncProtocol.ANONYMOUS },
                "s3_sync_secret_key" to password.ifEmpty { WebDavSyncProtocol.ANONYMOUS },
                "s3_sync_prefix" to "reamicro", "s3_sync_cloud_config_enabled" to false,
            )
            busy.set(true)
            worker.execute {
                runCatching {
                    check(settingsProvider().canRunWebDavSync) { "同步扩展已关闭" }
                    val config = configType.getConstructor(raw("he9"), *Array(6) { String::class.java })
                        .newInstance(raw("he9").enumConstants!![0], url, WebDavSyncProtocol.BUCKET, "auto",
                            next["s3_sync_access_key"], next["s3_sync_secret_key"], "reamicro")
                    transport(config).verify()
                    check(!dismissed.get()) { "配置已取消，未保存" }
                    check(session.get() === current) { "账号已切换，未保存" }
                    // 用户取消或探测失败均不覆盖原有 S3 配置。
                    check(settingsProvider().canRunWebDavSync) { "同步扩展已关闭" }
                    try {
                        saveValues(current, next)
                    } catch (error: Throwable) {
                        runCatching { saveValues(current, values) }.onFailure(error::addSuppressed)
                        throw error
                    }
                }.onSuccess { main.post { dialog.dismiss(); toast("WebDAV 已启用，返回书架即可使用原生同步") } }
                    .onFailure { error -> toast(error.message ?: "WebDAV 验证失败，原配置未修改") }
                busy.set(false)
            }
        }
        form.addView(save)
        form.addView(kit.button("取消", ModuleUiKit.Role.Neutral) { dialog.dismiss() })
        dialog.setContentView(ScrollView(activity).apply { addView(form) })
        dialog.setOnDismissListener { dismissed.set(true); configuring.set(false) }
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * .92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun toast(message: String) {
        main.post { activityProvider()?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() } }
    }

    private companion object {
        val KEYS = listOf("s3_sync_provider", "s3_sync_endpoint", "s3_sync_bucket", "s3_sync_region",
            "s3_sync_access_key", "s3_sync_secret_key", "s3_sync_prefix", "s3_sync_cloud_config_enabled",
            "s3_sync_baseline_cbor")
    }
}
