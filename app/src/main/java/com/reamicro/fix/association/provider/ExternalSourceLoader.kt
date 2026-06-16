package com.reamicro.fix.association.provider

import android.content.Context
import dalvik.system.DexClassLoader
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.json.JSONObject

object ExternalSourceLoader {
    private const val SOURCE_DIR = "reamicro_sources"
    private const val BUNDLED_SOURCE_ASSET_DIR = "reamicro_sources"
    private const val MODULE_PACKAGE_NAME = "com.reamicro.fix"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val API_VERSION = 1
    private val sourceExtensions = setOf("rmsource", "apk", "jar", "dex")

    @Volatile private var cachedKey: String = ""
    @Volatile private var cachedEntries: List<Any> = emptyList()
    @Volatile private var moduleApkPath: String? = null

    fun configure(moduleApkPath: String?) {
        if (!moduleApkPath.isNullOrBlank()) {
            this.moduleApkPath = moduleApkPath
        }
    }

    fun load(context: Context?): List<BookAssociationSearchProvider> =
        loadEntries(context).filterIsInstance<BookAssociationSearchProvider>()
            .distinctBy { it.source.id }

    fun loadFeatures(context: Context?): List<ExternalFeatureProvider> =
        loadEntries(context).filterIsInstance<ExternalFeatureProvider>()
            .distinctBy { it.id }

    private fun loadEntries(context: Context?): List<Any> {
        context ?: return emptyList()
        val sourceDir = File(context.filesDir, SOURCE_DIR)
        syncBundledSources(context, sourceDir)
        if (!sourceDir.isDirectory) return emptyList()
        val files = sourceDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in sourceExtensions }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) return emptyList()

        val key = files.joinToString("|") { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
        cachedEntries.takeIf { key == cachedKey }?.let { return it }

        val optimizedDir = File(context.codeCacheDir, "reamicro_source_odex").apply { mkdirs() }
        val parentClassLoader = ExternalSourceLoader::class.java.classLoader ?: context.classLoader
        val providers = files.mapNotNull { file -> loadProvider(file, optimizedDir, parentClassLoader) }
        cachedKey = key
        cachedEntries = providers
        return providers
    }

    private fun syncBundledSources(context: Context, sourceDir: File) {
        if (syncBundledSourcesFromModuleApk(sourceDir)) return

        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrElse {
            log("bundled source sync skipped: module context unavailable: ${it.message}")
            return
        }
        val names = runCatching {
            moduleContext.assets.list(BUNDLED_SOURCE_ASSET_DIR)?.toList().orEmpty()
        }.getOrElse {
            log("bundled source list failed: ${it.message}")
            emptyList()
        }.filter { it.substringAfterLast('.', "").lowercase() in sourceExtensions }
        if (names.isEmpty()) return

        val appUpdatedAt = runCatching {
            context.packageManager.getPackageInfo(MODULE_PACKAGE_NAME, 0).lastUpdateTime
        }.getOrDefault(System.currentTimeMillis())
        if (!sourceDir.isDirectory && !sourceDir.mkdirs()) {
            log("bundled source sync skipped: cannot create ${sourceDir.absolutePath}")
            return
        }

        names.forEach { name ->
            runCatching {
                val target = File(sourceDir, name)
                if (target.isFile && target.lastModified() > appUpdatedAt) return@forEach
                val bytes = moduleContext.assets.open("$BUNDLED_SOURCE_ASSET_DIR/$name").use { it.readBytes() }
                writeBundledSourceIfNeeded(target, bytes, appUpdatedAt, name)
            }.onFailure {
                log("bundled source $name sync failed: ${it.message}")
            }
        }
    }

    private fun syncBundledSourcesFromModuleApk(sourceDir: File): Boolean {
        val apkFile = File(moduleApkPath ?: return false)
        if (!apkFile.isFile) {
            log("bundled source APK unavailable: ${apkFile.absolutePath}")
            return false
        }
        val appUpdatedAt = apkFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        return runCatching {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { entry -> entry.isBundledSourceAsset() }
                    .sortedBy { it.name }
                    .toList()
                if (entries.isEmpty()) return true
                if (!sourceDir.isDirectory && !sourceDir.mkdirs()) {
                    log("bundled source sync skipped: cannot create ${sourceDir.absolutePath}")
                    return true
                }
                entries.forEach { entry ->
                    val name = entry.name.substringAfterLast('/')
                    val target = File(sourceDir, name)
                    if (target.isFile && target.lastModified() > appUpdatedAt) return@forEach
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    writeBundledSourceIfNeeded(target, bytes, appUpdatedAt, name)
                }
            }
            true
        }.getOrElse {
            log("bundled source APK sync failed: ${it.message}")
            false
        }
    }

    private fun ZipEntry.isBundledSourceAsset(): Boolean {
        if (isDirectory) return false
        val prefix = "assets/$BUNDLED_SOURCE_ASSET_DIR/"
        if (!name.startsWith(prefix)) return false
        val fileName = name.substringAfterLast('/')
        if (fileName.isBlank() || fileName.contains("..")) return false
        return fileName.substringAfterLast('.', "").lowercase() in sourceExtensions
    }

    private fun writeBundledSourceIfNeeded(
        target: File,
        bytes: ByteArray,
        appUpdatedAt: Long,
        name: String,
    ) {
        val shouldWrite = !target.isFile ||
            target.length() != bytes.size.toLong() ||
            target.lastModified() < appUpdatedAt
        if (!shouldWrite) return
        if (target.exists()) target.setWritable(true)
        FileOutputStream(target).use { it.write(bytes) }
        target.setReadOnly()
        target.setLastModified(appUpdatedAt)
        log("bundled source synced $name")
    }

    private fun loadProvider(
        file: File,
        optimizedDir: File,
        parentClassLoader: ClassLoader,
    ): Any? = runCatching {
        file.setReadOnly()
        val manifest = readManifest(file)
        val apiVersion = manifest.optInt("apiVersion", -1)
        if (apiVersion != API_VERSION) {
            log("skip ${file.name}: unsupported apiVersion=$apiVersion")
            return@runCatching null
        }
        val entryClass = manifest.optString("entryClass").trim()
        if (entryClass.isBlank()) {
            log("skip ${file.name}: missing entryClass")
            return@runCatching null
        }
        val dexPath = materializeDexPath(file, optimizedDir)
        val loader = DexClassLoader(
            dexPath.absolutePath,
            optimizedDir.absolutePath,
            null,
            parentClassLoader,
        )
        val instance = loader.loadClass(entryClass)
            .getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
        val searchProvider = instance as? BookAssociationSearchProvider
        val featureProvider = instance as? ExternalFeatureProvider
        if (searchProvider == null && featureProvider == null) {
            error("$entryClass does not implement a supported source interface")
        }
        if (searchProvider != null && (searchProvider.source.id.isBlank() || searchProvider.source.displayName.isBlank())) {
            error("$entryClass has blank source metadata")
        }
        val declaredId = manifest.optString("id").trim()
        if (searchProvider != null && declaredId.isNotBlank() && declaredId != searchProvider.source.id) {
            error("source id mismatch: manifest=$declaredId entry=${searchProvider.source.id}")
        }
        if (featureProvider != null && declaredId.isNotBlank() && declaredId != featureProvider.id) {
            error("feature id mismatch: manifest=$declaredId entry=${featureProvider.id}")
        }
        val entryId = searchProvider?.source?.id ?: featureProvider?.id ?: entryClass
        val capabilities = buildList {
            if (searchProvider != null) add("search")
            if (featureProvider != null) addAll(featureProvider.capabilities)
        }.joinToString("+")
        log("loaded $entryId ($capabilities) from ${file.name}")
        instance
    }.getOrElse { error ->
        log("failed to load ${file.name}: ${error.message}")
        null
    }

    private fun readManifest(file: File): JSONObject {
        if (file.extension.equals("json", ignoreCase = true) || file.extension.equals("rmsource", ignoreCase = true)) {
            val text = file.readText().trim()
            if (text.startsWith("{")) return JSONObject(text)
        }
        if (file.extension.equals("dex", ignoreCase = true)) {
            val sidecar = File(file.parentFile, file.nameWithoutExtension + ".json")
            if (sidecar.isFile) return JSONObject(sidecar.readText())
            error("dex source requires ${sidecar.name}")
        }
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(MANIFEST_ENTRY) ?: error("missing $MANIFEST_ENTRY")
            return JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
        }
    }

    private fun materializeDexPath(file: File, optimizedDir: File): File {
        if (file.extension.equals("dex", ignoreCase = true)) return file
        val cacheName = buildString {
            append(file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_.-]+"), "_"))
            append("-")
            append(file.length())
            append("-")
            append(file.lastModified())
            append(".dex")
        }
        val target = File(optimizedDir, cacheName)
        if (target.isFile && target.length() > 0L) {
            target.setReadOnly()
            return target
        }
        optimizedDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("${file.nameWithoutExtension}-") && it.extension == "dex" }
            ?.forEach { runCatching { it.delete() } }
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("classes.dex") ?: error("missing classes.dex")
            FileOutputStream(target).use { output ->
                zip.getInputStream(entry).use { input -> input.copyTo(output) }
            }
        }
        target.setReadOnly()
        return target
    }

    private fun log(message: String) {
        runCatching { XposedBridge.log("ReaMicro LSP external source $message") }
    }
}
