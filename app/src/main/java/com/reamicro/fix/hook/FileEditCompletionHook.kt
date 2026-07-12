package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.settings.FontSettingsSnapshot
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.Locale

class FileEditCompletionHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
    private val fontSettingsProvider: () -> FontSettingsSnapshot = { FontSettingsSnapshot() },
) {
    private val methodCache = HashMap<String, Method>()
    private val activityResultHookedClasses = HashSet<String>()
    private val activeBook = ThreadLocal<Any?>()
    private val activeBookDepth = ThreadLocal.withInitial { 0 }
    private val injectingRow = ThreadLocal.withInitial { false }

    fun install() {
        hookBookDetailsTitleAuthorItem()
        hookBookDetailsSyncSizeItem()
        hookActivityResultFor(Activity::class.java)
    }

    private fun hookBookDetailsTitleAuthorItem() {
        runCatching {
            val itemsClass = cls(BOOK_OVERVIEW_ITEMS_CLASS)
            val methods = itemsClass.declaredMethods.filter { method ->
                method.name == BOOK_TITLE_AUTHOR_ITEM_METHOD &&
                    method.parameterTypes.size == 6 &&
                    method.parameterTypes.getOrNull(1)?.name == BOOK_CLASS
            }
            if (methods.isEmpty()) error("BookTitleAuthorItem composable not found")
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (injectingRow.get() == true) return
                        if (!settingsProvider().canUseFileEdit) return
                        val lazyItemScope = param.args?.getOrNull(0) ?: return
                        val book = param.args?.getOrNull(1) ?: return
                        val onEditTitle = param.args?.getOrNull(2) ?: return
                        val onEditAuthor = param.args?.getOrNull(3) ?: return
                        val composer = param.args?.getOrNull(4) ?: return
                        injectingRow.set(true)
                        val rendered = runCatching {
                            renderBookTitleAuthorIdentifierCard(lazyItemScope, book, onEditTitle, onEditAuthor, composer)
                        }.onSuccess {
                            param.result = targetUnit()
                        }.onFailure {
                            XposedBridge.log("$LOG_PREFIX book identifier row render failed: ${it.stackTraceToString()}")
                        }
                        injectingRow.set(false)
                        rendered.getOrNull()
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX book identifier BookTitleAuthorItem hook installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX book identifier BookTitleAuthorItem hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun renderBookTitleAuthorIdentifierCard(
        lazyItemScope: Any,
        book: Any,
        onEditTitle: Any,
        onEditAuthor: Any,
        composer: Any,
    ) {
        val displayText = bookIdentifierDisplayText(book)
        renderDetailsCard(lazyItemScope, composer, "BookTitleAuthorIdentifierCard") { innerComposer ->
            renderDetailsActionRow(
                label = "\u4e66\u540d",
                value = callString(book, "getTitle"),
                composer = innerComposer,
                onClickName = "EditBookTitle",
                onClick = { invokeFunction0(onEditTitle) },
                trailing = editImageVector(),
            )
            renderDetailsDivider(innerComposer)
            renderDetailsActionRow(
                label = "\u4f5c\u8005",
                value = callString(book, "getAuthor"),
                composer = innerComposer,
                onClickName = "EditBookAuthor",
                onClick = { invokeFunction0(onEditAuthor) },
                trailing = editImageVector(),
            )
            if (displayText.isNotBlank()) {
                renderDetailsDivider(innerComposer)
                renderDetailsActionRow(
                    label = "\u6807\u8bc6",
                    value = displayText,
                    composer = innerComposer,
                    onClickName = "CopyBookIdentifier",
                    onClick = { copyBookIdentifiers(book) },
                    trailing = contentCopyImageVector(),
                    valueWeight = true,
                    middleEllipsis = true,
                )
            }
            targetUnit()
        }
    }

    private fun hookBookDetailsSyncSizeItem() {
        runCatching {
            val itemsClass = cls(BOOK_OVERVIEW_ITEMS_CLASS)
            val methods = itemsClass.declaredMethods.filter { method ->
                method.name == BOOK_SYNC_SIZE_ITEM_METHOD &&
                    method.parameterTypes.size == 5 &&
                    method.parameterTypes.getOrNull(1)?.name == BOOK_CLASS
            }
            if (methods.isEmpty()) error("BookSyncSizeItem composable not found")
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (injectingRow.get() == true) return
                        if (!settingsProvider().canUseFileEdit) return
                        val lazyItemScope = param.args?.getOrNull(0) ?: return
                        val book = param.args?.getOrNull(1) ?: return
                        val onOpenSync = param.args?.getOrNull(2) ?: return
                        val composer = param.args?.getOrNull(3) ?: return
                        injectingRow.set(true)
                        val rendered = runCatching {
                            renderFileEditSyncDetailsCard(lazyItemScope, book, onOpenSync, composer)
                        }.onSuccess {
                            param.result = targetUnit()
                        }.onFailure {
                            XposedBridge.log("$LOG_PREFIX file edit details row render failed: ${it.stackTraceToString()}")
                        }
                        injectingRow.set(false)
                        rendered.getOrNull()
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX file edit BookSyncSizeItem hook installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX file edit BookSyncSizeItem hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun renderFileEditSyncDetailsCard(lazyItemScope: Any, book: Any, onOpenSync: Any, composer: Any) {
        renderDetailsCard(lazyItemScope, composer, "FileEditSyncDetailsCard") { innerComposer ->
            renderDetailsActionRow(
                label = "\u7f16\u8f91",
                value = "\u56fe\u4e66\u7ed3\u6784",
                composer = innerComposer,
                onClickName = "OpenFileEditDetails",
                onClick = { openFileEditor(book) },
                trailing = navigateNextImageVector(),
            )
            renderDetailsDivider(innerComposer)
            renderDetailsActionRow(
                label = "\u540c\u6b65",
                value = syncSizeValueText(book),
                composer = innerComposer,
                onClickName = "OpenBookSync",
                onClick = { invokeFunction0(onOpenSync) },
                afterValueIcon = syncStorageImageVector(book),
                secondaryValue = backupTypeName(book),
                trailing = navigateNextImageVector(),
            )
            targetUnit()
        }
    }

    private fun bookIdentifierDisplayText(book: Any): String =
        bookIdentifierRawText(book)

    private fun bookIdentifierRawText(book: Any): String =
        callString(book, "getUuid").trim()
            .takeIf { it.isUuidOrMd5Identifier() }
            .orEmpty()

    private fun String.isUuidOrMd5Identifier(): Boolean =
        UUID_IDENTIFIER_REGEX.matches(this) || MD5_IDENTIFIER_REGEX.matches(this)

    private fun syncSizeValueText(book: Any): String =
        formatFileSize(callLong(book, "getSize")).replace(" ", "")

    private fun backupTypeName(book: Any): String =
        runCatching {
            method(BACKUP_TYPE_CLASS, "getName", 1).invoke(
                staticObject(BACKUP_TYPE_CLASS, "INSTANCE"),
                Integer.valueOf(callInt(book, "getBackupType")),
            )?.toString().orEmpty()
        }.getOrDefault("").ifBlank { "\u672c\u5730\u5b58\u50a8" }

    private fun copyBookIdentifiers(book: Any) {
        val activity = activityProvider() ?: return
        val text = bookIdentifierRawText(book)
        if (text.isBlank()) return
        val copy = {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("\u56fe\u4e66\u6807\u8bc6", text))
            Toast.makeText(activity, "\u5df2\u590d\u5236\u6807\u8bc6", Toast.LENGTH_SHORT).show()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) copy() else activity.runOnUiThread { copy() }
    }

    private fun invokeFunction0(callback: Any?) {
        runCatching { XposedHelpers.callMethod(callback, "invoke") }
            .onFailure { XposedBridge.log("$LOG_PREFIX callback invoke failed: ${it.stackTraceToString()}") }
    }

    private fun hookBookLocalSheet() {
        runCatching {
            val sheetClass = cls(BOOK_LOCAL_SHEET_CLASS)
            sheetClass.declaredMethods
                .filter { method ->
                    (method.name == BOOK_LOCAL_SHEET_METHOD && method.parameterTypes.size == 5) ||
                        (method.name == BOOK_LOCAL_SHEET_CONTENT_METHOD &&
                            method.parameterTypes.firstOrNull()?.name == BOOK_CLASS)
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            activeBookDepth.set((activeBookDepth.get() ?: 0) + 1)
                            activeBook.set(param.args?.getOrNull(0))
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val nextDepth = ((activeBookDepth.get() ?: 0) - 1).coerceAtLeast(0)
                            activeBookDepth.set(nextDepth)
                            if (nextDepth == 0) activeBook.set(null)
                        }
                    })
                }
            XposedBridge.log("$LOG_PREFIX file edit BookLocalSheet hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX file edit BookLocalSheet hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookFileBackup() {
        runCatching {
            val sheetClass = cls(BOOK_LOCAL_SHEET_CLASS)
            val methods = sheetClass.declaredMethods.filter {
                it.name == FILE_BACKUP_METHOD && it.parameterTypes.size == 5
            }
            if (methods.isEmpty()) error("FileBackup composable not found")
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (injectingRow.get() == true) return
                        if (!settingsProvider().canUseFileEdit) return
                        val book = activeBook.get() ?: return
                        val composer = param.args?.getOrNull(3) ?: return
                        injectingRow.set(true)
                        runCatching {
                            renderHostDivider(composer)
                            renderFileEditRow(book, composer)
                        }.onFailure {
                            XposedBridge.log("$LOG_PREFIX file edit row render failed: ${it.stackTraceToString()}")
                        }
                        injectingRow.set(false)
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX file edit FileBackup hook installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX file edit FileBackup hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun renderFileEditRow(book: Any, composer: Any) {
        val modifier = paddingModifier(
            clickableModifier(modifierInstance(), "OpenFileEdit") {
                openFileEditor(book)
            },
            start = 18,
            top = 16,
            end = 12,
            bottom = 16,
        )
        val content = functionProxy("FileEditRowContent", FUNCTION3_CLASS) { args ->
            val rowScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            val innerComposer = args.getOrNull(1) ?: return@functionProxy targetUnit()
            editImageVector()?.let { image ->
                renderIcon(
                    image = image,
                    modifier = sizeModifier(
                        paddingModifier(
                            modifierInstance(),
                            start = 0,
                            top = 0,
                            end = 16,
                            bottom = 0,
                        ),
                        20,
                    ),
                    tint = colorScheme(innerComposer).longMethod("getOnBackground"),
                    composer = innerComposer,
                )
            }
            renderPrimaryText(
                text = "\u6587\u4ef6\u7f16\u8f91",
                modifier = rowWeightModifier(rowScope, modifierInstance()),
                composer = innerComposer,
            )
            renderSecondarySingleLineText(
                text = "\u56fe\u4e66\u7ed3\u6784",
                composer = innerComposer,
            )
            navigateNextImageVector()?.let { image ->
                renderIcon(
                    image = image,
                    modifier = paddingModifier(
                        modifierInstance(),
                        start = 0,
                        top = 2,
                        end = 0,
                        bottom = 0,
                    ),
                    tint = colorScheme(innerComposer).longMethod("getSurfaceContainerHighest"),
                    composer = innerComposer,
                )
            }
            targetUnit()
        }
        method(ROW_KT_CLASS, ROW_METHOD, 7).invoke(
            null,
            modifier,
            arrangementStart(),
            alignmentCenterVertically(),
            content,
            composer,
            384,
            0,
        )
    }

    private fun openFileEditor(book: Any) {
        val activity = activityProvider() ?: return
        val open = open@{
            if (!settingsProvider().canUseFileEdit) return@open
            val root = resolveBookRoot(activity, book)
            if (root == null) {
                Toast.makeText(activity, "\u672a\u627e\u5230\u56fe\u4e66\u6587\u4ef6\u76ee\u5f55", Toast.LENGTH_SHORT).show()
                return@open
            }
            XposedBridge.log("$LOG_PREFIX file edit open root=${root.absolutePath}")
            hookActivityResultFor(activity.javaClass)
            EpubWebEditorPanel(
                activity = activity,
                root = root,
                bookTitle = callString(book, "getTitle").ifBlank { "\u56fe\u4e66\u6587\u4ef6" },
                book = book,
                settingsProvider = settingsProvider,
                fontSettingsProvider = fontSettingsProvider,
            ).show()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            open()
        } else {
            activity.runOnUiThread { open() }
        }
    }

    private fun hookActivityResultFor(startClass: Class<*>) {
        var current: Class<*>? = startClass
        while (current != null && Activity::class.java.isAssignableFrom(current)) {
            val target = current
            val shouldHook = synchronized(activityResultHookedClasses) {
                activityResultHookedClasses.add(target.name)
            }
            if (shouldHook) {
                runCatching {
                    val method = target.declaredMethods.firstOrNull {
                        it.name == "onActivityResult" &&
                            it.parameterTypes.size == 3 &&
                            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                            Intent::class.java.isAssignableFrom(it.parameterTypes[2])
                    } ?: return@runCatching
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            val requestCode = param.args?.getOrNull(0) as? Int ?: return
                            val resultCode = param.args?.getOrNull(1) as? Int ?: return
                            val data = param.args?.getOrNull(2) as? Intent
                            EpubWebEditorPanel.dispatchActivityResult(activity, requestCode, resultCode, data)
                        }
                    })
                    XposedBridge.log("$LOG_PREFIX file edit ActivityResult hook installed: ${target.name}")
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX file edit ActivityResult hook failed (${target.name}): ${it.stackTraceToString()}")
                }
            }
            current = current.superclass
        }
    }

    private fun resolveBookRoot(activity: Activity, book: Any): File? {
        val filesDir = activity.filesDir ?: return null
        val uid = callLong(book, "getUid")
        val uuid = callString(book, "getUuid").trim()
        val candidates = buildList {
            if (uid >= 0L && uuid.isNotBlank()) {
                add(File(File(File(filesDir, uid.toString()), "books"), uuid))
            }
            if (uuid.isNotBlank()) {
                filesDir.listFiles()
                    ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
                    ?.mapTo(this) { File(File(it, "books"), uuid) }
            }
            uriFile(callString(book, "getUri"))?.let { uriFile ->
                add(if (uriFile.isDirectory) uriFile else uriFile.parentFile ?: uriFile)
            }
        }
        return candidates
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .firstOrNull { it.isDirectory }
    }

    private fun uriFile(value: String): File? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        return when {
            trimmed.startsWith("file://") -> File(android.net.Uri.parse(trimmed).path ?: return null)
            else -> File(trimmed)
        }
    }

    private inner class FileEditorPanel(
        private val activity: Activity,
        private val root: File,
        private val bookTitle: String,
    ) {
        private val colors = HostColors(activity)
        private val dialog = Dialog(activity)
        private val toolbarTitle = TextView(activity)
        private val leftAction = TextView(activity)
        private val rightAction = TextView(activity)
        private val pathView = TextView(activity)
        private val content = FrameLayout(activity)
        private var currentDir: File = root
        private var editingFile: File? = null
        private var editor: EditText? = null

        fun show() {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(createRootView())
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
                when {
                    editingFile != null -> {
                        showDirectory(currentDir)
                        true
                    }
                    currentDir.canonicalPathSafe() != root.canonicalPathSafe() -> {
                        currentDir.parentFile?.let(::showDirectory)
                        true
                    }
                    else -> false
                }
            }
            dialog.show()
            dialog.window?.setBackgroundDrawable(ColorDrawable(colors.pageBackground))
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            showDirectory(root)
        }

        private fun createRootView(): View {
            return LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(colors.pageBackground)
                addView(createToolbar())
                addView(pathView.apply {
                    setTextColor(colors.secondaryText)
                    textSize = 12f
                    setSingleLine(true)
                    setPadding(dp(activity, 20), 0, dp(activity, 20), dp(activity, 10))
                })
                addView(content, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ))
            }
        }

        private fun createToolbar(): View {
            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(colors.pageBackground)
                setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 12), dp(activity, 4))
                minimumHeight = dp(activity, 60)
                addView(leftAction.apply {
                    gravity = Gravity.CENTER
                    setTextColor(colors.primaryText)
                    textSize = 26f
                    typeface = Typeface.DEFAULT
                }, LinearLayout.LayoutParams(
                    dp(activity, 44),
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
                addView(toolbarTitle.apply {
                    setTextColor(colors.primaryText)
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    setSingleLine(true)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(rightAction.apply {
                    gravity = Gravity.CENTER
                    setTextColor(colors.accent)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(activity, 12), 0, dp(activity, 8), 0)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
            }
        }

        private fun showDirectory(dir: File) {
            val canonicalRoot = root.canonicalPathSafe()
            val canonicalDir = dir.canonicalPathSafe()
            if (!canonicalDir.startsWith(canonicalRoot)) return
            editingFile = null
            editor = null
            currentDir = dir
            toolbarTitle.text = bookTitle
            leftAction.text = "\u2039"
            leftAction.setOnClickListener { dialog.dismiss() }
            rightAction.text = ""
            rightAction.setOnClickListener(null)
            pathView.text = relativePath(dir).ifBlank { "/" }

            val items = listFilesFor(dir)
            content.removeAllViews()
            content.addView(
                if (items.isEmpty()) {
                    createEmptyState(
                        title = "\u8fd9\u4e2a\u76ee\u5f55\u6ca1\u6709\u53ef\u663e\u793a\u7684\u6587\u4ef6",
                        subtitle = dir.absolutePath,
                    )
                } else {
                    createDirectoryList(items)
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        private fun openFile(file: File) {
            val canonicalRoot = root.canonicalPathSafe()
            val canonicalFile = file.canonicalPathSafe()
            if (!canonicalFile.startsWith(canonicalRoot) || !file.isFile) return
            if (file.length() > MAX_EDITABLE_FILE_BYTES) {
                Toast.makeText(activity, "\u6587\u4ef6\u8fc7\u5927\uff0c\u6682\u4e0d\u652f\u6301\u7f16\u8f91", Toast.LENGTH_SHORT).show()
                return
            }
            val text = runCatching {
                String(file.readBytes(), StandardCharsets.UTF_8)
            }.getOrElse {
                Toast.makeText(activity, "\u8bfb\u53d6\u6587\u4ef6\u5931\u8d25", Toast.LENGTH_SHORT).show()
                XposedBridge.log("$LOG_PREFIX file edit read failed: ${it.stackTraceToString()}")
                return
            }
            editingFile = file
            toolbarTitle.text = file.name
            leftAction.text = "\u2039"
            leftAction.setOnClickListener { showDirectory(currentDir) }
            rightAction.text = "\u4fdd\u5b58"
            rightAction.setOnClickListener { saveCurrentFile() }
            pathView.text = relativePath(file)

            editor = EditText(activity).apply {
                setText(text)
                setTextColor(colors.primaryText)
                setHintTextColor(colors.secondaryText)
                textSize = 14f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.START or Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setHorizontallyScrolling(false)
                setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
                background = roundedDrawable(colors.cardBackground, colors.border, 8)
            }
            content.removeAllViews()
            content.addView(createEditorContainer(editor!!), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }

        private fun createDirectoryList(items: List<FileItem>): View {
            return ScrollView(activity).apply {
                setBackgroundColor(colors.pageBackground)
                isFillViewport = true
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(activity, 20), dp(activity, 2), dp(activity, 20), dp(activity, 24))
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = roundedDrawable(colors.cardBackground, colors.border, 8)
                        items.forEachIndexed { index, item ->
                            if (index > 0) addView(createInsetDivider())
                            addView(createFileRow(item))
                        }
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
        }

        private fun createFileRow(item: FileItem): View {
            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(activity, 64)
                setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 12), dp(activity, 12))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    foreground = selectableItemBackground(activity)
                }
                setOnClickListener {
                    when {
                        item.parent -> currentDir.parentFile?.let(::showDirectory)
                        item.file.isDirectory -> showDirectory(item.file)
                        isEditableFile(item.file) -> openFile(item.file)
                        else -> Toast.makeText(
                            activity,
                            "\u8be5\u6587\u4ef6\u7c7b\u578b\u6682\u4e0d\u652f\u6301\u7f16\u8f91",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                addView(TextView(activity).apply {
                    text = fileKindLabel(item)
                    setTextColor(colors.secondaryText)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    background = roundedDrawable(colors.chipBackground, Color.TRANSPARENT, 6)
                }, LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 28)).apply {
                    rightMargin = dp(activity, 14)
                })
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(activity).apply {
                        text = item.title
                        setTextColor(colors.primaryText)
                        textSize = 15f
                        setSingleLine(true)
                        typeface = if (item.file.isDirectory || item.parent) {
                            Typeface.DEFAULT_BOLD
                        } else {
                            Typeface.DEFAULT
                        }
                    })
                    addView(TextView(activity).apply {
                        text = item.subtitle
                        setTextColor(colors.secondaryText)
                        textSize = 12f
                        setSingleLine(true)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(activity).apply {
                    text = ">"
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(colors.trailing)
                }, LinearLayout.LayoutParams(dp(activity, 28), ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }

        private fun createInsetDivider(): View =
            View(activity).apply {
                setBackgroundColor(colors.divider)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1,
                ).apply {
                    leftMargin = dp(activity, 70)
                }
            }

        private fun createEditorContainer(editorView: View): View {
            return FrameLayout(activity).apply {
                setBackgroundColor(colors.pageBackground)
                setPadding(dp(activity, 16), dp(activity, 2), dp(activity, 16), dp(activity, 16))
                addView(editorView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
            }
        }

        private fun createEmptyState(title: String, subtitle: String): View {
            return LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(activity, 28), dp(activity, 28), dp(activity, 28), dp(activity, 28))
                setBackgroundColor(colors.pageBackground)
                addView(TextView(activity).apply {
                    text = title
                    setTextColor(colors.primaryText)
                    textSize = 16f
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(activity).apply {
                    text = subtitle
                    setTextColor(colors.secondaryText)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(activity, 8), 0, 0)
                })
            }
        }

        private fun fileKindLabel(item: FileItem): String =
            when {
                item.parent -> ".."
                item.file.isDirectory -> "\u76ee\u5f55"
                item.file.extension.isBlank() -> "\u6587\u4ef6"
                else -> item.file.extension.uppercase(Locale.ROOT).take(4)
            }

        private fun saveCurrentFile() {
            val file = editingFile ?: return
            val editText = editor ?: return
            val canonicalRoot = root.canonicalPathSafe()
            val canonicalFile = file.canonicalPathSafe()
            if (!canonicalFile.startsWith(canonicalRoot) || !file.isFile) return
            runCatching {
                file.writeText(editText.text?.toString().orEmpty(), StandardCharsets.UTF_8)
            }.onSuccess {
                Toast.makeText(activity, "\u5df2\u4fdd\u5b58", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(activity, "\u4fdd\u5b58\u5931\u8d25", Toast.LENGTH_SHORT).show()
                XposedBridge.log("$LOG_PREFIX file edit save failed: ${it.stackTraceToString()}")
            }
        }

        private fun listFilesFor(dir: File): List<FileItem> {
            val items = mutableListOf<FileItem>()
            if (dir.canonicalPathSafe() != root.canonicalPathSafe()) {
                items.add(FileItem(dir.parentFile ?: dir, "..", "\u8fd4\u56de\u4e0a\u7ea7\u76ee\u5f55", parent = true))
            }
            dir.listFiles()
                .orEmpty()
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
                .mapTo(items) { file ->
                    FileItem(
                        file = file,
                        title = file.name,
                        subtitle = fileSubtitle(file),
                        parent = false,
                    )
                }
            return items
        }

        private fun fileSubtitle(file: File): String =
            if (file.isDirectory) {
                "\u76ee\u5f55 \u00b7 ${file.listFiles()?.size ?: 0} \u9879"
            } else {
                val editable = if (isEditableFile(file)) "\u53ef\u7f16\u8f91" else "\u4e0d\u53ef\u7f16\u8f91"
                "$editable \u00b7 ${formatFileSize(file.length())}"
            }

        private fun relativePath(file: File): String {
            val rootPath = root.canonicalPathSafe().trimEnd(File.separatorChar)
            val filePath = file.canonicalPathSafe()
            return filePath.removePrefix(rootPath).trimStart(File.separatorChar)
        }
    }

    private class FileListAdapter(
        private val context: Context,
        private val items: List<FileItem>,
        private val colors: HostColors,
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size

        override fun getItem(position: Int): FileItem = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10))
                minimumHeight = dp(context, 64)
                addView(TextView(context).apply {
                    id = android.R.id.text1
                    textSize = 15f
                    setSingleLine(true)
                })
                addView(TextView(context).apply {
                    id = android.R.id.text2
                    textSize = 12f
                    setSingleLine(true)
                })
            }
            val item = getItem(position)
            row.setBackgroundColor(colors.background)
            row.findViewById<TextView>(android.R.id.text1).apply {
                text = when {
                    item.parent -> item.title
                    item.file.isDirectory -> "[\u76ee\u5f55] ${item.title}"
                    else -> item.title
                }
                setTextColor(colors.primaryText)
                typeface = if (item.file.isDirectory || item.parent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            row.findViewById<TextView>(android.R.id.text2).apply {
                text = item.subtitle
                setTextColor(colors.secondaryText)
            }
            return row
        }
    }

    private data class FileItem(
        val file: File,
        val title: String,
        val subtitle: String,
        val parent: Boolean,
    )

    private class HostColors(context: Context) {
        private val palette = ModuleDialogTheme.palette(context)

        val pageBackground: Int = palette.pageBackground
        val cardBackground: Int = palette.rowBackground
        val background: Int = cardBackground
        val primaryText: Int = palette.title
        val secondaryText: Int = palette.body
        val accent: Int = palette.primary
        val trailing: Int = palette.border
        val border: Int = palette.border
        val divider: Int = palette.border
        val chipBackground: Int = palette.rowBackground
        val editorBackground: Int = cardBackground
    }

    private fun renderPrimaryText(text: String, modifier: Any?, composer: Any) {
        method(TEXT_KT_CLASS, TEXT_METHOD, 22).invoke(
            null,
            text,
            modifier,
            colorScheme(composer).longMethod("getOnBackground"),
            null,
            0L,
            null,
            null,
            null,
            0L,
            null,
            null,
            0L,
            0,
            false,
            0,
            0,
            null,
            typography(composer).method0("getLabelLarge"),
            composer,
            0,
            0,
            TEXT_DEFAULT_MASK_WITH_MODIFIER,
        )
    }

    private fun renderSecondarySingleLineText(text: String, composer: Any) {
        method(TEXT_KT_CLASS, TEXT_METHOD, 22).invoke(
            null,
            text,
            null,
            themeOnBackgroundVariant(composer),
            null,
            0L,
            null,
            null,
            null,
            0L,
            null,
            null,
            0L,
            textOverflowEllipsis(),
            false,
            1,
            0,
            null,
            typography(composer).method0("getBodyMedium"),
            composer,
            0,
            24960,
            TEXT_SECONDARY_SINGLE_LINE_MASK,
        )
    }

    private fun renderDetailsText(
        text: String,
        modifier: Any?,
        color: Long,
        composer: Any,
        overflow: Int = textOverflowEllipsis(),
    ) {
        method(TEXT_KT_CLASS, TEXT_METHOD, 22).invoke(
            null,
            text,
            modifier,
            color,
            null,
            0L,
            null,
            null,
            null,
            0L,
            null,
            null,
            0L,
            overflow,
            false,
            1,
            0,
            null,
            typography(composer).method0("getBodyLarge"),
            composer,
            0,
            24960,
            if (modifier == null) TEXT_SECONDARY_SINGLE_LINE_MASK else TEXT_SECONDARY_SINGLE_LINE_MASK_WITH_MODIFIER,
        )
    }

    private fun renderIcon(image: Any, modifier: Any, tint: Long, composer: Any) {
        iconImageVectorMethod().invoke(
            null,
            image,
            null,
            modifier,
            tint,
            composer,
            48,
            0,
        )
    }

    private fun iconImageVectorMethod(): Method =
        synchronized(methodCache) {
            methodCache.getOrPut("$ICON_KT_CLASS#$ICON_METHOD/ImageVector") {
                cls(ICON_KT_CLASS).declaredMethods.firstOrNull {
                    it.name == ICON_METHOD &&
                        it.parameterTypes.size == 7 &&
                        it.parameterTypes.firstOrNull()?.name == IMAGE_VECTOR_CLASS
                }?.apply { isAccessible = true }
                    ?: error("$ICON_KT_CLASS.$ICON_METHOD ImageVector overload not found")
            }
        }

    private fun renderHostDivider(composer: Any) {
        simpleDividerMethod().invoke(
            null,
            method(PADDING_KT_CLASS, PADDING_ABSOLUTE_DEFAULT_METHOD, 7).invoke(
                null,
                modifierInstance(),
                udp(54),
                0f,
                0f,
                0f,
                14,
                null,
            ),
            0L,
            composer,
            0,
            2,
        )
    }

    private fun renderDetailsCard(
        lazyItemScope: Any,
        composer: Any,
        name: String,
        renderRows: (Any) -> Unit,
    ) {
        val shape = roundedShape()
        val modifier = backgroundModifier(
            borderModifier(
                clipModifier(
                    lazyAnimateItemModifier(lazyItemScope, fillMaxWidthModifier(modifierInstance())),
                    shape,
                ),
                0.4f,
                colorScheme(composer).longMethod("getSurfaceContainerHighest"),
                shape,
            ),
            themeBackgroundAuto(composer),
        )
        val content = functionProxy(name, FUNCTION3_CLASS) { args ->
            val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
            renderRows(innerComposer)
            targetUnit()
        }
        method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(
            null,
            modifier,
            arrangementTop(),
            alignmentStart(),
            content,
            composer,
            384,
            0,
        )
    }

    private fun renderDetailsActionRow(
        label: String,
        value: String,
        composer: Any,
        onClickName: String,
        onClick: () -> Unit,
        trailing: Any?,
        leadingValueIcon: Any? = null,
        afterValueIcon: Any? = null,
        secondaryValue: String? = null,
        valueWeight: Boolean = false,
        middleEllipsis: Boolean = false,
    ) {
        val modifier = fillMaxWidthModifier(
            paddingModifier(
                clickableModifier(modifierInstance(), onClickName, onClick),
                start = 16,
                top = 15,
                end = 12,
                bottom = 15,
            ),
        )
        val content = functionProxy("${onClickName}RowContent", FUNCTION3_CLASS) { args ->
            val rowScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            val innerComposer = args.getOrNull(1) ?: return@functionProxy targetUnit()
            renderDetailsText(
                text = label,
                modifier = null,
                color = themeOnBackgroundVariant(innerComposer),
                composer = innerComposer,
            )
            renderFixedWidthSpacer(width = 12, composer = innerComposer)
            if (!valueWeight) renderWeightedSpacer(rowScope, innerComposer)
            leadingValueIcon?.let { image ->
                renderIcon(
                    image = image,
                    modifier = sizeModifier(
                        paddingModifier(modifierInstance(), start = 0, top = 0, end = 4, bottom = 0),
                        18,
                    ),
                    tint = syncIconTint(innerComposer),
                    composer = innerComposer,
                )
            }
            renderDetailsText(
                text = value,
                modifier = if (valueWeight) rowWeightModifier(rowScope, modifierInstance()) else null,
                color = colorScheme(innerComposer).longMethod("getOnBackground"),
                overflow = if (middleEllipsis) textOverflowMiddleEllipsis() else textOverflowEllipsis(),
                composer = innerComposer,
            )
            afterValueIcon?.let { image ->
                renderFixedWidthSpacer(width = 8, composer = innerComposer)
                renderIcon(
                    image = image,
                    modifier = sizeModifier(modifierInstance(), 18),
                    tint = syncIconTint(innerComposer),
                    composer = innerComposer,
                )
            }
            secondaryValue?.takeIf { it.isNotBlank() }?.let { text ->
                renderFixedWidthSpacer(width = 4, composer = innerComposer)
                renderDetailsText(
                    text = text,
                    modifier = null,
                    color = colorScheme(innerComposer).longMethod("getOnBackground"),
                    composer = innerComposer,
                )
            }
            trailing?.let { image ->
                renderIcon(
                    image = image,
                    modifier = paddingModifier(
                        sizeModifier(modifierInstance(), 20),
                        start = 4,
                        top = 0,
                        end = 0,
                        bottom = 0,
                    ),
                    tint = colorScheme(innerComposer).longMethod("getSurfaceContainerHighest"),
                    composer = innerComposer,
                )
            }
            targetUnit()
        }
        method(ROW_KT_CLASS, ROW_METHOD, 7).invoke(
            null,
            modifier,
            arrangementStart(),
            alignmentCenterVertically(),
            content,
            composer,
            384,
            0,
        )
    }

    private fun renderDetailsDivider(composer: Any) {
        simpleDividerMethod().invoke(
            null,
            paddingModifier(modifierInstance(), start = 16, top = 0, end = 16, bottom = 0),
            0L,
            composer,
            0,
            2,
        )
    }

    private fun rowWeightModifier(rowScope: Any, modifier: Any): Any =
        rowScope.javaClass.methods.first {
            it.name == "weight" && it.parameterTypes.size == 3
        }.invoke(rowScope, modifier, 1f, true)

    private fun renderWeightedSpacer(rowScope: Any, composer: Any) {
        method(SPACER_KT_CLASS, SPACER_METHOD, 3).invoke(
            null,
            rowWeightModifier(rowScope, startPaddingModifier(modifierInstance(), 12)),
            composer,
            0,
        )
    }

    private fun renderVerticalSpacer(height: Int, composer: Any) {
        method(SPACER_KT_CLASS, SPACER_METHOD, 3).invoke(
            null,
            heightModifier(modifierInstance(), height),
            composer,
            0,
        )
    }

    private fun renderFixedWidthSpacer(width: Int, composer: Any) {
        method(SPACER_KT_CLASS, SPACER_METHOD, 3).invoke(
            null,
            widthModifier(modifierInstance(), width),
            composer,
            0,
        )
    }

    private fun startPaddingModifier(baseModifier: Any, start: Int): Any =
        method(PADDING_KT_CLASS, PADDING_ABSOLUTE_DEFAULT_METHOD, 7).invoke(
            null,
            baseModifier,
            udp(start),
            0f,
            0f,
            0f,
            14,
            null,
        )

    private fun heightModifier(baseModifier: Any, height: Int): Any =
        method(SIZE_KT_CLASS, HEIGHT_METHOD, 2).invoke(null, baseModifier, udp(height))

    private fun widthModifier(baseModifier: Any, width: Int): Any =
        method(SIZE_KT_CLASS, WIDTH_METHOD, 2).invoke(null, baseModifier, udp(width))

    private fun sizeModifier(baseModifier: Any, size: Int): Any =
        method(SIZE_KT_CLASS, SIZE_METHOD, 2).invoke(null, baseModifier, udp(size))

    private fun paddingModifier(
        baseModifier: Any,
        start: Int,
        top: Int,
        end: Int,
        bottom: Int,
    ): Any =
        method(PADDING_KT_CLASS, PADDING_METHOD, 5).invoke(
            null,
            baseModifier,
            udp(start),
            udp(top),
            udp(end),
            udp(bottom),
        )

    private fun clickableModifier(baseModifier: Any, name: String, onClick: () -> Unit): Any =
        method(CLICKABLE_KT_CLASS, CLICKABLE_DEFAULT_METHOD, 9).invoke(
            null,
            baseModifier,
            null,
            null,
            false,
            null,
            null,
            functionProxy(name, FUNCTION0_CLASS) {
                onClick()
                targetUnit()
            },
            28,
            null,
        )

    private fun editImageVector(): Any? =
        runCatching {
            method(EDIT_ICON_CLASS, "getEdit", 1).invoke(null, staticObject(ICONS_OUTLINED_CLASS, "INSTANCE"))
        }.getOrNull()

    private fun navigateNextImageVector(): Any? =
        runCatching {
            method(NAVIGATE_NEXT_ICON_CLASS, "getNavigateNext", 1).invoke(
                null,
                staticObject(ICONS_AUTO_MIRRORED_FILLED_CLASS, "INSTANCE"),
            )
        }.getOrNull()

    private fun contentCopyImageVector(): Any? =
        runCatching {
            method(CONTENT_COPY_ICON_CLASS, "getContentCopy", 1).invoke(
                null,
                staticObject(ICONS_OUTLINED_CLASS, "INSTANCE"),
            )
        }.getOrNull()

    private fun syncStorageImageVector(book: Any): Any? =
        when (callInt(book, "getBackupType")) {
            1 -> coloredAppIcon(BAIDU_ICON_CLASS, "getBaiduNetdisk")
            2 -> coloredAppIcon(YUN115_ICON_CLASS, "getYun115")
            4 -> coloredAppIcon(ALIYUN_ICON_CLASS, "getAliyun")
            else -> coloredAppIcon(ANDROID_OS_ICON_CLASS, "getAndroidOs")
        }

    private fun coloredAppIcon(className: String, methodName: String): Any? =
        runCatching {
            method(className, methodName, 1).invoke(
                null,
                staticObject(APP_ICONS_COLORED_CLASS, "INSTANCE"),
            )
        }.getOrNull()

    private fun syncIconTint(composer: Any): Long =
        runCatching { colorUnspecified() }.getOrElse { colorScheme(composer).longMethod("getPrimary") }

    private fun colorUnspecified(): Long =
        staticObject(COLOR_CLASS, "Companion").method0("getUnspecified") as Long

    private fun arrangementStart(): Any =
        staticObject(ARRANGEMENT_CLASS, "INSTANCE").method0("getStart")

    private fun arrangementTop(): Any =
        staticObject(ARRANGEMENT_CLASS, "INSTANCE").method0("getTop")

    private fun alignmentCenterVertically(): Any =
        staticObject(ALIGNMENT_CLASS, "INSTANCE").method0("getCenterVertically")

    private fun alignmentStart(): Any =
        staticObject(ALIGNMENT_CLASS, "INSTANCE").method0("getStart")

    private fun themeOnBackgroundVariant(composer: Any): Long =
        method(THEME_KT_CLASS, "getOnBackgroundVariant", 1).invoke(null, colorScheme(composer)) as Long

    private fun textOverflowEllipsis(): Int =
        staticObject(TEXT_OVERFLOW_CLASS, "INSTANCE").method0("getEllipsis") as Int

    private fun textOverflowMiddleEllipsis(): Int =
        staticObject(TEXT_OVERFLOW_CLASS, "INSTANCE").method0("getMiddleEllipsis") as Int

    private fun fillMaxWidthModifier(baseModifier: Any): Any =
        method(SIZE_KT_CLASS, FILL_MAX_WIDTH_DEFAULT_METHOD, 4).invoke(null, baseModifier, 0f, 1, null)

    private fun lazyAnimateItemModifier(lazyItemScope: Any, baseModifier: Any): Any =
        runCatching {
            lazyItemScope.javaClass.methods.firstOrNull {
                it.name == LAZY_ANIMATE_ITEM_DEFAULT_METHOD && it.parameterTypes.size == 6
            }?.invoke(null, lazyItemScope, baseModifier, null, null, null, 7, null)
                ?: baseModifier
        }.getOrDefault(baseModifier)

    private fun clipModifier(baseModifier: Any, shape: Any): Any =
        method(CLIP_KT_CLASS, CLIP_METHOD, 2).invoke(null, baseModifier, shape)

    private fun borderModifier(baseModifier: Any, width: Float, color: Long, shape: Any): Any =
        method(BORDER_KT_CLASS, BORDER_METHOD, 4).invoke(null, baseModifier, width, color, shape)

    private fun backgroundModifier(baseModifier: Any, color: Long): Any =
        method(BACKGROUND_KT_CLASS, BACKGROUND_DEFAULT_METHOD, 5).invoke(null, baseModifier, color, null, 2, null)

    private fun roundedShape(): Any =
        method(SHAPE_KT_CLASS, ROUNDED_SHAPE_METHOD, 0).invoke(null)

    private fun themeBackgroundAuto(composer: Any): Long =
        method(THEME_KT_CLASS, BACKGROUND_AUTO_METHOD, 1).invoke(null, colorScheme(composer)) as Long

    private fun colorScheme(composer: Any): Any {
        val materialTheme = staticObject(MATERIAL_THEME_CLASS, "INSTANCE")
        val stable = staticInt(MATERIAL_THEME_CLASS, "\$stable")
        return method(MATERIAL_THEME_CLASS, "getColorScheme", 2).invoke(materialTheme, composer, stable)
    }

    private fun typography(composer: Any): Any {
        val materialTheme = staticObject(MATERIAL_THEME_CLASS, "INSTANCE")
        val stable = staticInt(MATERIAL_THEME_CLASS, "\$stable")
        return method(MATERIAL_THEME_CLASS, "getTypography", 2).invoke(materialTheme, composer, stable)
    }

    private fun modifierInstance(): Any =
        staticObject(MODIFIER_CLASS, "INSTANCE")

    private fun udp(value: Int): Float =
        cls(UNIT_EXT_KT_CLASS).declaredMethods.first {
            it.name == UDP_METHOD && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }.invoke(null, value) as Float

    private fun simpleDividerMethod(): Method =
        synchronized(methodCache) {
            methodCache.getOrPut("$DIVIDER_KT_CLASS#SimpleDivider/5") {
                cls(DIVIDER_KT_CLASS).declaredMethods.firstOrNull {
                    it.name.contains("SimpleDivider") && it.parameterTypes.size == 5
                }?.apply { isAccessible = true }
                    ?: error("SimpleDivider method not found")
            }
        }

    private fun composableLambda(key: Int, functionClassName: String, block: (Array<Any?>?) -> Any?): Any =
        method(COMPOSABLE_LAMBDA_KT_CLASS, COMPOSABLE_LAMBDA_METHOD, 3).invoke(
            null,
            key,
            true,
            functionProxy("Composable$key", functionClassName, block),
        )

    private fun functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any {
        val functionClass = cls(functionClassName)
        return Proxy.newProxyInstance(classLoader, arrayOf(functionClass)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> runCatching {
                    block(args)
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX failed in $name callback: ${it.stackTraceToString()}")
                }.getOrElse { targetUnit() }
                "toString" -> "ReaMicro$name"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
    }

    private fun cls(className: String): Class<*> =
        XposedHelpers.findClass(className, classLoader)

    private fun method(className: String, methodName: String, parameterCount: Int): Method {
        val cacheKey = "$className#$methodName/$parameterCount"
        return synchronized(methodCache) {
            methodCache.getOrPut(cacheKey) {
                cls(className).declaredMethods.firstOrNull {
                    it.name == methodName && it.parameterTypes.size == parameterCount
                }?.apply { isAccessible = true }
                    ?: error("$className.$methodName/$parameterCount not found")
            }
        }
    }

    private fun staticObject(className: String, fieldName: String): Any {
        val clazz = cls(className)
        val field = runCatching { clazz.getDeclaredField(fieldName) }
            .recoverCatching { clazz.getField(fieldName) }
            .recoverCatching { clazz.getDeclaredField("Companion") }
            .getOrElse {
                val fields = clazz.declaredFields.joinToString { field -> "${field.name}:${field.type.name}" }
                error("$className.$fieldName not found; fields=[$fields]")
            }
        field.isAccessible = true
        return field.get(null)
    }

    private fun staticInt(className: String, fieldName: String): Int =
        cls(className).getDeclaredField(fieldName).apply { isAccessible = true }.getInt(null)

    private fun Any.method0(name: String): Any =
        javaClass.methods.first {
            it.parameterTypes.isEmpty() && (it.name == name || it.name.startsWith("$name-"))
        }.invoke(this)

    private fun Any.longMethod(name: String): Long =
        method0(name) as Long

    private fun targetUnit(): Any? = runCatching {
        staticObject("kotlin.Unit", "INSTANCE")
    }.getOrNull()

    private fun callString(target: Any, methodName: String): String =
        runCatching { XposedHelpers.callMethod(target, methodName)?.toString().orEmpty() }.getOrDefault("")

    private fun callLong(target: Any, methodName: String): Long =
        runCatching { (XposedHelpers.callMethod(target, methodName) as? Number)?.toLong() ?: 0L }.getOrDefault(0L)

    private fun callInt(target: Any, methodName: String): Int =
        runCatching { (XposedHelpers.callMethod(target, methodName) as? Number)?.toInt() ?: 0 }.getOrDefault(0)

    private fun isEditableFile(file: File): Boolean =
        file.isFile && file.extension.lowercase(Locale.ROOT) in EDITABLE_EXTENSIONS

    private fun File.canonicalPathSafe(): String =
        runCatching { canonicalFile.absolutePath }.getOrDefault(absolutePath)

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.1f GB", mb / 1024.0)
    }

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val BOOK_LOCAL_SHEET_CLASS = "app.zhendong.reamicro.ui.home.components.BookLocalSheetKt"
        const val BOOK_OVERVIEW_ITEMS_CLASS = "app.zhendong.reamicro.ui.home.components.BookOverviewItemsKt"
        const val BOOK_CLASS = "app.zhendong.reamicro.data.db.entity.Book"
        const val BOOK_LOCAL_SHEET_METHOD = "BookLocalSheet"
        const val BOOK_LOCAL_SHEET_CONTENT_METHOD = "BookLocalSheet\$lambda\$2"
        const val FILE_BACKUP_METHOD = "FileBackup"
        const val BOOK_TITLE_AUTHOR_ITEM_METHOD = "BookTitleAuthorItem"
        const val BOOK_SYNC_SIZE_ITEM_METHOD = "BookSyncSizeItem"
        const val BACKUP_TYPE_CLASS = "app.zhendong.reamicro.constants.BackupType"

        const val ROW_KT_CLASS = "androidx.compose.foundation.layout.RowKt"
        const val ROW_METHOD = "Row"
        const val COLUMN_KT_CLASS = "androidx.compose.foundation.layout.ColumnKt"
        const val COLUMN_METHOD = "Column"
        const val SPACER_KT_CLASS = "androidx.compose.foundation.layout.SpacerKt"
        const val SPACER_METHOD = "Spacer"
        const val ARRANGEMENT_CLASS = "androidx.compose.foundation.layout.Arrangement"
        const val ALIGNMENT_CLASS = "androidx.compose.ui.Alignment"
        const val LIST_ITEM_KT_CLASS = "androidx.compose.material3.ListItemKt"
        const val LIST_ITEM_METHOD = "ListItem-HXNGIdc"
        const val LIST_ITEM_DEFAULTS_CLASS = "androidx.compose.material3.ListItemDefaults"
        const val LIST_ITEM_COLORS_METHOD = "colors-J08w3-E"
        const val TEXT_KT_CLASS = "androidx.compose.material3.TextKt"
        const val TEXT_METHOD = "Text-Nvy7gAk"
        const val TEXT_OVERFLOW_CLASS = "androidx.compose.ui.text.style.TextOverflow"
        const val ICON_KT_CLASS = "androidx.compose.material3.IconKt"
        const val ICON_METHOD = "Icon-ww6aTOc"
        const val IMAGE_VECTOR_CLASS = "androidx.compose.ui.graphics.vector.ImageVector"
        const val EDIT_ICON_CLASS = "androidx.compose.material.icons.outlined.EditKt"
        const val CONTENT_COPY_ICON_CLASS = "androidx.compose.material.icons.outlined.ContentCopyKt"
        const val NAVIGATE_NEXT_ICON_CLASS = "androidx.compose.material.icons.automirrored.filled.NavigateNextKt"
        const val ICONS_OUTLINED_CLASS = "androidx.compose.material.icons.Icons\$Outlined"
        const val ICONS_AUTO_MIRRORED_FILLED_CLASS = "androidx.compose.material.icons.Icons\$AutoMirrored\$Filled"
        const val APP_ICONS_COLORED_CLASS = "app.zhendong.reamicro.arch.icons.AppIcons\$Colored"
        const val ANDROID_OS_ICON_CLASS = "app.zhendong.reamicro.arch.icons.colored.AndroidOsKt"
        const val BAIDU_ICON_CLASS = "app.zhendong.reamicro.arch.icons.colored.BaiduNetdiskKt"
        const val YUN115_ICON_CLASS = "app.zhendong.reamicro.arch.icons.colored.Yun115Kt"
        const val ALIYUN_ICON_CLASS = "app.zhendong.reamicro.arch.icons.colored.AliyunKt"
        const val DIVIDER_KT_CLASS = "app.zhendong.reamicro.arch.components.DividerKt"
        const val SIZE_KT_CLASS = "androidx.compose.foundation.layout.SizeKt"
        const val HEIGHT_METHOD = "height-3ABfNKs"
        const val WIDTH_METHOD = "width-3ABfNKs"
        const val SIZE_METHOD = "size-3ABfNKs"
        const val FILL_MAX_WIDTH_DEFAULT_METHOD = "fillMaxWidth\$default"
        const val PADDING_KT_CLASS = "androidx.compose.foundation.layout.PaddingKt"
        const val PADDING_METHOD = "padding-qDBjuR0"
        const val PADDING_ABSOLUTE_DEFAULT_METHOD = "padding-qDBjuR0\$default"
        const val CLICKABLE_KT_CLASS = "androidx.compose.foundation.ClickableKt"
        const val CLICKABLE_DEFAULT_METHOD = "clickable-O2vRcR0\$default"
        const val CLIP_KT_CLASS = "androidx.compose.ui.draw.ClipKt"
        const val CLIP_METHOD = "clip"
        const val BORDER_KT_CLASS = "androidx.compose.foundation.BorderKt"
        const val BORDER_METHOD = "border-xT4_qwU"
        const val BACKGROUND_KT_CLASS = "androidx.compose.foundation.BackgroundKt"
        const val BACKGROUND_DEFAULT_METHOD = "background-bw27NRU\$default"
        const val MATERIAL_THEME_CLASS = "androidx.compose.material3.MaterialTheme"
        const val THEME_KT_CLASS = "app.zhendong.reamicro.arch.theme.ThemeKt"
        const val SHAPE_KT_CLASS = "app.zhendong.reamicro.arch.components.ShapeKt"
        const val ROUNDED_SHAPE_METHOD = "getRoundedShape"
        const val BACKGROUND_AUTO_METHOD = "getBackgroundAuto"
        const val COLOR_CLASS = "androidx.compose.ui.graphics.Color"
        const val COLOR_TRANSPARENT_METHOD = "getTransparent-0d7_KjU"
        const val MODIFIER_CLASS = "androidx.compose.ui.Modifier"
        const val LAZY_ANIMATE_ITEM_DEFAULT_METHOD = "animateItem\$default"
        const val UNIT_EXT_KT_CLASS = "app.zhendong.reamicro.arch.extensions.UnitExtKt"
        const val UDP_METHOD = "getUdp"
        const val COMPOSABLE_LAMBDA_KT_CLASS = "androidx.compose.runtime.internal.ComposableLambdaKt"
        const val COMPOSABLE_LAMBDA_METHOD = "composableLambdaInstance"
        const val FUNCTION0_CLASS = "kotlin.jvm.functions.Function0"
        const val FUNCTION2_CLASS = "kotlin.jvm.functions.Function2"
        const val FUNCTION3_CLASS = "kotlin.jvm.functions.Function3"
        const val TEXT_DEFAULT_MASK_WITH_MODIFIER = 131064
        const val TEXT_SECONDARY_SINGLE_LINE_MASK = 110586
        const val TEXT_SECONDARY_SINGLE_LINE_MASK_WITH_MODIFIER = 110584
        const val FILE_EDIT_TITLE_KEY = 0x524D4701
        const val FILE_EDIT_SUPPORTING_KEY = 0x524D4702
        const val FILE_EDIT_LEADING_KEY = 0x524D4703
        const val FILE_EDIT_TRAILING_KEY = 0x524D4704
        const val MAX_EDITABLE_FILE_BYTES = 3L * 1024L * 1024L
        val EDITABLE_EXTENSIONS = setOf(
            "html",
            "htm",
            "xhtml",
            "xml",
            "opf",
            "ncx",
            "css",
            "txt",
            "js",
            "json",
            "svg",
            "md",
        )
        val UUID_IDENTIFIER_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val MD5_IDENTIFIER_REGEX = Regex("^[0-9a-fA-F]{32}$")
    }
}

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()

private fun roundedDrawable(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radiusDp * Resources.getSystem().displayMetrics.density
        if (stroke != Color.TRANSPARENT) {
            setStroke(1, stroke)
        }
    }

private fun selectableItemBackground(context: Context): android.graphics.drawable.Drawable? {
    val value = TypedValue()
    return if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            context.getDrawable(value.resourceId)
        } else {
            @Suppress("DEPRECATION")
            context.resources.getDrawable(value.resourceId)
        }
    } else {
        null
    }
}

private fun withAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

private fun blend(background: Int, foreground: Int, ratio: Float): Int {
    val clamped = ratio.coerceIn(0f, 1f)
    val inverse = 1f - clamped
    return Color.rgb(
        (Color.red(background) * inverse + Color.red(foreground) * clamped).toInt(),
        (Color.green(background) * inverse + Color.green(foreground) * clamped).toInt(),
        (Color.blue(background) * inverse + Color.blue(foreground) * clamped).toInt(),
    )
}
