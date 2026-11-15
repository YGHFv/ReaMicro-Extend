package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.reamicro.fix.settings.XposedModuleSettings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.io.File
import java.util.WeakHashMap

class ReaMicroGlobalFontHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settings: XposedModuleSettings,
) {
    private val host get() = requireNotNull(com.reamicro.fix.core.HostGlobalFontBindings.current)
    private val fontFamilyCache = HashMap<String, Any>()
    private val androidTypefaceCache = HashMap<String, Typeface>()
    private val composeFontFamilyCache = HashMap<String, FontFamily>()
    private val failedFontFamilyLogKeys = HashSet<String>()
    private val readerTextDepth = ThreadLocal<Int>()
    private val fontPreviewDepth = ThreadLocal<Int>()
    private val appliedLogKeys = HashSet<String>()
    private val pendingDialogLayouts = WeakHashMap<View, View.OnLayoutChangeListener>()
    @Volatile private var cachedGlobalFont: CachedGlobalFont? = null

    fun install() {
        val symbols = host
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            fun scope(method: java.lang.reflect.Method, depth: ThreadLocal<Int>) {
                handles += HookRuntime.hookMethod(method, object : HookCallback() {
                    override fun before(param: Call) { depth.set((depth.get() ?: 0) + 1) }
                    override fun after(param: Call) {
                        val remaining = (depth.get() ?: 1) - 1
                        if (remaining <= 0) depth.remove() else depth.set(remaining)
                    }
                })
                HookRuntime.deoptimize(method)
            }
            symbols.methods("reader.").distinct().forEach { scope(it, readerTextDepth) }
            scope(symbols.method("preview"), fontPreviewDepth)
            for (method in symbols.methods("text.")) {
                handles += HookRuntime.hookMethod(method, object : HookCallback() {
                    override fun before(param: Call) {
                        if (readerDepth() > 0) return
                        val args = param.args
                        val family = symbols.number("family")
                        val style = args[symbols.number("style")]
                        val explicit = style?.let { symbols.field("style.span").get(it) }
                            ?.let { symbols.field("span.family").get(it) }
                        if (fontPreviewDepth() > 0 && (args[family] != null || explicit != null)) return
                        val resolved = resolveGlobalUiFontCached() ?: return
                        args[family] = resolved.family
                        val mask = symbols.number("mask")
                        args[mask] = (args[mask] as Int) and symbols.number("familyBit").inv()
                        // 默认掩码按源码参数编号计算；R8 合并值类参数后不能用反射下标推算。
                        val sourceIndex = Integer.numberOfTrailingZeros(symbols.number("familyBit"))
                        val changed = symbols.number("composer") + 1 + sourceIndex / 10
                        args[changed] = (args[changed] as Int) and (14 shl (3 * (sourceIndex % 10))).inv()
                        logApplied("text", resolved.selection)
                    }
                })
                HookRuntime.deoptimize(method)
            }
            handles += HookRuntime.hookMethod(Dialog::class.java.getDeclaredMethod("show"), object : HookCallback() {
                override fun after(param: Call) { (param.thisObject as? Dialog)?.let(::applyGlobalFontToDialog) }
            })
            EmbeddedHostUi.bindUiFontResolver(::globalAndroidTypeface)
            EmbeddedHostUi.bindUiFontFamilyResolver(::globalComposeFontFamily)
            EmbeddedHostUi.bindMaterialFontFamilyResolver { context, nativeFamily ->
                val selection = if (settings.snapshot().canUseFontSettings) settings.fontSettings().globalFamily else ""
                if (selection.isBlank()) nativeFamily else globalComposeFontFamily(context)
            }
            com.reamicro.fix.reload.ModuleResources.cleanup {
                readerTextDepth.remove(); fontPreviewDepth.remove(); invalidateGlobalFontCache()
                synchronized(failedFontFamilyLogKeys) { failedFontFamilyLogKeys.clear() }
                synchronized(appliedLogKeys) { appliedLogKeys.clear() }
                val pending = synchronized(pendingDialogLayouts) {
                    pendingDialogLayouts.map { it.key to it.value }.also { pendingDialogLayouts.clear() }
                }
                pending.forEach { (view, listener) -> view.removeOnLayoutChangeListener(listener) }
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
    }

    fun invalidateGlobalFontCache() {
        cachedGlobalFont = null
        synchronized(fontFamilyCache) { fontFamilyCache.clear() }
        synchronized(androidTypefaceCache) {
            androidTypefaceCache.clear()
        }
        synchronized(composeFontFamilyCache) { composeFontFamilyCache.clear() }
    }

    private fun applyGlobalFontToDialog(dialog: Dialog) {
        val decor = dialog.window?.decorView ?: return
        val resolved = resolveGlobalAndroidTypefaceCached() ?: return
        applyGlobalFontToViewTree(decor, resolved.typeface, resolved.selection == "host")
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                val lease = com.reamicro.fix.reload.ModuleResources.lease() ?: return
                lease.use {
                    view.removeOnLayoutChangeListener(this)
                    synchronized(pendingDialogLayouts) {
                        if (pendingDialogLayouts[view] === this) pendingDialogLayouts.remove(view)
                    }
                    val current = resolveGlobalAndroidTypefaceCached() ?: return
                    applyGlobalFontToViewTree(view, current.typeface, current.selection == "host")
                }
            }
        }
        synchronized(pendingDialogLayouts) { pendingDialogLayouts.put(decor, listener) }
            ?.let(decor::removeOnLayoutChangeListener)
        decor.addOnLayoutChangeListener(listener)
        logApplied("dialog", resolved.selection)
    }

    private fun applyGlobalFontToViewTree(view: View, baseTypeface: Typeface, nativeFollow: Boolean) {
        if (view is TextView) applyGlobalFontToTextView(view, baseTypeface, nativeFollow)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyGlobalFontToViewTree(view.getChildAt(index), baseTypeface, nativeFollow)
            }
        }
    }

    private fun applyGlobalFontToTextView(textView: TextView, baseTypeface: Typeface, nativeFollow: Boolean) {
        if (textView.tag == "reamicro-font-preview") return
        val current = textView.typeface
        if (current === Typeface.MONOSPACE || current == Typeface.MONOSPACE) return
        val style = current?.style ?: Typeface.NORMAL
        textView.typeface = if (nativeFollow && (style and Typeface.BOLD) != 0)
            EmbeddedHostUi.nativeBoldTypeface(textView.context) ?: Typeface.create(baseTypeface, style)
        else Typeface.create(baseTypeface, style)
    }

    private fun readerDepth(): Int =
        readerTextDepth.get() ?: 0

    private fun fontPreviewDepth(): Int =
        fontPreviewDepth.get() ?: 0

    private fun resolveGlobalUiFontCached(): ResolvedGlobalFont? {
        val now = SystemClock.uptimeMillis()
        val selection = if (settings.snapshot().canUseFontSettings) settings.fontSettings().globalFamily else ""
        if (selection.isBlank()) return null
        val activity = activityProvider()
        val identity = runCatching { AccountHostRuntime.storageSnapshot(activity) }.getOrNull()
        val accountKey = "${identity?.accountId}|${identity?.generation}"
        cachedGlobalFont?.takeIf { now - it.atMs < GLOBAL_FONT_CACHE_WINDOW_MS &&
            it.selection == selection && it.accountKey == accountKey }?.let {
            if (identity == null || runCatching { AccountHostRuntime.isCurrentStorage(identity, activity) }.getOrDefault(false)) return it.font
        }
        val resolved = resolveGlobalUiFont()
        if (!settings.snapshot().canUseFontSettings || identity != null &&
            !runCatching { AccountHostRuntime.isCurrentStorage(identity, activity) }.getOrDefault(false)) return null
        cachedGlobalFont = CachedGlobalFont(now, resolved, selection, accountKey)
        return resolved
    }

    private fun resolveGlobalUiFont(): ResolvedGlobalFont? {
        if (!settings.snapshot().canUseFontSettings) return null
        val selection = settings.fontSettings().globalFamily
        if (selection.isBlank()) return null
        val family = resolveFontFamily(selection) ?: return null
        return ResolvedGlobalFont(selection, family)
    }

    private fun resolveGlobalAndroidTypefaceCached(): ResolvedAndroidTypeface? {
        val selection = if (settings.snapshot().canUseFontSettings) settings.fontSettings().globalFamily else ""
        if (selection.isBlank()) return activityProvider()?.let(EmbeddedHostUi::nativeTypeface)?.let { ResolvedAndroidTypeface("host", it) }
        val typeface = resolveAndroidTypeface(selection) ?: return activityProvider()?.let(EmbeddedHostUi::nativeTypeface)?.let { ResolvedAndroidTypeface("host", it) }
        return ResolvedAndroidTypeface(selection, typeface)
    }

    internal fun globalAndroidTypeface(): Typeface? = resolveGlobalAndroidTypefaceCached()?.typeface

    /** Module Compose must select real font weights, which a single loaded Typeface cannot do. */
    private fun globalComposeFontFamily(host: android.content.Context): FontFamily {
        val selection = if (settings.snapshot().canUseFontSettings) settings.fontSettings().globalFamily else ""
        if (selection == FAMILY_SYSTEM) return FontFamily.Default
        val file = if (selection.isBlank() || selection == FAMILY_SOURCE_HAN_SERIF) null
            else resolveFontFile(selection)
        val key = com.reamicro.fix.epub.editor.FontSelectionResolution.cacheKey(selection, file)
        synchronized(composeFontFamilyCache) {
            composeFontFamilyCache[key]?.let { return it }
        }
        val family = if (file == null) HostEmbeddedStyle.fonts(host).serif else {
            runCatching {
                FontFamily(Font(file, weight = FontWeight.Normal), Font(file, weight = FontWeight.Bold))
            }.onFailure { logResolveFontFamilyFailure(selection, it) }
                .getOrElse { HostEmbeddedStyle.fonts(host).serif }
        }
        synchronized(composeFontFamilyCache) { composeFontFamilyCache[key] = family }
        return family
    }

    private fun resolveAndroidTypeface(selection: String): Typeface? {
        val resolvedFile = if (isBuiltinFontSelection(selection)) null else resolveFontFile(selection) ?: return null
        val key = com.reamicro.fix.epub.editor.FontSelectionResolution.cacheKey(selection, resolvedFile)
        synchronized(androidTypefaceCache) {
            androidTypefaceCache[key]?.let { return it }
        }
        val resolved = when (selection) {
            FAMILY_SYSTEM -> Typeface.DEFAULT
            FAMILY_SOURCE_HAN_SERIF -> activityProvider()?.let(EmbeddedHostUi::nativeTypeface) ?: Typeface.SERIF
            else -> {
                val file = resolvedFile ?: return null
                runCatching { Typeface.createFromFile(file) }
                    .onFailure { logResolveFontFamilyFailure(selection, it) }
                    .getOrNull()
            }
        } ?: return null
        synchronized(androidTypefaceCache) {
            androidTypefaceCache[key] = resolved
        }
        return resolved
    }

    private fun resolveFontFamily(selection: String): Any? {
        if (selection.isBlank()) return null
        val resolvedFile = if (isBuiltinFontSelection(selection)) null else resolveFontFile(selection) ?: return null
        val cacheKey = com.reamicro.fix.epub.editor.FontSelectionResolution.cacheKey(selection, resolvedFile)
        synchronized(fontFamilyCache) {
            fontFamilyCache[cacheKey]?.let { return it }
        }
        if (isBuiltinFontSelection(selection)) {
            return resolveBuiltinFontFamily(selection)?.also { family ->
                synchronized(fontFamilyCache) {
                    fontFamilyCache[cacheKey] = family
                }
            }
        }
        val file = resolvedFile ?: return null
        return runCatching {
            val family = com.reamicro.fix.core.HostFontFamilies.fromFile(classLoader, file) ?: return null
            synchronized(fontFamilyCache) {
                fontFamilyCache[cacheKey] = family
            }
            family
        }.onFailure { logResolveFontFamilyFailure(selection, it) }.getOrNull()
    }

    private fun resolveBuiltinFontFamily(selection: String): Any? =
        runCatching {
            when (selection) {
                FAMILY_SYSTEM -> resolveDefaultFontFamily()
                FAMILY_SOURCE_HAN_SERIF -> com.reamicro.fix.core.HostFontFamilies.song(classLoader)
                else -> null
            }
        }.onFailure { logResolveFontFamilyFailure(selection, it) }.getOrNull()

    private fun resolveDefaultFontFamily(): Any? =
        com.reamicro.fix.core.HostFontFamilies.system(classLoader)

    private fun resolveFontFile(selection: String): File? {
        val activity = activityProvider() ?: return null
        val identity = runCatching { AccountHostRuntime.storageSnapshot(activity) }.getOrNull() ?: return null
        val file = runCatching { com.reamicro.fix.epub.editor.AccountFontFiles.resolve(
            activity.filesDir, identity.accountId, selection,
        ) }.getOrNull() ?: return null
        return file.takeIf { runCatching { AccountHostRuntime.isCurrentStorage(identity, activity) }.getOrDefault(false) }
    }

    private fun logResolveFontFamilyFailure(selection: String, error: Throwable) {
        val key = "$selection|${error.javaClass.name}|${error.message}"
        synchronized(failedFontFamilyLogKeys) {
            if (!failedFontFamilyLogKeys.add(key)) return
        }
        HookRuntime.log(
            "$LOG_PREFIX global UI font resolve failed: " +
                "${displayFontName(selection)}: ${error.javaClass.simpleName}: ${error.message}",
        )
    }

    private fun logApplied(source: String, selection: String) {
        val key = "$source|$selection"
        synchronized(appliedLogKeys) {
            if (!appliedLogKeys.add(key)) return
        }
        HookRuntime.log("$LOG_PREFIX global UI font applied ($source): ${displayFontName(selection)}")
    }

    private fun displayFontName(value: String): String =
        when (value) {
            FAMILY_SYSTEM -> "系统字体"
            FAMILY_SOURCE_HAN_SERIF -> "思源宋体"
            else -> File(value).name.substringBeforeLast('.', File(value).name)
        }

    private data class ResolvedGlobalFont(
        val selection: String,
        val family: Any,
    )

    private data class ResolvedAndroidTypeface(
        val selection: String,
        val typeface: Typeface,
    )

    private data class CachedGlobalFont(
        val atMs: Long,
        val font: ResolvedGlobalFont?,
        val selection: String,
        val accountKey: String,
    )

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val FAMILY_SYSTEM = "system"
        const val FAMILY_SOURCE_HAN_SERIF = "serif"
        const val GLOBAL_FONT_CACHE_WINDOW_MS = 300L

        fun isBuiltinFontSelection(selection: String): Boolean =
            selection == FAMILY_SYSTEM || selection == FAMILY_SOURCE_HAN_SERIF
    }
}
