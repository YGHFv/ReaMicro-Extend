package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XCallback
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

object OnlineCompletionImportSignal {
    private data class Mark(
        val uuid: String,
        val title: String,
        val uri: String,
        val createdAtMs: Long,
    )

    private val marks = ConcurrentHashMap<String, Mark>()

    fun remember(uuid: String, title: String, uri: String) {
        cleanup()
        val mark = Mark(
            uuid = uuid.trim(),
            title = title.normalizedSignalTitle(),
            uri = uri.trim(),
            createdAtMs = System.currentTimeMillis(),
        )
        listOf(mark.uuid, mark.uri, mark.title)
            .filter { it.isNotBlank() }
            .forEach { marks[it] = mark }
    }

    fun forget(uuid: String, title: String, uri: String) {
        listOf(uuid.trim(), uri.trim(), title.normalizedSignalTitle())
            .filter { it.isNotBlank() }
            .forEach { marks.remove(it) }
    }

    fun matches(uuid: String, title: String, uri: String): Boolean {
        cleanup()
        return listOf(uuid.trim(), uri.trim(), title.normalizedSignalTitle())
            .any { it.isNotBlank() && marks.containsKey(it) }
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        marks.entries.removeAll { (_, mark) -> now - mark.createdAtMs > MARK_TTL_MS }
    }

    private fun String.normalizedSignalTitle(): String =
        trim().replace(Regex("\\s+"), " ")

    private const val MARK_TTL_MS = 30 * 60_000L
}

class ReaderImportOverwriteHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
) {
    private var repositoryRef: WeakReference<Any>? = null
    private val pendingPreImportDecisions = ConcurrentHashMap<String, PreImportDecision>()

    fun install() {
        runCatching {
            val bookshelfClass = XposedHelpers.findClass(BOOKSHELF_REPOSITORY_CLASS, classLoader)
            XposedBridge.hookAllConstructors(bookshelfClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    repositoryRef = WeakReference(param.thisObject)
                    ReaMicroBookMetadataSync.rememberBookshelfRepository(param.thisObject)
                }
            })
            hookWorkerManagerRepositoryCapture()
            XposedBridge.hookAllMethods(bookshelfClass, BOOKSHELF_IMPORT_BOOK_METHOD, object : XC_MethodHook(XCallback.PRIORITY_LOWEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val snapshot = settingsProvider()
                    if (!snapshot.canRunReaderOverwriteCheck) return
                    repositoryRef = WeakReference(param.thisObject)
                    ReaMicroBookMetadataSync.rememberBookshelfRepository(param.thisObject)
                    val opf = param.args?.getOrNull(2) ?: return
                    val uriOverride = param.args?.getOrNull(3)?.toString().orEmpty().trim()
                    val title = resolveImportTitle(opf, param.args?.getOrNull(0)) ?: "未命名"
                    val uuid = resolveImportUuid(opf).orEmpty()
                    if (uuid.isBlank() && uriOverride.isBlank() && title.isBlank()) return
                    val signaledOnlineImport = OnlineCompletionImportSignal.matches(uuid, title, uriOverride)

                    consumePreImportDecision(uuid, title)?.let { preDecision ->
                        applyPreImportDecision(param, opf, uuid, preDecision)
                        return
                    }

                    val conflict = findImportConflict(param.thisObject, uuid, uriOverride, title) ?: return
                    XposedBridge.log(
                        "$LOG_PREFIX overwrite check intercepted: title=$title, uuid=$uuid, uri=$uriOverride, conflict=$conflict",
                    )

                    val decision = if (signaledOnlineImport || isOnlineCompletionImport(uuid, uriOverride, conflict)) {
                        XposedBridge.log(
                            "$LOG_PREFIX online completion overwrite forced silently: title=$title, " +
                                "uuid=$uuid uri=$uriOverride signaled=$signaledOnlineImport oldBook=${conflict.oldUuid}",
                        )
                        OverwriteDecision.OVERWRITE
                    } else {
                        showOverwriteConfirm(conflict.oldTitle, title)
                    }
                    when (decision) {
                        OverwriteDecision.OVERWRITE -> {
                            val oldUuid = conflict.oldUuid
                            if (!oldUuid.isNullOrBlank() && oldUuid != uuid) {
                                val newOpf = opf.withUuid(oldUuid)
                                param.args[2] = newOpf
                                prepareBookDirForOverwriteUuid(param.args?.getOrNull(1), oldUuid, newOpf)?.let { oldBookDir ->
                                    param.args[1] = oldBookDir
                                }
                            }
                            conflict.oldUri?.takeIf { it.isNotBlank() }?.let { oldUri ->
                                param.args[3] = oldUri
                            }
                            ReaMicroBookMetadataSync.syncBookMetadataAsync(
                                repository = param.thisObject,
                                book = conflict.oldBook,
                                patch = ReaMicroBookMetadataSync.metadataFromOpf(param.args?.getOrNull(2))
                                    .withSize(importBookSize(param.args)),
                                delayMs = POST_IMPORT_METADATA_SYNC_DELAY_MS,
                            )
                            XposedBridge.log(
                                "$LOG_PREFIX overwrite import forced: uuid=$uuid -> ${conflict.oldUuid}, " +
                                    "uri=${param.args?.getOrNull(3)}, bookDir=${param.args?.getOrNull(1)}",
                            )
                        }
                        OverwriteDecision.INDEPENDENT -> {
                            if (conflict.byUuid) {
                                val newUuid = UUID.randomUUID().toString()
                                val newOpf = opf.withUuid(newUuid)
                                param.args[2] = newOpf
                                copyBookDirForIndependentUuid(param.args?.getOrNull(1), newUuid, newOpf)?.let { newBookDir ->
                                    param.args[1] = newBookDir
                                }
                                XposedBridge.log("$LOG_PREFIX independent import generated uuid and book dir: $uuid -> $newUuid")
                            }
                            if (conflict.byUrl && uriOverride.isNotBlank()) {
                                param.args[3] = "$uriOverride#reamicro-independent-${System.currentTimeMillis()}"
                            }
                        }
                        OverwriteDecision.CANCEL -> {
                            cancelImport(param)
                            XposedBridge.log("$LOG_PREFIX overwrite import cancelled: title=$title, uuid=$uuid")
                            return
                        }
                    }
                    XposedBridge.log("$LOG_PREFIX overwrite import decision applied: title=$title, uuid=$uuid")
                }
            })
            hookEpubFileManagerImport()
            XposedBridge.log("$LOG_PREFIX reader overwrite check hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook reader overwrite check: ${it.stackTraceToString()}")
        }
    }

    private fun hookWorkerManagerRepositoryCapture() {
        runCatching {
            val workerClass = XposedHelpers.findClass(WORKER_MANAGER_CLASS, classLoader)
            XposedBridge.hookAllConstructors(workerClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.thisObject.fieldValue("bookshelf")?.let { repository ->
                        repositoryRef = WeakReference(repository)
                        ReaMicroBookMetadataSync.rememberBookshelfRepository(repository)
                        XposedBridge.log("$LOG_PREFIX captured bookshelf repository from worker manager")
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook worker manager repository capture: ${it.stackTraceToString()}")
        }
    }

    private fun hookEpubFileManagerImport() {
        runCatching {
            val managerClass = XposedHelpers.findClass(EPUB_FILE_MANAGER_CLASS, classLoader)
            val importMethod = (managerClass.methods.asSequence() + managerClass.declaredMethods.asSequence())
                .firstOrNull { candidate ->
                    candidate.parameterTypes.size == 2 &&
                        candidate.parameterTypes.all { it.name == OKIO_PATH_CLASS } &&
                        candidate.returnType.name == KOTLIN_PAIR_CLASS
                }
                ?.apply { isAccessible = true }
                ?: return@runCatching
            XposedBridge.hookMethod(importMethod, object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val snapshot = settingsProvider()
                    if (!snapshot.canRunReaderOverwriteCheck) return
                    val repository = repositoryRef?.get() ?: return
                    val unzipDir = param.args?.getOrNull(0) ?: return
                    val opf = obtainOpf(unzipDir) ?: return
                    val title = resolveImportTitle(opf, null) ?: return
                    val uuid = resolveImportUuid(opf).orEmpty()
                    val signaledOnlineImport = OnlineCompletionImportSignal.matches(uuid, title, "")
                    val conflict = findImportConflict(repository, uuid, "", title) ?: return
                    val existingBook = conflict.oldBook
                    XposedBridge.log(
                        "$LOG_PREFIX pre-import conflict intercepted: title=$title, uuid=$uuid, conflict=$conflict",
                    )
                    val decision = if (signaledOnlineImport || isOnlineCompletionImport(uuid, "", conflict)) {
                        XposedBridge.log(
                            "$LOG_PREFIX online completion pre-import overwrite forced silently: " +
                                "title=$title, uuid=$uuid signaled=$signaledOnlineImport oldBook=${conflict.oldUuid}",
                        )
                        OverwriteDecision.OVERWRITE
                    } else {
                        showOverwriteConfirm(conflict.oldTitle, title)
                    }
                    val preDecision = PreImportDecision(
                        decision = decision,
                        oldTitle = conflict.oldTitle,
                        oldUuid = conflict.oldUuid,
                        oldUri = null,
                        oldBook = existingBook,
                        metadataPatch = ReaMicroBookMetadataSync.metadataFromOpf(opf),
                        createdAtMs = System.currentTimeMillis(),
                    )
                    rememberPreImportDecision(uuid, title, preDecision)
                    when (decision) {
                        OverwriteDecision.OVERWRITE -> {
                            val oldUuid = conflict.oldUuid
                            if (!oldUuid.isNullOrBlank() && oldUuid != uuid) {
                                val newOpf = opf.withUuid(oldUuid)
                                newOpf.overwriteToRoot(unzipDir)
                                rememberPreImportDecision(oldUuid, title, preDecision)
                                XposedBridge.log("$LOG_PREFIX pre-import opf uuid rewritten: $uuid -> $oldUuid")
                            }
                            clearExistingBookDirContentsBeforeImport(param.args?.getOrNull(1), oldUuid ?: uuid)
                        }
                        OverwriteDecision.INDEPENDENT -> {
                            val newUuid = UUID.randomUUID().toString()
                            val newOpf = opf.withUuid(newUuid)
                            newOpf.overwriteToRoot(unzipDir)
                            rememberPreImportDecision(newUuid, title, preDecision)
                            XposedBridge.log("$LOG_PREFIX pre-import independent uuid generated: $uuid -> $newUuid, title=$title")
                        }
                        OverwriteDecision.CANCEL -> {
                            cancelImport(param)
                            XposedBridge.log("$LOG_PREFIX pre-import conflict cancelled: title=$title, uuid=$uuid")
                        }
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX epub file manager import hook installed: ${importMethod.name}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook epub file manager import: ${it.stackTraceToString()}")
        }
    }

    private fun applyPreImportDecision(
        param: XC_MethodHook.MethodHookParam,
        opf: Any,
        uuid: String,
        preDecision: PreImportDecision,
    ) {
        when (preDecision.decision) {
            OverwriteDecision.OVERWRITE -> {
                val oldUuid = preDecision.oldUuid
                if (!oldUuid.isNullOrBlank() && oldUuid != uuid) {
                    val newOpf = opf.withUuid(oldUuid)
                    param.args[2] = newOpf
                    prepareBookDirForOverwriteUuid(param.args?.getOrNull(1), oldUuid, newOpf)?.let { oldBookDir ->
                        param.args[1] = oldBookDir
                    }
                }
                preDecision.oldUri?.takeIf { it.isNotBlank() }?.let { oldUri ->
                    param.args[3] = oldUri
                }
                ReaMicroBookMetadataSync.syncBookMetadataAsync(
                    repository = param.thisObject,
                    book = preDecision.oldBook,
                    patch = ReaMicroBookMetadataSync.metadataFromOpf(param.args?.getOrNull(2))
                        .mergeWith(preDecision.metadataPatch)
                        .withSize(importBookSize(param.args)),
                    delayMs = POST_IMPORT_METADATA_SYNC_DELAY_MS,
                )
                XposedBridge.log(
                    "$LOG_PREFIX importBook reused pre-import overwrite decision: uuid=$uuid -> ${preDecision.oldUuid}",
                )
            }
            OverwriteDecision.INDEPENDENT -> {
                XposedBridge.log("$LOG_PREFIX importBook reused pre-import independent decision: uuid=$uuid")
            }
            OverwriteDecision.CANCEL -> {
                cancelImport(param)
            }
        }
    }

    private fun findImportConflict(repository: Any, uuid: String, uriOverride: String, title: String): ImportConflict? {
        val uuidBook = if (uuid.isNotBlank()) findExistingBookByUuid(repository, uuid) else null
        val duplicateByUuid = uuidBook != null
        val urlBook = if (uriOverride.isNotBlank()) {
            runCatching { invokeSuspendBlocking(method(repository.javaClass, "findBookByUrl", 2), repository, uriOverride) }
                .getOrNull()
                ?.takeIf { it.hasLocalBookData() }
        } else {
            null
        }
        val titleBook = findExistingBookByExactTitle(repository, title, uuid)
        val existingBook = titleBook ?: urlBook ?: uuidBook
        if (!duplicateByUuid && urlBook == null && titleBook == null) return null
        return ImportConflict(
            oldBook = existingBook,
            oldTitle = existingBook.titleOrNull() ?: title,
            oldUuid = existingBook.uuidOrNull(),
            oldUri = if (titleBook != null) null else existingBook.uriOrNull(),
            byUuid = duplicateByUuid,
            byUrl = urlBook != null,
            byTitle = titleBook != null,
        )
    }

    private fun resolveImportTitle(opf: Any?, file: Any?): String? =
        sequenceOf(
            runCatching { callStringPath(opf, "metadata", "title", "value") }.getOrNull(),
            runCatching { resolveImportTitleFromFile(file) }.getOrNull(),
            runCatching { callStringPath(file, "name") }.getOrNull(),
        ).firstOrNull { !it.isNullOrBlank() }

    private fun resolveImportUuid(opf: Any?): String? =
        sequenceOf(
            runCatching { callStringPath(opf, "metadata", "uuid", "value") }.getOrNull(),
            runCatching { callStringPath(opf, "metadata", "getUuid", "value") }.getOrNull(),
            runCatching { resolveReaMicroMd5Identifier(opf) }.getOrNull(),
        ).firstOrNull { !it.isNullOrBlank() }

    private fun isOnlineCompletionUuid(uuid: String): Boolean =
        uuid.startsWith(ONLINE_COMPLETION_UUID_PREFIX)

    private fun isOnlineCompletionImport(uuid: String, uriOverride: String, conflict: ImportConflict): Boolean =
        isOnlineCompletionUuid(uuid) ||
            isOnlineCompletionPath(uriOverride) ||
            isOnlineCompletionBook(conflict.oldBook)

    private fun isOnlineCompletionBook(book: Any?): Boolean =
        isOnlineCompletionPath(book.stringOrNull("getBackupId").orEmpty()) ||
            isOnlineCompletionPath(book.uriOrNull().orEmpty()) ||
            isOnlineCompletionUuid(book.uuidOrNull().orEmpty()) ||
            (book.intOrNull("getBackupType") == BACKUP_TYPE_ONLINE_COMPLETION &&
                book.stringOrNull("getBackupCode").orEmpty().startsWith("online_"))

    private fun isOnlineCompletionPath(value: String): Boolean =
        value.startsWith(ONLINE_COMPLETION_BOOK_PREFIX) ||
            value.startsWith(ONLINE_COMPLETION_SOURCE_PREFIX)

    private fun resolveReaMicroMd5Identifier(opf: Any?): String? {
        val metadata = noArgValue(opf, "getMetadata") ?: noArgValue(opf, "metadata") ?: return null
        val metas = noArgValue(metadata, "getMetas") ?: noArgValue(metadata, "metas") ?: return null
        return (metas as? Iterable<*>)
            ?.firstNotNullOfOrNull { meta ->
                val property = callStringPath(meta, "property")?.trim()
                    ?: callStringPath(meta, "getProperty")?.trim()
                if (!property.equals("reamicro:md5", ignoreCase = true)) return@firstNotNullOfOrNull null
                sequenceOf(
                    callStringPath(meta, "value"),
                    callStringPath(meta, "content"),
                    callStringPath(meta, "getValue"),
                    callStringPath(meta, "getContent"),
                )
                    .mapNotNull { it?.trim() }
                    .firstOrNull { REAMICRO_MD5_REGEX.matches(it) }
            }
    }

    private fun showOverwriteConfirm(oldTitle: String, newTitle: String): OverwriteDecision {
        val activity = activityProvider() ?: run {
            XposedBridge.log("$LOG_PREFIX no activity for overwrite confirmation; allowing import")
            return OverwriteDecision.OVERWRITE
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            XposedBridge.log("$LOG_PREFIX overwrite confirmation requested on main thread; allowing import")
            return OverwriteDecision.OVERWRITE
        }
        val latch = CountDownLatch(1)
        var decision = OverwriteDecision.CANCEL
        activity.runOnUiThread {
            showOverwriteDialog(activity, oldTitle, newTitle) { result ->
                decision = result
                latch.countDown()
            }
        }
        latch.await()
        return decision
    }

    private fun showOverwriteDialog(
        activity: Activity,
        oldTitle: String,
        newTitle: String,
        onResult: (OverwriteDecision) -> Unit,
    ) {
        var resolved = false
        val dialog = Dialog(activity)
        val dp = activity.resources.displayMetrics.density
        val colors = DialogColors(activity)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * dp).toInt(), (22 * dp).toInt(), (22 * dp).toInt(), (18 * dp).toInt())
            background = roundedDrawable(colors.card, 8 * dp)
        }
        card.addView(TextView(activity).apply {
            text = "原：$oldTitle\n新：$newTitle"
            textSize = 19f
            setTextColor(colors.title)
            includeFontPadding = false
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (16 * dp).toInt() }
        })
        card.addView(actionButton(activity, "覆盖导入", colors.primarySoft, colors.primaryText) {
            resolved = true
            onResult(OverwriteDecision.OVERWRITE)
            dialog.dismiss()
        })
        card.addView(actionButton(activity, "独立导入", colors.neutralSoft, colors.neutralText) {
            resolved = true
            onResult(OverwriteDecision.INDEPENDENT)
            dialog.dismiss()
        })
        card.addView(actionButton(activity, "取消导入", colors.dangerSoft, colors.dangerText) {
            resolved = true
            onResult(OverwriteDecision.CANCEL)
            dialog.dismiss()
        })
        dialog.setContentView(card)
        dialog.setOnCancelListener {
            if (!resolved) {
                resolved = true
                onResult(OverwriteDecision.CANCEL)
            }
        }
        dialog.setOnDismissListener {
            if (!resolved) {
                resolved = true
                onResult(OverwriteDecision.CANCEL)
            }
        }
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.42f)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.86f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun actionButton(
        context: Context,
        label: String,
        backgroundColor: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView {
        val dp = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            includeFontPadding = false
            background = roundedDrawable(backgroundColor, 8 * dp)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (56 * dp).toInt(),
            ).apply { topMargin = (10 * dp).toInt() }
        }
    }

    private fun roundedDrawable(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

    private class DialogColors(context: Context) {
        private val palette = ModuleDialogTheme.palette(context)
        val card: Int = palette.pageBackground
        val title: Int = palette.title
        val primarySoft: Int = palette.rowBackground
        val primaryText: Int = palette.primaryText
        val neutralSoft: Int = palette.rowBackground
        val neutralText: Int = palette.neutralText
        val dangerSoft: Int = palette.rowBackground
        val dangerText: Int = palette.destructiveText
    }

    private fun findExistingBookByExactTitle(repository: Any, title: String, importUuid: String): Any? {
        val normalizedTitle = title.normalizedBookTitle()
        if (normalizedTitle.isBlank()) return null
        val listedBook = listExistingBooks(repository)?.firstOrNull { book ->
            val candidateTitle = book.titleOrNull()?.normalizedBookTitle()
            val candidateUuid = book.uuidOrNull().orEmpty()
            candidateTitle == normalizedTitle && candidateUuid != importUuid && book.hasLocalBookData()
        }
        if (listedBook != null) return listedBook

        val user = currentUser(repository) ?: return null
        val uid = user.longOrNull("getId") ?: return null
        val bookDao = repository.fieldValue("bookDao") ?: return null
        val searchMethod = (bookDao.javaClass.methods.asSequence() + bookDao.javaClass.declaredMethods.asSequence())
            .firstOrNull { candidate ->
                candidate.name == "search" &&
                    candidate.parameterTypes.size == 3 &&
                    candidate.parameterTypes[1] == String::class.java
            }
            ?.apply { isAccessible = true }
            ?: return null
        val result = runCatching { invokeSuspendBlocking(searchMethod, bookDao, uid, title) }
            .onFailure {
                XposedBridge.log("$LOG_PREFIX failed to search existing title: ${it.stackTraceToString()}")
            }
            .getOrNull()
        val candidates = result as? Iterable<*> ?: return null
        return candidates.firstOrNull { book ->
            val candidateTitle = book.titleOrNull()?.normalizedBookTitle()
            val candidateUuid = book.uuidOrNull().orEmpty()
            candidateTitle == normalizedTitle && candidateUuid != importUuid && book.hasLocalBookData()
        }
    }

    private fun listExistingBooks(repository: Any): Iterable<*>? {
        val user = currentUser(repository) ?: return null
        val uid = user.longOrNull("getId") ?: return null
        val bookDao = repository.fieldValue("bookDao") ?: return null
        val listMethod = (bookDao.javaClass.methods.asSequence() + bookDao.javaClass.declaredMethods.asSequence())
            .firstOrNull { candidate ->
                candidate.name == "listByUid" &&
                    candidate.parameterTypes.size == 1 &&
                    candidate.parameterTypes[0] == java.lang.Long.TYPE
            }
            ?.apply { isAccessible = true }
            ?: return null
        val flow = runCatching { listMethod.invoke(bookDao, uid) }
            .onFailure {
                XposedBridge.log("$LOG_PREFIX failed to get book list flow: ${it.stackTraceToString()}")
            }
            .getOrNull()
            ?: return null
        val flowKt = XposedHelpers.findClass(FLOW_KT_CLASS, classLoader)
        val firstMethod = (flowKt.methods.asSequence() + flowKt.declaredMethods.asSequence())
            .firstOrNull { it.name == "first" && it.parameterTypes.size == 2 }
            ?.apply { isAccessible = true }
            ?: return null
        return runCatching { invokeSuspendBlocking(firstMethod, null, flow) as? Iterable<*> }
            .onFailure {
                XposedBridge.log("$LOG_PREFIX failed to collect existing books: ${it.stackTraceToString()}")
            }
            .getOrNull()
    }

    private fun findExistingBookByUuid(repository: Any, uuid: String): Any? {
        val user = currentUser(repository) ?: return null
        val uid = user.longOrNull("getId") ?: return null
        val bookDao = repository.fieldValue("bookDao") ?: return null
        val findMethod = (bookDao.javaClass.methods.asSequence() + bookDao.javaClass.declaredMethods.asSequence())
            .firstOrNull { candidate ->
                candidate.name == "findByUidAndUuid" &&
                    candidate.parameterTypes.size == 3 &&
                    candidate.parameterTypes[1] == String::class.java
            }
            ?.apply { isAccessible = true }
            ?: return null
        return runCatching { invokeSuspendBlocking(findMethod, bookDao, uid, uuid) }
            .getOrNull()
            ?.takeIf { it.hasLocalBookData() }
    }

    private fun currentUser(repository: Any): Any? =
        runCatching {
            val flow = method(repository.javaClass, "getCurrentUserFlow", 0).invoke(repository)
                ?: repository.fieldValue("currentUserFlow")
                ?: return null
            val flowKt = XposedHelpers.findClass(FLOW_KT_CLASS, classLoader)
            val firstMethod = (flowKt.methods.asSequence() + flowKt.declaredMethods.asSequence())
                .firstOrNull { it.name == "first" && it.parameterTypes.size == 2 }
                ?.apply { isAccessible = true }
                ?: return null
            invokeSuspendBlocking(firstMethod, null, flow)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to resolve current user: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun Any.fieldValue(name: String): Any? =
        runCatching {
            var current: Class<*>? = javaClass
            while (current != null) {
                current.declaredFields.firstOrNull { it.name == name }?.let { field ->
                    field.isAccessible = true
                    return field.get(this)
                }
                current = current.superclass
            }
            null
        }.getOrNull()

    private fun Any?.titleOrNull(): String? =
        runCatching {
            this?.javaClass?.methods?.firstOrNull { it.name == "getTitle" && it.parameterTypes.isEmpty() }
                ?.invoke(this)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun Any?.uuidOrNull(): String? =
        runCatching {
            this?.javaClass?.methods?.firstOrNull { it.name == "getUuid" && it.parameterTypes.isEmpty() }
                ?.invoke(this)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun Any?.uriOrNull(): String? =
        runCatching {
            this?.javaClass?.methods?.firstOrNull { it.name == "getUri" && it.parameterTypes.isEmpty() }
                ?.invoke(this)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun Any?.longOrNull(methodName: String): Long? =
        runCatching {
            (this?.javaClass?.methods?.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
                ?.invoke(this) as? Number)?.toLong()
        }.getOrNull()

    private fun Any?.intOrNull(methodName: String): Int? =
        runCatching {
            (this?.javaClass?.methods?.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
                ?.invoke(this) as? Number)?.toInt()
        }.getOrNull()

    private fun Any?.stringOrNull(methodName: String): String? =
        runCatching {
            this?.javaClass?.methods?.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
                ?.invoke(this)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun Any?.hasLocalBookData(): Boolean {
        val context = activityProvider()?.applicationContext ?: currentApplicationContext() ?: return false
        val uid = longOrNull("getUid") ?: return false
        val uuid = uuidOrNull() ?: return false
        return File(context.filesDir, "$uid/books/$uuid/META-INF/container.xml").isFile
    }

    private fun String.normalizedBookTitle(): String =
        trim().replace(Regex("\\s+"), " ")

    private fun currentApplicationContext(): Context? =
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Context
        }.getOrNull()?.applicationContext

    private fun decisionKey(uuid: String, title: String): String =
        "${uuid.trim()}|${title.normalizedBookTitle()}"

    private fun rememberPreImportDecision(uuid: String, title: String, decision: PreImportDecision) {
        pendingPreImportDecisions[decisionKey(uuid, title)] = decision
        clearExpiredPreImportDecisions()
    }

    private fun consumePreImportDecision(uuid: String, title: String): PreImportDecision? {
        clearExpiredPreImportDecisions()
        return pendingPreImportDecisions.remove(decisionKey(uuid, title))
    }

    private fun clearExpiredPreImportDecisions() {
        val now = System.currentTimeMillis()
        pendingPreImportDecisions.entries.removeAll { (_, decision) ->
            now - decision.createdAtMs > PRE_IMPORT_DECISION_TTL_MS
        }
    }

    private fun clearExistingBookDirContentsBeforeImport(booksDir: Any?, uuid: String?) {
        runCatching {
            val cleanUuid = uuid?.trim()?.takeIf { it.isNotBlank() } ?: return
            val basePath = booksDir ?: return
            val targetPath = basePath.javaClass.methods.firstOrNull {
                it.name == "resolve" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java
            }?.invoke(basePath, cleanUuid, true) ?: return
            val base = File(basePath.toString()).canonicalFile
            val target = File(targetPath.toString()).canonicalFile
            val basePrefix = base.path.trimEnd(File.separatorChar) + File.separator
            if (!target.path.startsWith(basePrefix) || target == base) {
                XposedBridge.log("$LOG_PREFIX refused to clear unexpected overwrite dir: $target")
                return
            }
            if (target.isDirectory) {
                val children = target.listFiles().orEmpty()
                children.forEach { child -> child.deleteRecursively() }
                XposedBridge.log("$LOG_PREFIX cleared existing book dir before overwrite import: $target children=${children.size}")
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to clear existing book dir before import: ${it.stackTraceToString()}")
        }
    }

    private fun prepareBookDirForOverwriteUuid(bookDir: Any?, oldUuid: String, newOpf: Any): Any? =
        runCatching {
            val sourcePath = bookDir ?: return null
            val parentPath = sourcePath.javaClass.methods.firstOrNull {
                it.name == "parent" && it.parameterTypes.isEmpty()
            }?.invoke(sourcePath) ?: return null
            val targetPath = parentPath.javaClass.methods.firstOrNull {
                it.name == "resolve" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java
            }?.invoke(parentPath, oldUuid, true) ?: return null

            val source = File(sourcePath.toString())
            val target = File(targetPath.toString())
            if (!source.exists() || !source.isDirectory) return null
            if (source.canonicalPath != target.canonicalPath) {
                target.parentFile?.mkdirs()
                if (target.exists()) target.deleteRecursively()
                source.copyRecursively(target, overwrite = true)
                source.deleteRecursively()
            }
            newOpf.javaClass.methods.firstOrNull {
                it.name == "overwrite" && it.parameterTypes.size == 1
            }?.invoke(newOpf, targetPath)
            targetPath
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to prepare overwrite import book dir: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun copyBookDirForIndependentUuid(bookDir: Any?, newUuid: String, newOpf: Any): Any? =
        runCatching {
            val sourcePath = bookDir ?: return null
            val parentPath = sourcePath.javaClass.methods.firstOrNull {
                it.name == "parent" && it.parameterTypes.isEmpty()
            }?.invoke(sourcePath) ?: return null
            val targetPath = parentPath.javaClass.methods.firstOrNull {
                it.name == "resolve" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java
            }?.invoke(parentPath, newUuid, true) ?: return null

            val source = File(sourcePath.toString())
            val target = File(targetPath.toString())
            if (!source.exists() || !source.isDirectory) return null
            if (target.exists()) target.deleteRecursively()
            source.copyRecursively(target, overwrite = true)
            newOpf.javaClass.methods.firstOrNull {
                it.name == "overwrite" && it.parameterTypes.size == 1
            }?.invoke(newOpf, targetPath)
            targetPath
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to copy independent import book dir: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun obtainOpf(root: Any): Any? =
        runCatching {
            val opfClass = XposedHelpers.findClass(OPF_CLASS, classLoader)
            val instance = sequenceOf("INSTANCE", "Companion")
                .mapNotNull { fieldName ->
                    runCatching {
                        opfClass.companionField(fieldName)?.let { field ->
                            field.isAccessible = true
                            field.get(null)
                        }
                    }.getOrNull()
                }
                .firstOrNull()
                ?: (opfClass.declaredFields.asSequence() + opfClass.fields.asSequence())
                    .filter { Modifier.isStatic(it.modifiers) && it.type.name.contains("Opf\$Companion") }
                    .mapNotNull { field ->
                        runCatching {
                            field.isAccessible = true
                            field.get(null)
                        }.getOrNull()
                    }
                    .firstOrNull()
                ?: return null
            (instance.javaClass.methods.asSequence() + instance.javaClass.declaredMethods.asSequence())
                .firstOrNull { it.name == "obtain" && it.parameterTypes.size == 1 }
                ?.apply { isAccessible = true }
                ?.invoke(instance, root)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to obtain import opf: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun Class<*>.companionField(name: String) =
        runCatching { getDeclaredField(name) }
            .recoverCatching { getField(name) }
            .getOrNull()

    private fun Any.overwriteToRoot(root: Any): Any =
        runCatching {
            javaClass.methods.firstOrNull { it.name == "overwrite" && it.parameterTypes.size == 1 }
                ?.apply { isAccessible = true }
                ?.invoke(this, root) ?: this
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to overwrite opf before import: ${it.stackTraceToString()}")
        }.getOrDefault(this)

    private fun Any.withUuid(uuid: String): Any =
        runCatching {
            val metadata = javaClass.methods.first { it.name == "getMetadata" && it.parameterTypes.isEmpty() }
                .invoke(this)
            val oldIdentifier = metadata.javaClass.methods.first { it.name == "getUuid" && it.parameterTypes.isEmpty() }
                .invoke(metadata)
            val newIdentifier = oldIdentifier.javaClass.methods.first {
                it.name == "copy" && it.parameterTypes.size == 4
            }.invoke(
                oldIdentifier,
                oldIdentifier.javaClass.methods.first { it.name == "getId" && it.parameterTypes.isEmpty() }.invoke(oldIdentifier),
                oldIdentifier.javaClass.methods.first { it.name == "getType" && it.parameterTypes.isEmpty() }.invoke(oldIdentifier),
                uuid,
                oldIdentifier.javaClass.methods.first { it.name == "getRefinements" && it.parameterTypes.isEmpty() }
                    .invoke(oldIdentifier),
            )
            val newMetadata = metadata.javaClass.methods.first {
                it.name == "copy" && it.parameterTypes.size == 20
            }.invoke(
                metadata,
                metadata.javaClass.methods.first { it.name == "getTitle" && it.parameterTypes.isEmpty() }.invoke(metadata),
                newIdentifier,
                metadata.javaClass.methods.first { it.name == "getSubtitle" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getCover" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getAuthors" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getContributors" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getIsbn" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getDoi" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getUri" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getLanguage" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getSubject" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getPublisher" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getDate" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getDescription" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getFormat" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getSource" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getRelation" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getCoverage" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getRights" && it.parameterTypes.isEmpty() }.invoke(metadata),
                metadata.javaClass.methods.first { it.name == "getMetas" && it.parameterTypes.isEmpty() }.invoke(metadata),
            )
            javaClass.methods.first { it.name == "copy" && it.parameterTypes.size == 8 }.invoke(
                this,
                javaClass.methods.first { it.name == "getPath" && it.parameterTypes.isEmpty() }.invoke(this),
                javaClass.methods.first { it.name == "getVersion" && it.parameterTypes.isEmpty() }.invoke(this),
                javaClass.methods.first { it.name == "getUniqueIdentifier" && it.parameterTypes.isEmpty() }.invoke(this),
                newMetadata,
                javaClass.methods.first { it.name == "getManifest" && it.parameterTypes.isEmpty() }.invoke(this),
                javaClass.methods.first { it.name == "getSpine" && it.parameterTypes.isEmpty() }.invoke(this),
                javaClass.methods.first { it.name == "getGuide" && it.parameterTypes.isEmpty() }.invoke(this),
                javaClass.methods.first { it.name == "getSpineCfiIndex" && it.parameterTypes.isEmpty() }.invoke(this),
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to rewrite import opf uuid: ${it.stackTraceToString()}")
        }.getOrDefault(this)

    private fun duplicateBookException(message: String): RuntimeException =
        runCatching {
            val clazz = XposedHelpers.findClass(BOOKSHELF_REPOSITORY_CLASS, classLoader)
            clazz.declaredClasses.firstOrNull { it.simpleName == "DuplicateBookException" }
                ?.getConstructor(String::class.java)
                ?.newInstance(message) as? RuntimeException
        }.getOrNull() ?: RuntimeException(message)

    private fun cancelImport(param: XC_MethodHook.MethodHookParam) {
        param.throwable = duplicateBookException("导入已取消")
    }

    private fun method(targetClass: Class<*>, name: String, parameterCount: Int): Method =
        (targetClass.methods.asSequence() + targetClass.declaredMethods.asSequence())
            .firstOrNull { it.name == name && it.parameterTypes.size == parameterCount }
            ?.apply { isAccessible = true }
            ?: error("Method not found: ${targetClass.name}#$name/$parameterCount")

    private fun callStringPath(target: Any?, vararg path: String): String? {
        var current = target ?: return null
        for (name in path) {
            current = when (current) {
                is String -> return current
                else -> runCatching {
                    current.javaClass.methods.firstOrNull {
                        it.parameterTypes.isEmpty() &&
                            (it.name == name || it.name == "get" + name.replaceFirstChar { ch -> ch.uppercaseChar() })
                    }?.apply { isAccessible = true }?.invoke(current)
                }.getOrNull() ?: return null
            }
        }
        return current?.toString()
    }

    private fun resolveImportTitleFromFile(file: Any?): String? =
        runCatching {
            file?.javaClass?.methods?.firstOrNull { it.name == "getNameWithoutExtension" && it.parameterTypes.isEmpty() }
                ?.invoke(file)?.toString()
        }.getOrNull()

    private fun importBookSize(args: Array<Any?>?): Long? =
        (args?.getOrNull(4) as? Number)?.toLong()?.takeIf { it > 0L }
            ?: platformFileSize(args?.getOrNull(0))

    private fun platformFileSize(platformFile: Any?): Long? =
        runCatching {
            val androidFile = noArgValue(platformFile, "getAndroidFile")
                ?: noArgValue(platformFile, "component1")
            val file = noArgValue(androidFile, "getFile") as? File
                ?: platformFile?.toString()?.let { File(it) }
            file?.takeIf { it.isFile }?.length()?.takeIf { it > 0L }
        }.getOrNull()

    private fun noArgValue(target: Any?, name: String): Any? {
        if (target == null) return null
        return runCatching {
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
                ?.invoke(target)
        }.getOrNull()
    }

    private fun invokeSuspendBlocking(method: Method, target: Any?, vararg args: Any?): Any? {
        val latch = CountDownLatch(1)
        var value: Any? = null
        var error: Throwable? = null
        val continuationClass = XposedHelpers.findClass(KOTLIN_CONTINUATION_CLASS, classLoader)
        val throwOnFailure = XposedHelpers.findClass(KOTLIN_RESULT_KT_CLASS, classLoader).declaredMethods.first {
            it.name == "throwOnFailure" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        val continuation = Proxy.newProxyInstance(classLoader, arrayOf(continuationClass)) { proxy, proxyMethod, proxyArgs ->
            when (proxyMethod.name) {
                "getContext" -> emptyCoroutineContext()
                "resumeWith" -> {
                    val result = proxyArgs?.getOrNull(0)
                    runCatching {
                        throwOnFailure.invoke(null, result)
                        value = result
                    }.onFailure {
                        error = if (it is InvocationTargetException) it.targetException ?: it else it
                    }
                    latch.countDown()
                    Unit
                }
                "toString" -> "ReaMicroReaderContinuation"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === proxyArgs?.getOrNull(0)
                else -> null
            }
        }
        val returned = try {
            method.invoke(target, *args.toMutableList().apply { add(continuation) }.toTypedArray())
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
        if (returned !== coroutineSuspended()) return returned
        latch.await()
        error?.let { throw it }
        return value
    }

    private fun emptyCoroutineContext(): Any =
        XposedHelpers.findClass(KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS, classLoader)
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null) ?: error("EmptyCoroutineContext unavailable")

    private fun coroutineSuspended(): Any =
        runCatching {
            XposedHelpers.findClass(KOTLIN_INTRINSICS_CLASS, classLoader).methods.first {
                it.name == "getCOROUTINE_SUSPENDED" && it.parameterTypes.isEmpty()
            }.apply { isAccessible = true }.invoke(null)
        }.getOrElse {
            XposedHelpers.findClass(KOTLIN_COROUTINE_SINGLETONS_CLASS, classLoader)
                .enumConstants
                ?.firstOrNull { value -> value.toString() == "COROUTINE_SUSPENDED" }
                ?: error("COROUTINE_SUSPENDED unavailable")
        }

    private enum class OverwriteDecision {
        OVERWRITE,
        INDEPENDENT,
        CANCEL,
    }

    private data class ImportConflict(
        val oldBook: Any?,
        val oldTitle: String,
        val oldUuid: String?,
        val oldUri: String?,
        val byUuid: Boolean,
        val byUrl: Boolean,
        val byTitle: Boolean,
    )

    private data class PreImportDecision(
        val decision: OverwriteDecision,
        val oldTitle: String,
        val oldUuid: String?,
        val oldUri: String?,
        val oldBook: Any?,
        val metadataPatch: BookMetadataPatch,
        val createdAtMs: Long,
    )

    private fun BookMetadataPatch.mergeWith(fallback: BookMetadataPatch): BookMetadataPatch =
        BookMetadataPatch(
            title = title ?: fallback.title,
            subtitle = subtitle ?: fallback.subtitle,
            author = author ?: fallback.author,
            cover = cover ?: fallback.cover,
            size = size ?: fallback.size,
            publisher = publisher ?: fallback.publisher,
        )

    private fun BookMetadataPatch.withSize(size: Long?): BookMetadataPatch =
        if (size != null && size > 0L) copy(size = size) else this

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val BOOKSHELF_REPOSITORY_CLASS = "app.zhendong.reamicro.repository.BookshelfRepository"
        const val BOOKSHELF_IMPORT_BOOK_METHOD = "importBook"
        const val WORKER_MANAGER_CLASS = "app.zhendong.reamicro.arch.WorkerManager"
        const val EPUB_FILE_MANAGER_CLASS = "app.zhendong.reamicro.arch.EpubFileManager"
        const val OPF_CLASS = "org.epub.structure.opf.Opf"
        const val OKIO_PATH_CLASS = "okio.Path"
        const val KOTLIN_PAIR_CLASS = "kotlin.Pair"
        const val FLOW_KT_CLASS = "kotlinx.coroutines.flow.FlowKt"
        const val KOTLIN_CONTINUATION_CLASS = "kotlin.coroutines.Continuation"
        const val KOTLIN_RESULT_KT_CLASS = "kotlin.ResultKt"
        const val KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS = "kotlin.coroutines.EmptyCoroutineContext"
        const val KOTLIN_INTRINSICS_CLASS = "kotlin.coroutines.intrinsics.IntrinsicsKt"
        const val KOTLIN_COROUTINE_SINGLETONS_CLASS = "kotlin.coroutines.intrinsics.CoroutineSingletons"
        const val BACKUP_TYPE_ONLINE_COMPLETION = 10
        const val ONLINE_COMPLETION_SOURCE_PREFIX = "reamicro-online-source://"
        const val ONLINE_COMPLETION_BOOK_PREFIX = "reamicro-online-book://"
        const val ONLINE_COMPLETION_UUID_PREFIX = "reamicro-online-"
        const val PRE_IMPORT_DECISION_TTL_MS = 120_000L
        const val POST_IMPORT_METADATA_SYNC_DELAY_MS = 2_500L
        val REAMICRO_MD5_REGEX = Regex("^[0-9a-fA-F]{32}$")
    }
}
