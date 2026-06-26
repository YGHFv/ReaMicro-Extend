package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

class BookDetailsAssociationActionHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot,
) {
    private var barcodeHotspot: View? = null
    private val localBooksById = ConcurrentHashMap<Long, Any>()
    private val localBooksByCloudId = ConcurrentHashMap<Long, Any>()
    private val completedCoverFixes = ConcurrentHashMap<String, Boolean>()
    private val renderingBookDetails = ThreadLocal.withInitial { false }
    @Volatile private var currentDetailsContext: DetailContext? = null
    @Volatile private var currentNavGraphScopeRef: WeakReference<Any>? = null
    @Volatile private var lastBarcodeHookLogKey: String = ""

    fun install() {
        hookBookshelfDetailNavigation()
        hookBookDetailsViewModel()
        hookBookDetailsScreen()
        hookBookDetailsBarcodeText()
        hookNavGraphScope()
        hookNavigationBackCleanup()
    }

    private fun hookBookshelfDetailNavigation() {
        runCatching {
            val screenClass = XposedHelpers.findClass(BOOKSHELF_SCREEN_CLASS, classLoader)
            val methods = screenClass.declaredMethods.filter { method ->
                method.parameterTypes.size == 4 &&
                    method.parameterTypes.lastOrNull()?.name == BOOK_CLASS
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val book = param.args?.getOrNull(3) ?: return
                        cacheLocalBook(book)
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX book details association bookshelf cache hooks installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook bookshelf detail navigation: ${it.stackTraceToString()}")
        }
    }

    private fun hookBookDetailsViewModel() {
        runCatching {
            val viewModelClass = XposedHelpers.findClass(BOOK_DETAILS_VIEW_MODEL_CLASS, classLoader)
            XposedBridge.hookAllConstructors(viewModelClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val viewModel = param.thisObject ?: return
                    val args = fieldValue(viewModel, "args") ?: param.args?.getOrNull(0) ?: return
                    val repository = fieldValue(viewModel, "repository") ?: param.args?.getOrNull(1) ?: return
                    val bookId = callLong(args, "getBookId")
                    val compat = callBoolean(args, "getCompat")
                    currentDetailsContext = DetailContext(
                        bookId = bookId,
                        compat = compat,
                        viewModelRef = WeakReference(viewModel),
                        repositoryRef = WeakReference(repository),
                    )
                    showBarcodeHotspot(bookId)
                    XposedBridge.log("$LOG_PREFIX book details association context captured: bookId=$bookId, compat=$compat")
                }
            })
            XposedBridge.hookAllMethods(viewModelClass, "onCleared", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (currentDetailsContext?.viewModelRef?.get() === param.thisObject) {
                        clearBookDetailsContext()
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook BookDetailsViewModel: ${it.stackTraceToString()}")
        }
    }

    private fun hookBookDetailsScreen() {
        runCatching {
            val screenClass = XposedHelpers.findClass(BOOK_DETAILS_SCREEN_CLASS, classLoader)
            val methods = screenClass.declaredMethods.filter {
                it.name == BOOK_DETAILS_CONTENT_METHOD && it.parameterTypes.size == 4
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        renderingBookDetails.set(true)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        renderingBookDetails.set(false)
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX book details content hooks installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook BookDetailsScreen content: ${it.stackTraceToString()}")
        }
    }

    private fun hookBookDetailsBarcodeText() {
        runCatching {
            val textClass = XposedHelpers.findClass(MATERIAL_TEXT_KT_CLASS, classLoader)
            val textMethods = textClass.declaredMethods.filter {
                it.parameterTypes.size >= 20 && it.parameterTypes.firstOrNull() == String::class.java
            }
            textMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!renderingBookDetails.get()) return
                        val details = currentDetailsContext ?: return
                        val barcode = param.args?.getOrNull(0) as? String ?: return
                        if (!isBookDetailsBarcode(barcode)) return
                        if (!canUseBookDetailsAssociationActions()) return
                        val baseModifier = param.args?.getOrNull(1) ?: return
                        param.args[1] = clickableModifier(baseModifier) {
                            showAssociationUnlinkDialog(details.bookId)
                        }
                        logBarcodeHook(details.bookId, barcode)
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX book details barcode Text hooks installed: ${textMethods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook barcode Text: ${it.stackTraceToString()}")
        }
    }

    private fun hookNavGraphScope() {
        runCatching {
            val navGraphScopeClass = XposedHelpers.findClass(NAV_GRAPH_SCOPE_CLASS, classLoader)
            XposedBridge.hookAllConstructors(navGraphScopeClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let { currentNavGraphScopeRef = WeakReference(it) }
                }
            })
            XposedBridge.hookAllMethods(navGraphScopeClass, "navigate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let { currentNavGraphScopeRef = WeakReference(it) }
                    val route = param.args?.getOrNull(0)
                    if (route?.javaClass?.name != ROUTE_BOOK_DETAIL_CLASS) {
                        clearBookDetailsContext()
                    }
                }
            })
            XposedBridge.hookAllMethods(navGraphScopeClass, "popBackStack", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result == true) clearBookDetailsContext()
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook NavGraphScope: ${it.stackTraceToString()}")
        }
    }

    private fun hookNavigationBackCleanup() {
        runCatching {
            val holderClass = XposedHelpers.findClass(NAV_CONTROLLER_HOLDER_CLASS, classLoader)
            XposedBridge.hookAllMethods(holderClass, "popBack", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    clearBookDetailsContext()
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook NavControllerHolder popBack: ${it.stackTraceToString()}")
        }
        runCatching {
            val navControllerClass = XposedHelpers.findClass(ANDROIDX_NAV_CONTROLLER_CLASS, classLoader)
            XposedBridge.hookAllMethods(navControllerClass, "popBackStack", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result != false) clearBookDetailsContext()
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook NavController popBackStack: ${it.stackTraceToString()}")
        }
    }

    private fun showAssociationUnlinkDialog(bookId: Long) {
        val activity = activityProvider() ?: return
        if (!canUseBookDetailsAssociationActions()) return
        if (!canShowAssociationUnlinkDialog(bookId)) {
            clearBookDetailsContext()
            return
        }
        activity.runOnUiThread {
            val snapshot = settingsProvider()
            if (!snapshot.canUseAssociationUnlink && !snapshot.canUseAssociationCoverFix) return@runOnUiThread
            if (!canShowAssociationUnlinkDialog(bookId)) {
                clearBookDetailsContext()
                return@runOnUiThread
            }
            val dialog = Dialog(activity)
            val dp = activity.resources.displayMetrics.density
            val primary = activity.resolveThemeColor(android.R.attr.colorAccent, Color.rgb(57, 126, 184))
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((22 * dp).toInt(), (22 * dp).toInt(), (22 * dp).toInt(), (18 * dp).toInt())
                background = roundedDrawable(Color.WHITE, 24 * dp)
            }
            card.addView(TextView(activity).apply {
                text = if (snapshot.canUseAssociationCoverFix) "\u5173\u8054\u64cd\u4f5c" else "\u662f\u5426\u53d6\u6d88\u5173\u8054"
                textSize = 19f
                setTextColor(Color.rgb(32, 36, 40))
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (16 * dp).toInt() }
            })
            if (snapshot.canUseAssociationCoverFix) {
                card.addView(actionButton(activity, "\u5c01\u9762\u4fee\u590d", Color.rgb(232, 242, 250), primary) {
                    dialog.dismiss()
                    fixAssociationCover(bookId)
                })
            }
            if (snapshot.canUseAssociationUnlink) {
                card.addView(actionButton(activity, "\u53d6\u6d88\u5173\u8054", Color.rgb(244, 236, 239), Color.rgb(145, 54, 78)) {
                    dialog.dismiss()
                    unlinkAssociation(bookId, reAssociate = false)
                })
                card.addView(actionButton(activity, "\u91cd\u65b0\u5173\u8054", primary, Color.WHITE) {
                    dialog.dismiss()
                    unlinkAssociation(bookId, reAssociate = true)
                })
            }
            card.addView(actionButton(activity, "\u53d6\u6d88\u64cd\u4f5c", Color.rgb(244, 247, 250), Color.rgb(86, 96, 106)) {
                dialog.dismiss()
            })
            dialog.setContentView(card)
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
    }

    private fun showBarcodeHotspot(bookId: Long) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            if (!canUseBookDetailsAssociationActions()) {
                hideBarcodeHotspot()
                return@runOnUiThread
            }
            val decor = activity.window?.decorView as? ViewGroup ?: return@runOnUiThread
            val density = activity.resources.displayMetrics.density
            val hotspot = barcodeHotspot ?: View(activity).also { view ->
                view.setBackgroundColor(Color.TRANSPARENT)
                view.isClickable = true
                view.isFocusable = false
                barcodeHotspot = view
            }
            hotspot.setOnClickListener {
                if (!canShowAssociationUnlinkDialog(bookId)) {
                    clearBookDetailsContext()
                    return@setOnClickListener
                }
                XposedBridge.log("$LOG_PREFIX barcode hotspot clicked: bookId=$bookId")
                showAssociationUnlinkDialog(bookId)
            }
            if (hotspot.parent == null) {
                val params = FrameLayout.LayoutParams(
                    (260 * density).toInt(),
                    (96 * density).toInt(),
                    Gravity.TOP or Gravity.END,
                ).apply {
                    topMargin = (48 * density).toInt()
                    rightMargin = (18 * density).toInt()
                }
                decor.addView(hotspot, params)
                XposedBridge.log("$LOG_PREFIX barcode hotspot added: bookId=$bookId")
            }
        }
    }

    private fun hideBarcodeHotspot() {
        val view = barcodeHotspot ?: return
        barcodeHotspot = null
        val remove = Runnable {
            runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                .onFailure { XposedBridge.log("$LOG_PREFIX failed to remove barcode hotspot: ${it.message}") }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            remove.run()
        } else {
            view.post(remove)
        }
    }

    private fun fixAssociationCover(bookId: Long) {
        val activity = activityProvider()
        val details = currentDetailsContext
        Thread {
            val result = runCatching {
                if (!settingsProvider().canUseAssociationCoverFix) error("Association cover fix disabled")
                if (details == null || details.bookId != bookId) error("Book details unavailable")
                val repository = details.repositoryRef.get() ?: error("BookRepository unavailable")
                val bookshelf = fieldValue(repository, "bookshelfRepository") ?: error("BookshelfRepository unavailable")
                val book = findLocalBookForDetails(bookshelf, details) ?: error("Book not found: $bookId")
                val coverUrl = resolveDetailsCoverUrl(repository, details, book)
                if (!isRemoteCoverUrl(coverUrl)) error("Cover unavailable")
                val dedupeKey = "$bookId|$coverUrl"
                if (completedCoverFixes.putIfAbsent(dedupeKey, true) != null) {
                    CoverFixUploadResult(0L, coverUrl)
                } else {
                    runCatching {
                        CoverFixUploadResult(postAssociationCover(repository, book, coverUrl), coverUrl)
                    }.onFailure {
                        completedCoverFixes.remove(dedupeKey)
                    }.getOrThrow()
                }
            }
            activity?.runOnUiThread {
                result
                    .onSuccess { upload ->
                        Toast.makeText(activity, "\u5df2\u4e0a\u4f20\u5c01\u9762", Toast.LENGTH_SHORT).show()
                        XposedBridge.log(
                            "$LOG_PREFIX association cover fix uploaded manually: " +
                                "bookId=$bookId, userBookId=${upload.userBookId}, cover=${upload.coverUrl}",
                        )
                    }
                    .onFailure {
                        val message = if (it.message == "Cover unavailable") {
                            "\u672a\u627e\u5230\u53ef\u4e0a\u4f20\u7684\u5c01\u9762"
                        } else {
                            "\u5c01\u9762\u4fee\u590d\u5931\u8d25"
                        }
                        XposedBridge.log("$LOG_PREFIX failed to fix association cover manually: ${it.stackTraceToString()}")
                        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                    }
            }
        }.apply { name = "ReaMicro-ManualCoverFix" }.start()
    }

    private fun resolveDetailsCoverUrl(repository: Any, details: DetailContext, book: Any): String {
        val publisherName = callString(book, "getPublisher").trim()
        publisherCoverUrl(detailStatePublishers(details), publisherName).takeIf(::isRemoteCoverUrl)?.let { return it }
        publisherCoverUrl(fetchCloudInfoPublishers(repository, details), publisherName).takeIf(::isRemoteCoverUrl)?.let { return it }
        return callString(book, "getCover").trim().takeIf(::isRemoteCoverUrl).orEmpty()
    }

    private fun detailStatePublishers(details: DetailContext): List<Any> =
        runCatching {
            details.viewModelRef.get()
                ?.let { XposedHelpers.callMethod(it, "getCurrentState") }
                ?.let(::publishersFrom)
                ?: emptyList()
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX failed to read details publishers: ${it.stackTraceToString()}")
            emptyList()
        }

    private fun fetchCloudInfoPublishers(repository: Any, details: DetailContext): List<Any> {
        val method = (repository.javaClass.methods.asSequence() + repository.javaClass.declaredMethods.asSequence())
            .firstOrNull {
                it.name.contains("getBookCloudInfo", ignoreCase = true) &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == java.lang.Long.TYPE &&
                    it.parameterTypes[1] == java.lang.Boolean.TYPE
            }
            ?.apply { isAccessible = true }
            ?: return emptyList()
        return runCatching {
            val info = unwrapKotlinResult(invokeSuspendBlocking(method, repository, details.bookId, details.compat))
            publishersFrom(info)
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX failed to fetch cloud publishers for cover fix: ${it.stackTraceToString()}")
            emptyList()
        }
    }

    private fun publishersFrom(target: Any?): List<Any> {
        if (target == null) return emptyList()
        return runCatching {
            (XposedHelpers.callMethod(target, "getPublishers") as? Iterable<*>)
                ?.mapNotNull { it }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun publisherCoverUrl(publishers: List<Any>, publisherName: String): String {
        val covers = publishers.mapNotNull { publisher ->
            val cover = callString(publisher, "getCover").trim()
            if (!isRemoteCoverUrl(cover)) return@mapNotNull null
            callString(publisher, "getPublisher").trim() to cover
        }
        return covers.firstOrNull { (name, _) ->
            publisherName.isNotEmpty() && name.equals(publisherName, ignoreCase = true)
        }?.second ?: covers.firstOrNull()?.second.orEmpty()
    }

    private fun unlinkAssociation(bookId: Long, reAssociate: Boolean) {
        val activity = activityProvider()
        val details = currentDetailsContext
        Thread {
            val result = runCatching {
                if (!settingsProvider().canUseAssociationUnlink) error("Association unlink disabled")
                if (details == null || details.bookId != bookId) error("Book details unavailable")
                val repository = details.repositoryRef.get() ?: error("BookRepository unavailable")
                val bookshelf = fieldValue(repository, "bookshelfRepository") ?: error("BookshelfRepository unavailable")
                val book = findLocalBookForDetails(bookshelf, details) ?: error("Book not found: $bookId")
                val localBookId = callLong(book, "getId")
                updateLocalBook(bookshelf, copyBookWithoutAssociation(book))
                localBookId
            }
            activity?.runOnUiThread {
                result
                    .onSuccess { localBookId ->
                        Toast.makeText(activity, "\u5df2\u53d6\u6d88\u5173\u8054", Toast.LENGTH_SHORT).show()
                        if (reAssociate) {
                            popBookDetails()
                            navigateToBookPublish(localBookId)
                        } else {
                            popBookDetails()
                        }
                    }
                    .onFailure {
                        XposedBridge.log("$LOG_PREFIX failed to unlink association: ${it.stackTraceToString()}")
                        Toast.makeText(activity, "\u53d6\u6d88\u5173\u8054\u5931\u8d25", Toast.LENGTH_SHORT).show()
                    }
            }
        }.apply { name = "ReaMicro-UnlinkAssociation" }.start()
    }

    private fun findLocalBookForDetails(bookshelf: Any, details: DetailContext): Any? {
        findLocalBookById(bookshelf, details.bookId)?.let { book ->
            if (details.compat || callLong(book, "getCloudId") == details.bookId) return book
        }
        localBooksByCloudId[details.bookId]?.let { return it }
        if (!details.compat) {
            findLocalBookByCloudIdFromSearch(bookshelf, details)?.let { return it }
        }
        return null
    }

    private fun findLocalBookById(bookshelf: Any, bookId: Long): Any? {
        val method = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
            .firstOrNull {
                it.name == BOOKSHELF_FIND_BOOK_BY_ID_METHOD &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == java.lang.Long.TYPE
            }
            ?.apply { isAccessible = true }
            ?: return null
        return invokeSuspendBlocking(method, bookshelf, bookId)?.also { cacheLocalBook(it) }
    }

    private fun findLocalBookByCloudIdFromSearch(bookshelf: Any, details: DetailContext): Any? {
        val state = runCatching {
            details.viewModelRef.get()?.let { XposedHelpers.callMethod(it, "getCurrentState") }
        }.getOrNull()
        val title = state?.let { callString(it, "getTitle") }.orEmpty()
        val author = state?.let { callString(it, "getAuthor") }.orEmpty()
        val queries = listOf(title, author, "").map { it.trim() }.distinct()
        for (query in queries) {
            val book = searchLocalBooks(bookshelf, query)
                .firstOrNull { callLong(it, "getCloudId") == details.bookId }
            if (book != null) {
                cacheLocalBook(book)
                return book
            }
        }
        return null
    }

    private fun searchLocalBooks(bookshelf: Any, query: String): List<Any> {
        val method = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
            .firstOrNull {
                it.name.contains(BOOKSHELF_SEARCH_METHOD_KEYWORD, ignoreCase = true) &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java
            }
            ?.apply { isAccessible = true }
            ?: return emptyList()
        return runCatching {
            val result = unwrapKotlinResult(invokeSuspendBlocking(method, bookshelf, query))
            (result as? Iterable<*>)?.mapNotNull { it }?.onEach { cacheLocalBook(it) } ?: emptyList()
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX failed to search local books for unlink: ${it.stackTraceToString()}")
            emptyList()
        }
    }

    private fun updateLocalBook(bookshelf: Any, book: Any) {
        val method = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
            .first {
                it.name == BOOKSHELF_UPDATE_BOOK_METHOD &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].name == BOOK_CLASS
            }
            .apply { isAccessible = true }
        invokeSuspendBlocking(method, bookshelf, book)
    }

    private fun postAssociationCover(repository: Any, book: Any, coverUrl: String): Long {
        val api = fieldValue(repository, "api") ?: error("Api unavailable")
        val requestClass = XposedHelpers.findClass(POST_USER_BOOK_REQ_CLASS, classLoader)
        val request = requestClass.getConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        ).newInstance(
            callString(book, "getTitle"),
            callString(book, "getAuthor"),
            callString(book, "getUuid").replace("-", ""),
            coverUrl,
            "",
        )
        val method = (api.javaClass.methods.asSequence() + api.javaClass.declaredMethods.asSequence())
            .first {
                it.name == "postUserBook" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].name == POST_USER_BOOK_REQ_CLASS
            }
            .apply { isAccessible = true }
        val envelope = invokeSuspendBlocking(method, api, request)
        val data = envelopeDataOrThrow(envelope) ?: return 0L
        return callLong(data, "getId")
    }

    private fun envelopeDataOrThrow(envelope: Any?): Any? {
        if (envelope == null) return null
        val envelopeKt = XposedHelpers.findClass(ENVELOPE_KT_CLASS, classLoader)
        val method = envelopeKt.declaredMethods.first {
            it.name == "dataOrThrow" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        return method.invoke(null, envelope)
    }

    private fun isRemoteCoverUrl(value: String): Boolean {
        val lower = value.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun copyBookWithoutAssociation(book: Any): Any {
        val copyMethod = book.javaClass.methods.firstOrNull {
            it.name == "copy" && it.parameterTypes.size == 24
        } ?: book.javaClass.methods.first {
            it.name == "copy" && it.parameterTypes.size == 23
        }
        copyMethod.isAccessible = true
        val args = mutableListOf<Any?>(
            callLong(book, "getId"),
            callString(book, "getUuid"),
            callLong(book, "getUid"),
            callString(book, "getTitle"),
            callString(book, "getSubtitle"),
            callString(book, "getAuthor"),
            callString(book, "getCover"),
            callLong(book, "getSize"),
            callString(book, "getUri"),
            callString(book, "getGroup"),
            callLong(book, "getCreated"),
            callInt(book, "getCfiVersion"),
        )
        if (copyMethod.parameterTypes.size == 24) {
            args.add(callInt(book, "getEmbeddedFonts"))
        }
        args.addAll(
            listOf(
                callString(book, "getEpubcfi"),
                callString(book, "getChapter"),
                callFloat(book, "getProgress"),
                callLong(book, "getTotal"),
                callLong(book, "getFinished"),
                callLong(book, "getUpdated"),
                0L,
                callInt(book, "getBackupType"),
                callString(book, "getBackupId"),
                callString(book, "getBackupCode"),
                "",
            ),
        )
        return copyMethod.invoke(book, *args.toTypedArray())
    }

    private fun popBookDetails(): Boolean {
        val navGraphScope = currentNavGraphScopeRef?.get()
        clearBookDetailsContext()
        val popped = runCatching {
            navGraphScope?.javaClass?.methods
                ?.firstOrNull { it.name == "popBackStack" && it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
                ?.invoke(navGraphScope) as? Boolean ?: false
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX failed to pop BookDetails: ${it.stackTraceToString()}")
            false
        }
        if (!popped) {
            runCatching { activityProvider()?.onBackPressed() }
                .onFailure { XposedBridge.log("$LOG_PREFIX failed to fallback back press: ${it.stackTraceToString()}") }
        }
        return popped
    }

    private fun navigateToBookPublish(bookId: Long) {
        val activity = activityProvider()
        val navGraphScope = currentNavGraphScopeRef?.get()
        if (navGraphScope == null) {
            XposedBridge.log("$LOG_PREFIX cannot re-associate: NavGraphScope unavailable")
            activity?.let { Toast.makeText(it, "\u8bf7\u624b\u52a8\u8fdb\u5165\u56fe\u4e66\u5173\u8054\u9875", Toast.LENGTH_SHORT).show() }
            return
        }
        runCatching {
            val route = XposedHelpers.findClass(ROUTE_BOOK_PUBLISH_CLASS, classLoader)
                .getDeclaredConstructor(java.lang.Long.TYPE)
                .apply { isAccessible = true }
                .newInstance(bookId)
            navGraphScope.javaClass.methods.first {
                it.name == "navigate" && it.parameterTypes.size == 3
            }.apply { isAccessible = true }.invoke(navGraphScope, route, null, null)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to navigate BookPublish: ${it.stackTraceToString()}")
            activity?.let { act -> Toast.makeText(act, "\u8bf7\u624b\u52a8\u8fdb\u5165\u56fe\u4e66\u5173\u8054\u9875", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun canShowAssociationUnlinkDialog(bookId: Long): Boolean =
        currentDetailsContext?.bookId == bookId && isCurrentBookDetailsRoute()

    private fun canUseBookDetailsAssociationActions(): Boolean {
        val snapshot = settingsProvider()
        return snapshot.canUseAssociationUnlink || snapshot.canUseAssociationCoverFix
    }

    private fun isCurrentBookDetailsRoute(): Boolean {
        val route = currentNavDestinationRoute()
        return route?.contains(BOOK_DETAIL_ROUTE_NAME) == true
    }

    private fun currentNavDestinationRoute(): String? {
        fun routeFromController(controller: Any?): String? {
            if (controller == null) return null
            val destination = runCatching { XposedHelpers.callMethod(controller, "getCurrentDestination") }.getOrNull()
                ?: runCatching {
                    val entry = XposedHelpers.callMethod(controller, "getCurrentBackStackEntry")
                    XposedHelpers.callMethod(entry, "getDestination")
                }.getOrNull()
            return runCatching { XposedHelpers.callMethod(destination, "getRoute")?.toString() }.getOrNull()
                ?: destination?.toString()
        }
        val scopeController = runCatching {
            currentNavGraphScopeRef?.get()?.let { XposedHelpers.callMethod(it, "getNavController") }
        }.getOrNull()
        routeFromController(scopeController)?.let { return it }
        val holderController = runCatching {
            val holder = XposedHelpers.findClass(NAV_CONTROLLER_HOLDER_CLASS, classLoader)
                .getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .get(null)
            XposedHelpers.callMethod(holder, "getNavController")
        }.getOrNull()
        return routeFromController(holderController)
    }

    private fun clearBookDetailsContext() {
        if (currentDetailsContext != null || barcodeHotspot != null) {
            currentDetailsContext = null
            hideBarcodeHotspot()
        }
    }

    private fun clickableModifier(baseModifier: Any, onClick: () -> Unit): Any {
        val method = XposedHelpers.findClass(CLICKABLE_KT_CLASS, classLoader).declaredMethods.first {
            it.name == CLICKABLE_DEFAULT_METHOD && it.parameterTypes.size == 8
        }.apply { isAccessible = true }
        return method.invoke(
            null,
            baseModifier,
            false,
            null,
            null,
            null,
            function0Proxy("AssociationUnlinkClick") {
                onClick()
                targetUnit()
            },
            15,
            null,
        )
    }

    private fun isBookDetailsBarcode(value: String): Boolean =
        value.count { it.isDigit() } >= 6

    private fun logBarcodeHook(bookId: Long, barcode: String) {
        val key = "$bookId|$barcode"
        if (lastBarcodeHookLogKey == key) return
        lastBarcodeHookLogKey = key
        XposedBridge.log("$LOG_PREFIX barcode association menu enabled: bookId=$bookId, text=$barcode")
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
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            background = roundedDrawable(backgroundColor, 10 * dp)
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

    private fun Context.resolveThemeColor(attr: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        return if (theme.resolveAttribute(attr, typedValue, true)) typedValue.data else fallback
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
                    targetUnit()
                }
                "toString" -> "ReaMicroAssociationDetailsContinuation"
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
            .get(null)

    private fun coroutineSuspended(): Any =
        runCatching {
            XposedHelpers.findClass(KOTLIN_INTRINSICS_CLASS, classLoader).methods.first {
                it.name == "getCOROUTINE_SUSPENDED" && it.parameterTypes.isEmpty()
            }.apply { isAccessible = true }.invoke(null)
        }.getOrElse {
            (XposedHelpers.findClass(KOTLIN_COROUTINE_SINGLETONS_CLASS, classLoader).enumConstants ?: emptyArray())
                .first { value -> value.toString() == "COROUTINE_SUSPENDED" }
        }

    private fun unwrapKotlinResult(value: Any?): Any? {
        val throwOnFailure = XposedHelpers.findClass(KOTLIN_RESULT_KT_CLASS, classLoader).declaredMethods.first {
            it.name == "throwOnFailure" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        try {
            throwOnFailure.invoke(null, value)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
        return value
    }

    private fun fieldValue(target: Any?, vararg names: String): Any? {
        if (target == null) return null
        for (name in names) {
            var type: Class<*>? = target.javaClass
            while (type != null) {
                val field = runCatching { type.getDeclaredField(name) }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    return runCatching { field.get(target) }.getOrNull()
                }
                type = type.superclass
            }
        }
        return null
    }

    private fun cacheLocalBook(book: Any) {
        runCatching {
            val id = callLong(book, "getId")
            val cloudId = callLong(book, "getCloudId")
            if (id > 0) localBooksById[id] = book
            if (cloudId > 0) localBooksByCloudId[cloudId] = book
        }
    }

    private fun callLong(target: Any, methodName: String): Long =
        (XposedHelpers.callMethod(target, methodName) as? Number)?.toLong()
            ?: XposedHelpers.callMethod(target, methodName).toString().toLong()

    private fun callInt(target: Any, methodName: String): Int =
        (XposedHelpers.callMethod(target, methodName) as? Number)?.toInt()
            ?: XposedHelpers.callMethod(target, methodName).toString().toInt()

    private fun callFloat(target: Any, methodName: String): Float =
        (XposedHelpers.callMethod(target, methodName) as? Number)?.toFloat()
            ?: XposedHelpers.callMethod(target, methodName).toString().toFloat()

    private fun callString(target: Any, methodName: String): String =
        XposedHelpers.callMethod(target, methodName)?.toString().orEmpty()

    private fun callBoolean(target: Any, methodName: String): Boolean =
        (XposedHelpers.callMethod(target, methodName) as? Boolean)
            ?: XposedHelpers.callMethod(target, methodName).toString().toBoolean()

    private fun function0Proxy(name: String, block: () -> Any?): Any {
        val f0Class = XposedHelpers.findClass(KOTLIN_FUNCTION0_CLASS, classLoader)
        return Proxy.newProxyInstance(classLoader, arrayOf(f0Class)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> block()
                "toString" -> "ReaMicro$name"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
    }

    private fun targetUnit(): Any? = runCatching {
        XposedHelpers.findClass(KOTLIN_UNIT_CLASS, classLoader)
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)
    }.getOrNull()

    private data class DetailContext(
        val bookId: Long,
        val compat: Boolean,
        val viewModelRef: WeakReference<Any>,
        val repositoryRef: WeakReference<Any>,
    )

    private data class CoverFixUploadResult(
        val userBookId: Long,
        val coverUrl: String,
    )

    private companion object {
        const val BOOK_DETAILS_VIEW_MODEL_CLASS = "app.zhendong.reamicro.ui.book.BookDetailsViewModel"
        const val BOOK_DETAILS_SCREEN_CLASS = "app.zhendong.reamicro.ui.book.BookDetailsScreenKt"
        const val BOOK_DETAILS_CONTENT_METHOD = "BookDetailsScreen\$lambda\$1\$1"
        const val BOOKSHELF_SCREEN_CLASS = "app.zhendong.reamicro.ui.home.BookshelfScreenKt"
        const val BOOK_CLASS = "app.zhendong.reamicro.data.db.entity.Book"
        const val ROUTE_BOOK_DETAIL_CLASS = "app.zhendong.reamicro.Route\$BookDetail"
        const val ROUTE_BOOK_PUBLISH_CLASS = "app.zhendong.reamicro.Route\$BookPublish"
        const val POST_USER_BOOK_REQ_CLASS = "app.zhendong.reamicro.data.res.book.PostUserBookReq"
        const val ENVELOPE_KT_CLASS = "app.zhendong.reamicro.data.res.EnvelopeKt"
        const val BOOK_DETAIL_ROUTE_NAME = "BookDetail"
        const val BOOKSHELF_FIND_BOOK_BY_ID_METHOD = "findBookById"
        const val BOOKSHELF_SEARCH_METHOD_KEYWORD = "search"
        const val BOOKSHELF_UPDATE_BOOK_METHOD = "updateBook"
        const val NAV_GRAPH_SCOPE_CLASS = "app.zhendong.reamicro.NavGraphScope"
        const val NAV_CONTROLLER_HOLDER_CLASS = "app.zhendong.reamicro.arch.components.NavControllerHolder"
        const val ANDROIDX_NAV_CONTROLLER_CLASS = "androidx.navigation.NavController"
        const val MATERIAL_TEXT_KT_CLASS = "androidx.compose.material3.TextKt"
        const val CLICKABLE_KT_CLASS = "androidx.compose.foundation.ClickableKt"
        const val CLICKABLE_DEFAULT_METHOD = "m371clickableoSLSa3U\$default"
        const val KOTLIN_FUNCTION0_CLASS = "kotlin.jvm.functions.Function0"
        const val KOTLIN_UNIT_CLASS = "kotlin.Unit"
        const val KOTLIN_CONTINUATION_CLASS = "kotlin.coroutines.Continuation"
        const val KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS = "kotlin.coroutines.EmptyCoroutineContext"
        const val KOTLIN_INTRINSICS_CLASS = "kotlin.coroutines.intrinsics.IntrinsicsKt"
        const val KOTLIN_COROUTINE_SINGLETONS_CLASS = "kotlin.coroutines.intrinsics.CoroutineSingletons"
        const val KOTLIN_RESULT_KT_CLASS = "kotlin.ResultKt"
        const val LOG_PREFIX = "ReaMicro LSP"
    }
}
