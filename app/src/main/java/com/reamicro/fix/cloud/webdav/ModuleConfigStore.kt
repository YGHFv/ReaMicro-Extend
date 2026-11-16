package com.reamicro.fix.cloud.webdav

import android.content.Context
import android.content.SharedPreferences
import com.reamicro.fix.cloud.api.ApiServerSettingsStore
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.association.provider.ExternalSourceLoader
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** 配置恢复采用先校验、暂存再提交，失败回滚偏好和已移动文件。 */
internal class ModuleConfigStore(private val context: Context) {
    private val root = context.filesDir.canonicalFile
    fun create(id: Long, scopes: Set<ModuleConfigScope>): ByteArray {
        require(id > 0 && scopes.isNotEmpty()) { "请选择至少一个模块同步范围" }
        val stores = JSONObject()
        val files = sortedMapOf<String, ByteArray>()
        val assets = JSONObject()
        fun collectAssets(value: String) {
            // 配置中只收集显式引用且位于本机私有目录的图片和字体。
            val candidates = mutableSetOf(value)
            Regex("\"([^\"\\n]+)\"").findAll(value).forEach { candidates += it.groupValues[1].replace("\\/", "/") }
            for (path in candidates) {
                val file = File(path)
                if (!file.isAbsolute || file.extension.lowercase() !in setOf("png", "jpg", "jpeg", "webp", "ttf", "otf", "woff", "woff2")) continue
                val canonical = file.canonicalFile
                if (!canonical.toPath().startsWith(root.toPath()) || !canonical.isFile) continue
                require(canonical.length() <= ModuleConfigCrypto.MAX_BYTES) { "配置附件过大" }
                val data = canonical.readBytes()
                val name = "assets/${ModuleConfigCrypto.digest(data)}.${file.extension.lowercase()}"
                files[name] = data
                assets.put(path, name)
            }
        }
        for (store in ModuleConfigPolicy.preferenceStores.sorted()) {
            if (store == "reamicro_api_server" && ModuleConfigScope.API !in scopes) continue
            val prefs = context.getSharedPreferences(store, Context.MODE_PRIVATE)
            val values = if (store == "reamicro_api_server") ApiServerSettingsStore { context }.portableSyncValues() else prefs.all
            val json = JSONObject()
            values.toSortedMap().forEach { (key, value) ->
                if (ModuleConfigPolicy.preferenceScope(store, key) in scopes) {
                    json.put(key, ModuleConfigArchive.encodePreference(value))
                    if (value is String) collectAssets(value)
                }
            }
            if (json.length() > 0) stores.put(store, json)
        }
        for (name in listOf("reamicro_ai_apis.json", "reamicro_dictionary_settings.json", "reamicro_image_settings.json")) {
            val file = File(root, name)
            if (ModuleConfigPolicy.fileScope(name) in scopes && file.isFile) {
                require(file.canonicalFile.parentFile == root && file.length() <= ModuleConfigCrypto.MAX_BYTES)
                files["files/$name"] = file.readBytes()
            }
        }
        for (dirName in listOf("reamicro_online_sources", "reamicro_sources")) {
            val dir = File(root, dirName)
            dir.listFiles().orEmpty().filter { it.isFile && ModuleConfigPolicy.fileScope("$dirName/${it.name}") in scopes }
                .sortedBy { it.name }.forEach { file ->
                    require(file.canonicalFile.parentFile == dir.canonicalFile && file.length() <= ModuleConfigCrypto.MAX_BYTES)
                    files["files/$dirName/${file.name}"] = file.readBytes()
                }
        }
        val manifest = JSONObject().put("version", 1).put("account", ModuleConfigCrypto.accountKey(id))
            .put("scopes", JSONArray(scopes.map { it.name }.sorted())).put("preferences", stores).put("assets", assets)
        return ModuleConfigArchive.create(manifest, files)
    }

    fun restore(id: Long, scopes: Set<ModuleConfigScope>, bytes: ByteArray, commit: (() -> Unit) -> Unit): Int {
        require(scopes.isNotEmpty()) { "请选择至少一个模块同步范围" }
        val entries = ModuleConfigArchive.read(bytes)
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        require(manifest.getInt("version") == 1 && manifest.getString("account") == ModuleConfigCrypto.accountKey(id)) {
            "模块配置版本或阅微账号不匹配"
        }
        val savedScopes = manifest.getJSONArray("scopes").let { a ->
            (0 until a.length()).map { ModuleConfigScope.valueOf(a.getString(it)) }.toSet()
        }
        val selected = scopes intersect savedScopes
        require(selected.isNotEmpty()) { "备份与当前所选同步范围没有交集" }
        val assetMap = manifest.getJSONObject("assets")
        val replacements = assetMap.keys().asSequence().associateWith { key ->
            val name = assetMap.getString(key)
            require(name.matches(Regex("assets/[a-f0-9]{64}\\.(png|jpg|jpeg|webp|ttf|otf|woff|woff2)")) && name in entries)
            val body = entries.getValue(name)
            require(ModuleConfigCrypto.digest(body) == name.substringAfter('/').substringBefore('.'))
            File(root, "reamicro_sync_assets/${name.substringAfter('/')}").absolutePath
        }
        fun remap(value: Any): Any {
            if (value !is String) return value
            var result: String = value
            replacements.forEach { (old, next) ->
                if (result == old) result = next
                result = result.replace(JSONObject.quote(old), JSONObject.quote(next))
            }
            return result
        }
        val stores = linkedMapOf<String, Map<String, Any>>()
        val jsonStores = manifest.getJSONObject("preferences")
        val usedAssets = hashSetOf<String>()
        for (store in jsonStores.keys()) {
            require(store in ModuleConfigPolicy.preferenceStores) { "备份包含未知配置存储" }
            val json = jsonStores.getJSONObject(store)
            val values = linkedMapOf<String, Any>()
            for (key in json.keys()) {
                val value = ModuleConfigArchive.decodePreference(json.getJSONObject(key))
                val scope = ModuleConfigPolicy.preferenceScope(store, key)
                require(scope != null && scope in savedScopes) { "备份配置范围不一致" }
                if (scope in selected) {
                    values[key] = remap(value)
                    if (value is String) replacements.keys.filter { value == it || JSONObject.quote(it) in value }
                        .forEach { usedAssets += assetMap.getString(it) }
                }
            }
            if (values.isNotEmpty()) stores[store] = if (store == "reamicro_api_server")
                ApiServerSettingsStore { context }.preparePortableSyncValues(values) else values
        }
        val targetFiles = linkedMapOf<String, ByteArray>()
        entries.filterKeys { it != "manifest.json" }.forEach { (name, body) ->
            when {
                name.startsWith("assets/") -> if (name in usedAssets) targetFiles["reamicro_sync_assets/${name.substringAfter('/')}"] = body
                name.startsWith("files/") -> {
                    val path = name.removePrefix("files/")
                    val scope = ModuleConfigPolicy.fileScope(path)
                    require(scope != null && scope in savedScopes) { "备份文件范围不一致" }
                    if (scope in selected) targetFiles[path] = body
                }
                else -> error("备份包含未知文件")
            }
        }
        val stage = Files.createTempDirectory(context.cacheDir.toPath(), "module-config-restore-").toFile()
        val oldPrefs = stores.keys.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all }
        val moved = arrayListOf<Pair<File, File?>>()
        val writtenPrefs = arrayListOf<String>()
        try {
            targetFiles.forEach { (path, body) -> File(stage, "new/$path").apply { parentFile.mkdirs(); writeBytes(body) } }
            OnlineSourceStore.restoreConfiguration {
                commit {
                    try {
                        targetFiles.keys.forEach { path ->
                            val target = File(root, path).canonicalFile
                            require(target.toPath().startsWith(root.toPath()) && target != root)
                            target.parentFile.mkdirs()
                            val old = if (target.exists()) File(stage, "old/$path").apply {
                                parentFile.mkdirs()
                                Files.move(target.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING)
                            } else null
                            moved += target to old
                            Files.move(File(stage, "new/$path").toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                        stores.forEach { (store, values) ->
                            writtenPrefs += store
                            val editor = context.getSharedPreferences(store, Context.MODE_PRIVATE).edit()
                            values.forEach { (key, value) -> put(editor, key, value) }
                            check(editor.commit()) { "模块配置保存失败" }
                        }
                    } catch (error: Throwable) {
                        writtenPrefs.asReversed().forEach { store ->
                            runCatching {
                                val editor = context.getSharedPreferences(store, Context.MODE_PRIVATE).edit().clear()
                                oldPrefs.getValue(store).forEach { (key, value) -> put(editor, key, requireNotNull(value)) }
                                check(editor.commit())
                            }.onFailure(error::addSuppressed)
                        }
                        moved.asReversed().forEach { (target, old) ->
                            runCatching {
                                if (old != null) Files.move(old.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                else Files.deleteIfExists(target.toPath())
                            }.onFailure(error::addSuppressed)
                        }
                        throw error
                    }
                }
            }
            ExternalSourceLoader.invalidate()
            return stores.values.sumOf { it.size } + targetFiles.size
        } finally {
            // 暂存目录由本次创建，路径不来源于备份内容。
            stage.deleteRecursively()
        }
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("不支持的配置类型")
        }
    }
}
