package com.reamicro.fix.hook

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import com.reamicro.fix.logging.ModuleLogState
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.tts.ReadAloudIntents
import com.reamicro.fix.tts.TtsSourceEntry
import com.reamicro.fix.tts.TtsSourceStore
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.ref.WeakReference
import com.reamicro.fix.hook.reader.*

// 阅读页朗读簇。
//
// 朗读入口与控制条、分句推送、朗读高亮跟随、翻页重启、Lyricon 歌词卡片。
//
// 从 ReaderHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaderHook.canRunReadAloud(): Boolean =
    settingsProvider().canRunReaderReadAloud

internal fun ReaderHook.canUseReadAloudSelection(): Boolean =
    settingsProvider().canUseReaderReadAloudSelection && currentEpubRoot() != null

internal fun ReaderHook.canShowReaderReadAloudEntry(): Boolean =
    canRunReadAloud() && currentEpubRoot() != null && currentReaderPage() != null

internal fun ReaderHook.showReadAloudMenuButton(activity: Activity, receiver: Any?, book: Any?) {
    if (!canShowReaderReadAloudEntry()) {
        removeReadAloudMenuButton()
        return
    }
    val decor = activity.window?.decorView as? ViewGroup ?: return
    val existing = readAloudMenuButtonRef?.get()
    if (existing != null && readAloudMenuButtonActivityRef?.get() === activity && existing.parent === decor) {
        (existing as? ReadAloudMenuButtonView)?.refreshColors()
        existing.visibility = View.VISIBLE
        existing.bringToFront()
        bottomReadAloudReceiverRef = receiver?.let { WeakReference(it) } ?: bottomReadAloudReceiverRef
        bottomReadAloudBookRef = book?.let { WeakReference(it) } ?: bottomReadAloudBookRef
        return
    }
    readAloudMenuButtonRef = null
    readAloudMenuButtonActivityRef = null
    bottomReadAloudReceiverRef = receiver?.let { WeakReference(it) } ?: bottomReadAloudReceiverRef
    bottomReadAloudBookRef = book?.let { WeakReference(it) } ?: bottomReadAloudBookRef
    decor.post {
        if (!canShowReaderReadAloudEntry()) return@post
        removeTaggedViews(decor, READ_ALOUD_MENU_BUTTON_TAG)
        removeTaggedViews(activity.findViewById(android.R.id.content), READ_ALOUD_MENU_BUTTON_TAG)
        val button = ReadAloudMenuButtonView(activity).apply {
            tag = READ_ALOUD_MENU_BUTTON_TAG
            contentDescription = "\u542c\u4e66"
            alpha = 0.94f
            elevation = dp(activity, 6).toFloat()
            setOnClickListener { openReadAloud() }
        }
        decor.addView(button, FrameLayout.LayoutParams(
            dp(activity, SEARCH_MENU_BUTTON_SIZE_DP),
            dp(activity, SEARCH_MENU_BUTTON_SIZE_DP),
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(activity, READ_ALOUD_MENU_BUTTON_RIGHT_MARGIN_DP)
            bottomMargin = dp(activity, SEARCH_MENU_BUTTON_BOTTOM_MARGIN_DP)
        })
        button.bringToFront()
        readAloudMenuButtonRef = WeakReference(button)
        readAloudMenuButtonActivityRef = WeakReference(activity)
    }
}

internal fun ReaderHook.removeReadAloudMenuButton() {
    val activity = readAloudMenuButtonActivityRef?.get() ?: activityProvider()
    readAloudMenuButtonRef = null
    readAloudMenuButtonActivityRef = null
    postRemoveTaggedViews(activity, READ_ALOUD_MENU_BUTTON_TAG)
}

internal fun ReaderHook.bottomReadAloudContext(receiver: Any?, book: Any?): CatalogContext? {
    val root = currentEpubRoot()
    val existing = lastCatalogContext
    val targetBook = book
        ?: existing?.book
        ?: currentUiStateBook()
        ?: bottomReadAloudBookRef?.get()
        ?: bottomSearchBookRef?.get()
    if (root == null && targetBook == null && existing == null) {
        XposedBridge.log(
            "$LOG_PREFIX read aloud context unavailable root=false uiBook=false " +
                "page=${currentPageRef?.get()?.javaClass?.name.orEmpty()}",
        )
        return null
    }
    val catalog = existing?.takeIf { bookKey(it).isNotBlank() }?.catalog.orEmpty()
    return CatalogContext(receiver ?: existing?.intentReceiver ?: bottomSearchReceiverRef?.get(), targetBook, catalog)
}

internal fun ReaderHook.ensureReadAloudHighlightReceiver() {
    val context = activityProvider()?.applicationContext ?: return
    synchronized(readAloudHighlightReceiverLock) {
        val existingContext = readAloudHighlightReceiverContextRef?.get()
        if (readAloudHighlightReceiver != null && existingContext === context) return
        readAloudHighlightReceiver?.let { receiver ->
            runCatching { existingContext?.unregisterReceiver(receiver) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                handleReadAloudHighlightBroadcast(intent ?: return)
            }
        }
        val filter = IntentFilter().apply {
            addAction(ReadAloudIntents.ACTION_CURRENT)
            addAction(ReadAloudIntents.ACTION_CLEAR)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            readAloudHighlightReceiver = receiver
            readAloudHighlightReceiverContextRef = WeakReference(context)
            XposedBridge.log("$LOG_PREFIX read aloud highlight receiver registered")
            requestReadAloudProgressSync(context, "receiver registered")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud highlight receiver failed: ${it.stackTraceToString()}")
        }
    }
}

internal fun ReaderHook.requestReadAloudProgressSync(context: Context? = hostApplicationContext(), reason: String) {
    val appContext = context ?: return
    val now = SystemClock.elapsedRealtime()
    if (now - lastReadAloudProgressSyncAtMs < READ_ALOUD_PROGRESS_SYNC_MIN_INTERVAL_MS) return
    lastReadAloudProgressSyncAtMs = now
    runCatching {
        val intent = Intent(ReadAloudIntents.ACTION_SYNC_PROGRESS).apply {
            setClassName(ReadAloudIntents.MODULE_PACKAGE_NAME, ReadAloudIntents.COMMAND_RECEIVER_CLASS_NAME)
        }
        appContext.sendBroadcast(intent)
        XposedBridge.log("$LOG_PREFIX read aloud progress sync requested reason=$reason")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX read aloud progress sync request failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaderHook.handleReadAloudHighlightBroadcast(intent: Intent) {
    when (intent.action) {
        ReadAloudIntents.ACTION_CURRENT -> applyReadAloudHighlight(intent)
        ReadAloudIntents.ACTION_CLEAR -> clearReadAloudHighlightFromIntent(intent)
    }
}

internal fun ReaderHook.clearReadAloudHighlightFromIntent(intent: Intent) {
    val sessionId = intent.getStringExtra(ReadAloudIntents.EXTRA_SESSION_ID).orEmpty()
    val bookKey = intent.getStringExtra(ReadAloudIntents.EXTRA_BOOK_KEY).orEmpty()
    val activeSession = activeReadAloudSessionId
    val activeBook = activeReadAloudBookKey
    if (sessionId.isNotBlank() && activeSession.isNotBlank() && sessionId != activeSession &&
        (bookKey.isBlank() || bookKey != activeBook)
    ) {
        return
    }
    if (intent.getBooleanExtra(ReadAloudIntents.EXTRA_CLEAR_SESSION, false)) {
        synchronized(readAloudRestartLock) {
            readAloudRestartSeq += 1
        }
        clearPersistedReadAloudProgress("clear session broadcast")
        activeReadAloudSessionId = ""
        activeReadAloudBookKey = ""
        activeReadAloudPaused = false
        lastReadAloudFollowCfi = ""
        lastReadAloudFollowAtMs = 0L
        lastReadAloudPageKey = ""
    }
    clearReadAloudHighlight()
}

internal fun ReaderHook.applyReadAloudHighlight(intent: Intent) {
    val sessionId = intent.getStringExtra(ReadAloudIntents.EXTRA_SESSION_ID).orEmpty()
    val bookKey = intent.getStringExtra(ReadAloudIntents.EXTRA_BOOK_KEY).orEmpty()
    val currentBookKey = lastCatalogContext?.let(::bookKey).orEmpty()
    val isCurrentReaderBook = isReadAloudBookCurrentForUi(bookKey, currentBookKey)
    val playing = intent.getBooleanExtra(ReadAloudIntents.EXTRA_PLAYING, true)
    val restoredProgress = intent.getBooleanExtra(ReadAloudIntents.EXTRA_RESTORED_PROGRESS, false)
    val playbackStarted = intent.getBooleanExtra(ReadAloudIntents.EXTRA_PLAYBACK_STARTED, restoredProgress)
    val progressRecordable = intent.getBooleanExtra(ReadAloudIntents.EXTRA_PROGRESS_RECORDABLE, restoredProgress)
    if (!restoredProgress && sessionId.isNotBlank() && activeReadAloudSessionId.isNotBlank() && sessionId != activeReadAloudSessionId) {
        return
    }
    if (!restoredProgress && bookKey.isNotBlank() && activeReadAloudBookKey.isNotBlank() && bookKey != activeReadAloudBookKey) {
        return
    }
    if (!restoredProgress || playing) {
        if (activeReadAloudSessionId.isBlank()) activeReadAloudSessionId = sessionId
        if (activeReadAloudBookKey.isBlank()) activeReadAloudBookKey = bookKey
        activeReadAloudPaused = !playing
    }
    if (!playbackStarted) {
        if (!playing) clearReadAloudHighlight()
        XposedBridge.log("$LOG_PREFIX read aloud progress ignored before playback start session=$sessionId")
        return
    }

    val startCfi = intent.getStringExtra(ReadAloudIntents.EXTRA_START_CFI).orEmpty()
    if (startCfi.isBlank()) {
        clearReadAloudHighlight()
        return
    }
    val spokenText = intent.getStringExtra(ReadAloudIntents.EXTRA_TEXT).orEmpty().trim()
    if (spokenText.isBlank()) return
    val highlightText = intent.getStringExtra(ReadAloudIntents.EXTRA_HIGHLIGHT_TEXT)
        .orEmpty()
        .trim()
        .ifBlank { spokenText }
    val endCfi = intent.getStringExtra(ReadAloudIntents.EXTRA_END_CFI).orEmpty().ifBlank { startCfi }
    val paragraphIndex = intent.getIntExtra(ReadAloudIntents.EXTRA_PARAGRAPH_INDEX, 0).coerceAtLeast(0)
    val chapterIndex = intent.getIntExtra(ReadAloudIntents.EXTRA_CHAPTER_INDEX, 0).coerceAtLeast(0)
    val chapterTitle = intent.getStringExtra(ReadAloudIntents.EXTRA_CHAPTER_TITLE).orEmpty()
        .ifBlank { fallbackCurrentChapterTitle() }
    val elapsedMs = intent.getLongExtra(ReadAloudIntents.EXTRA_PLAYBACK_ELAPSED_MS, 0L).coerceAtLeast(0L)
    val progress = if (progressRecordable) {
        rememberReadAloudProgress(
            sessionId = sessionId,
            bookKey = bookKey,
            bookTitle = intent.getStringExtra(ReadAloudIntents.EXTRA_BOOK_TITLE).orEmpty(),
            startCfi = startCfi,
            endCfi = endCfi,
            paragraphIndex = paragraphIndex,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            summary = spokenText,
            elapsedMs = elapsedMs,
        )
    } else {
        null
    }
    if (isCurrentReaderBook) {
        if (progress != null) dispatchReadAloudStatistics(progress, "current")
        if (progress != null && (restoredProgress || !playing)) {
            scheduleRestorePersistedReadAloudProgress("read aloud progress broadcast")
        }
    } else {
        XposedBridge.log("$LOG_PREFIX read aloud progress kept for background book cfi=$startCfi")
        return
    }
    val id = READ_ALOUD_HIGHLIGHT_MARK_ID_BASE + (paragraphIndex % READ_ALOUD_HIGHLIGHT_MARK_ID_RANGE)
    val mark = createReaderMark(
        id = id,
        chapter = chapterTitle,
        startCfi = startCfi,
        endCfi = endCfi,
        quote = highlightText,
        style = MARK_STYLE_FILL,
        color = MARK_COLOR_RED,
    ) ?: return
    activeReadAloudHighlightId = id
    activeReadAloudHighlightMark = mark
    XposedBridge.log("$LOG_PREFIX read aloud highlight active ${describeSearchHighlightMark(mark)}")
    injectReadAloudHighlight(mark)
    if (playing) {
        scheduleReadAloudHighlightFollow(
            expectedBookKey = bookKey,
            startCfi = startCfi,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            text = spokenText,
            mark = mark,
        )
    }
}

internal fun ReaderHook.scheduleReadAloudHighlightFollow(
    expectedBookKey: String,
    startCfi: String,
    chapterIndex: Int,
    chapterTitle: String,
    text: String,
    mark: Any,
) {
    if (!shouldFollowReadAloudHighlight(expectedBookKey, startCfi)) return
    val now = SystemClock.elapsedRealtime()
    if (startCfi == lastReadAloudFollowCfi && now - lastReadAloudFollowAtMs < READ_ALOUD_FOLLOW_MIN_INTERVAL_MS) {
        return
    }
    lastReadAloudFollowCfi = startCfi
    lastReadAloudFollowAtMs = now
    val activity = activityProvider() ?: return
    val block = {
        if (shouldFollowReadAloudHighlight(expectedBookKey, startCfi)) {
            suppressReadAloudRestartUntilMs = SystemClock.elapsedRealtime() + READ_ALOUD_RESTART_SUPPRESS_MS
            val jumped = runCatching {
                jumpToCfi(
                    receiver = lastCatalogContext?.intentReceiver,
                    viewModel = currentViewModelRef?.get(),
                    cfi = startCfi,
                    mark = mark,
                    chapterIndex = chapterIndex,
                    title = chapterTitle,
                    summary = text.take(120),
                )
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX read aloud follow jump failed: ${it.stackTraceToString()}")
            }.getOrDefault(false)
            XposedBridge.log("$LOG_PREFIX read aloud follow jump dispatched=$jumped cfi=$startCfi")
            if (jumped) {
                injectReadAloudHighlight(mark)
            }
        }
    }
    activity.runOnUiThread {
        activity.window?.decorView?.post(block) ?: block()
    }
}

internal fun ReaderHook.shouldFollowReadAloudHighlight(expectedBookKey: String, startCfi: String): Boolean {
    if (!isActivityResumedProvider()) return false
    if (startCfi.isBlank() || !isValidEpubCfi(startCfi)) return false
    if (currentPageRef?.get() == null || currentEpubRoot() == null) return false
    val currentContext = lastCatalogContext ?: return false
    val currentBookKey = bookKey(currentContext)
    if (currentBookKey.isBlank()) return false
    val normalizedExpectedBookKey = expectedBookKey.ifBlank { activeReadAloudBookKey }
    return normalizedExpectedBookKey.isNotBlank() &&
        currentBookKey == normalizedExpectedBookKey &&
        activeReadAloudBookKey == normalizedExpectedBookKey
}

internal fun ReaderHook.isReadAloudBookCurrentForUi(incomingBookKey: String, currentBookKey: String): Boolean {
    if (incomingBookKey.isBlank()) return true
    if (currentBookKey.isNotBlank()) return currentBookKey == incomingBookKey
    val currentRoot = currentEpubRoot()?.absolutePath.orEmpty()
    if (currentRoot.isNotBlank()) return incomingBookKey.contains(currentRoot)
    return currentViewModelRef?.get() == null && lastCatalogContext == null
}

internal fun ReaderHook.rememberReadAloudProgress(
    sessionId: String,
    bookKey: String,
    bookTitle: String,
    startCfi: String,
    endCfi: String,
    paragraphIndex: Int,
    chapterIndex: Int,
    chapterTitle: String,
    summary: String,
    elapsedMs: Long,
): PersistedReadAloudProgress {
    val existing = readPersistedReadAloudProgress()
    val recordedElapsedMs = existing
        ?.takeIf { it.sessionId == sessionId && it.bookKey == bookKey }
        ?.recordedElapsedMs
        ?.coerceAtMost(elapsedMs)
        ?: 0L
    val context = lastCatalogContext
    val progress = PersistedReadAloudProgress(
        timestamp = System.currentTimeMillis(),
        sessionId = sessionId,
        bookKey = bookKey,
        bookIdentity = context?.let(::searchBookIdentity).orEmpty(),
        epubRoot = currentEpubRoot()?.absolutePath.orEmpty(),
        bookTitle = bookTitle.ifBlank { context?.let(::bookTitle).orEmpty() }.ifBlank { fallbackCurrentBookTitle() },
        target = ReadingTarget(
            cfi = startCfi,
            chapterIndex = chapterIndex,
            title = chapterTitle.ifBlank { fallbackCurrentChapterTitle() },
            summary = summary.take(240),
        ),
        endCfi = endCfi.ifBlank { startCfi },
        paragraphIndex = paragraphIndex,
        elapsedMs = elapsedMs,
        recordedElapsedMs = recordedElapsedMs,
    )
    persistReadAloudProgress(progress)
    return progress
}

internal fun ReaderHook.dispatchReadAloudStatistics(progress: PersistedReadAloudProgress, reason: String): Boolean {
    val viewModel = currentViewModelRef?.get()
    val receiver = lastCatalogContext?.intentReceiver
    if (viewModel == null && receiver == null) return false
    if (!isPersistedReadAloudProgressForCurrentBook(progress)) return false
    val page = createReadAloudStatisticsPage(progress) ?: return false
    val intent = createReaderStatisticsIntent(page) ?: return false
    if (
        progress.sessionId == lastReadAloudStatisticsSessionId &&
        progress.target.cfi == lastReadAloudStatisticsCfi &&
        progress.elapsedMs <= lastReadAloudStatisticsElapsedMs
    ) {
        return false
    }
    val durationDeltaMs = (progress.elapsedMs - progress.recordedElapsedMs).coerceAtLeast(0L)
    if (viewModel != null) {
        setReaderStatisticsTimer(viewModel, durationDeltaMs)
    }
    val sent = runCatching {
        isDispatchingReadAloudStatistics = true
        dispatchReaderIntent(receiver, viewModel, intent, "ReadAloudStatistics")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX read aloud statistics dispatch failed: ${it.stackTraceToString()}")
    }.getOrDefault(false).also {
        isDispatchingReadAloudStatistics = false
    }
    if (!sent) return false

    lastReadAloudStatisticsSessionId = progress.sessionId
    lastReadAloudStatisticsCfi = progress.target.cfi
    lastReadAloudStatisticsElapsedMs = progress.elapsedMs
    persistReadAloudProgress(progress.copy(recordedElapsedMs = progress.elapsedMs))
    XposedBridge.log(
        "$LOG_PREFIX read aloud statistics recorded reason=$reason " +
            "deltaMs=$durationDeltaMs elapsedMs=${progress.elapsedMs} cfi=${progress.target.cfi}",
    )
    return true
}

internal fun ReaderHook.createReadAloudStatisticsPage(progress: PersistedReadAloudProgress): Any? =
    runCatching {
        val template = currentPageRef?.get() ?: return@runCatching null
        val document = callNoArg(template, "getDocument") ?: return@runCatching null
        val start = createEpubCfi(progress.target.cfi) ?: return@runCatching null
        val end = createEpubCfi(progress.endCfi.ifBlank { progress.target.cfi }) ?: start
        val pageClass = classLoader.loadClass(EPUB_PAGE_CLASS)
        val documentClass = classLoader.loadClass(HTML_DOCUMENT_CLASS)
        val cfiClass = classLoader.loadClass(EPUB_CFI_CLASS)
        val page = pageClass
            .getDeclaredConstructor(documentClass, cfiClass, cfiClass, String::class.java)
            .newInstance(document, start, end, progress.target.summary)
        setString(page, "setBookTitle", progress.bookTitle.ifBlank { fallbackCurrentBookTitle() })
        setInt(page, "setContentType", EPUB_PAGE_CONTENT)
        setInt(page, "setChapterIndex", progress.target.chapterIndex.coerceAtLeast(0))
        setInt(page, "setChapterTotal", readAloudChapterTotal(template))
        setInt(page, "setSpineIndex", readAloudSpineIndex(start, template))
        setString(page, "setChapter", progress.target.title)
        setFloat(page, "setPercentage", estimateReadAloudPercentage(progress, template))
        setInt(page, "setNumber", (callNoArg(template, "getNumber") as? Number)?.toInt() ?: 0)
        setInt(page, "setMaxNumber", (callNoArg(template, "getMaxNumber") as? Number)?.toInt() ?: 0)
        page
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX read aloud statistics page failed: ${it.stackTraceToString()}")
    }.getOrNull()

internal fun ReaderHook.readAloudChapterTotal(template: Any): Int {
    val fromTemplate = (callNoArg(template, "getChapterTotal") as? Number)?.toInt() ?: 0
    if (fromTemplate > 0) return fromTemplate
    return lastCatalogContext?.catalog?.size?.takeIf { it > 0 } ?: 0
}

internal fun ReaderHook.readAloudSpineIndex(startCfi: Any, template: Any): Int {
    val fromCfi = (callNoArg(startCfi, "getSpineIndex") as? Number)?.toInt()
    if (fromCfi != null && fromCfi >= 0) return fromCfi
    return (callNoArg(template, "getSpineIndex") as? Number)?.toInt() ?: 0
}

internal fun ReaderHook.estimateReadAloudPercentage(progress: PersistedReadAloudProgress, template: Any): Float {
    val templateStart = callNoArg(template, "getStart")?.toString().orEmpty()
    val templateAnchor = callString(template, "getAnchor")
    if (progress.target.cfi == templateStart || progress.target.cfi == templateAnchor) {
        return ((callNoArg(template, "getPercentage") as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f)
    }
    val total = readAloudChapterTotal(template)
    if (total > 0 && progress.target.chapterIndex >= 0) {
        return (progress.target.chapterIndex.toFloat() / total.toFloat()).coerceIn(0f, 0.9999f)
    }
    return ((callNoArg(template, "getPercentage") as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f)
}

internal fun ReaderHook.scheduleRestorePersistedReadAloudProgress(reason: String) {
    if (pendingReadAloudProgressRestore) return
    val activity = activityProvider() ?: return
    val persisted = readPersistedReadAloudProgress() ?: return
    if (!canRestoreReadAloudProgress(persisted, reason)) return
    if (!isPersistedReadAloudProgressForCurrentBook(persisted)) return
    val restoreKey = readAloudProgressRestoreKey(persisted)
    if (restoreKey == lastRestoredReadAloudProgressKey) return
    pendingReadAloudProgressRestore = true
    activity.window?.decorView?.postDelayed({
        restorePersistedReadAloudProgress(reason)
    }, READ_ALOUD_PROGRESS_RESTORE_DELAY_MS)
}

internal fun ReaderHook.restorePersistedReadAloudProgress(reason: String) {
    val persisted = readPersistedReadAloudProgress()
    if (persisted == null) {
        pendingReadAloudProgressRestore = false
        return
    }
    if (!isPersistedReadAloudProgressForCurrentBook(persisted)) {
        pendingReadAloudProgressRestore = false
        return
    }
    if (!canRestoreReadAloudProgress(persisted, reason)) {
        pendingReadAloudProgressRestore = false
        return
    }
    val viewModel = currentViewModelRef?.get()
    val receiver = lastCatalogContext?.intentReceiver
    if (viewModel == null && receiver == null) {
        pendingReadAloudProgressRestore = false
        return
    }
    val restoreKey = readAloudProgressRestoreKey(persisted)
    suppressReadAloudRestartUntilMs = SystemClock.elapsedRealtime() + READ_ALOUD_RESTART_SUPPRESS_MS
    val jumped = runCatching {
        jumpToCfi(
            receiver = receiver,
            viewModel = viewModel,
            cfi = persisted.target.cfi,
            chapterIndex = persisted.target.chapterIndex,
            title = persisted.target.title,
            summary = persisted.target.summary,
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX read aloud progress restore failed: ${it.stackTraceToString()}")
    }.getOrDefault(false)
    if (jumped) {
        lastRestoredReadAloudProgressKey = restoreKey
        dispatchReadAloudStatistics(persisted, "restore")
        XposedBridge.log("$LOG_PREFIX read aloud progress restored reason=$reason cfi=${persisted.target.cfi}")
    }
    pendingReadAloudProgressRestore = false
}

internal fun ReaderHook.canRestoreReadAloudProgress(progress: PersistedReadAloudProgress, reason: String): Boolean {
    val activeSession = activeReadAloudSessionId
    if (activeSession.isBlank()) {
        XposedBridge.log("$LOG_PREFIX read aloud progress restore skipped without active session reason=$reason")
        clearPersistedReadAloudProgress("restore skipped without active session: $reason")
        return false
    }
    if (activeReadAloudPaused) {
        XposedBridge.log("$LOG_PREFIX read aloud progress restore skipped while paused reason=$reason")
        clearPersistedReadAloudProgress("restore skipped while paused: $reason")
        return false
    }
    if (progress.sessionId.isNotBlank() && progress.sessionId != activeSession) {
        XposedBridge.log(
            "$LOG_PREFIX read aloud progress restore skipped stale session " +
                "active=$activeSession persisted=${progress.sessionId} reason=$reason",
        )
        clearPersistedReadAloudProgress("restore skipped stale session: $reason")
        return false
    }
    return true
}

internal fun ReaderHook.persistReadAloudProgress(progress: PersistedReadAloudProgress) {
    val context = hostApplicationContext() ?: return
    context
        .getSharedPreferences(READ_ALOUD_PROGRESS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putLong(READ_ALOUD_PROGRESS_KEY_TIMESTAMP, progress.timestamp)
        .putString(READ_ALOUD_PROGRESS_KEY_SESSION, progress.sessionId)
        .putString(READ_ALOUD_PROGRESS_KEY_BOOK, progress.bookKey)
        .putString(READ_ALOUD_PROGRESS_KEY_BOOK_IDENTITY, progress.bookIdentity)
        .putString(READ_ALOUD_PROGRESS_KEY_EPUB_ROOT, progress.epubRoot)
        .putString(READ_ALOUD_PROGRESS_KEY_BOOK_TITLE, progress.bookTitle)
        .putString(READ_ALOUD_PROGRESS_KEY_CFI, progress.target.cfi)
        .putString(READ_ALOUD_PROGRESS_KEY_END_CFI, progress.endCfi)
        .putInt(READ_ALOUD_PROGRESS_KEY_PARAGRAPH_INDEX, progress.paragraphIndex)
        .putInt(READ_ALOUD_PROGRESS_KEY_CHAPTER_INDEX, progress.target.chapterIndex)
        .putString(READ_ALOUD_PROGRESS_KEY_TITLE, progress.target.title)
        .putString(READ_ALOUD_PROGRESS_KEY_SUMMARY, progress.target.summary)
        .putLong(READ_ALOUD_PROGRESS_KEY_ELAPSED_MS, progress.elapsedMs)
        .putLong(READ_ALOUD_PROGRESS_KEY_RECORDED_ELAPSED_MS, progress.recordedElapsedMs)
        .putBoolean(READ_ALOUD_PROGRESS_KEY_PLAYBACK_STARTED, true)
        .apply()
}

internal fun ReaderHook.clearPersistedReadAloudProgress(reason: String) {
    pendingReadAloudProgressRestore = false
    lastRestoredReadAloudProgressKey = ""
    hostApplicationContext()
        ?.getSharedPreferences(READ_ALOUD_PROGRESS_PREFS, Context.MODE_PRIVATE)
        ?.edit()
        ?.clear()
        ?.apply()
    requestReadAloudProgressStoreClear(reason)
    XposedBridge.log("$LOG_PREFIX read aloud persisted progress cleared reason=$reason")
}

internal fun ReaderHook.requestReadAloudProgressStoreClear(reason: String) {
    val context = hostApplicationContext() ?: return
    runCatching {
        context.sendBroadcast(
            Intent(ReadAloudIntents.ACTION_CLEAR_PROGRESS).apply {
                setClassName(ReadAloudIntents.MODULE_PACKAGE_NAME, ReadAloudIntents.COMMAND_RECEIVER_CLASS_NAME)
            },
        )
        XposedBridge.log("$LOG_PREFIX read aloud module progress clear requested reason=$reason")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX read aloud module progress clear request failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaderHook.readPersistedReadAloudProgress(): PersistedReadAloudProgress? {
    val context = hostApplicationContext() ?: return null
    val prefs = context.getSharedPreferences(READ_ALOUD_PROGRESS_PREFS, Context.MODE_PRIVATE)
    val cfi = prefs.getString(READ_ALOUD_PROGRESS_KEY_CFI, null)?.takeIf { it.isNotBlank() } ?: return null
    if (!prefs.getBoolean(READ_ALOUD_PROGRESS_KEY_PLAYBACK_STARTED, false)) return null
    return PersistedReadAloudProgress(
        timestamp = prefs.getLong(READ_ALOUD_PROGRESS_KEY_TIMESTAMP, 0L),
        sessionId = prefs.getString(READ_ALOUD_PROGRESS_KEY_SESSION, null).orEmpty(),
        bookKey = prefs.getString(READ_ALOUD_PROGRESS_KEY_BOOK, null).orEmpty(),
        bookIdentity = prefs.getString(READ_ALOUD_PROGRESS_KEY_BOOK_IDENTITY, null).orEmpty(),
        epubRoot = prefs.getString(READ_ALOUD_PROGRESS_KEY_EPUB_ROOT, null).orEmpty(),
        bookTitle = prefs.getString(READ_ALOUD_PROGRESS_KEY_BOOK_TITLE, null).orEmpty(),
        target = ReadingTarget(
            cfi = cfi,
            chapterIndex = prefs.getInt(READ_ALOUD_PROGRESS_KEY_CHAPTER_INDEX, 0),
            title = prefs.getString(READ_ALOUD_PROGRESS_KEY_TITLE, null).orEmpty().ifBlank { "\u542c\u4e66\u8fdb\u5ea6" },
            summary = prefs.getString(READ_ALOUD_PROGRESS_KEY_SUMMARY, null).orEmpty(),
        ),
        endCfi = prefs.getString(READ_ALOUD_PROGRESS_KEY_END_CFI, null).orEmpty().ifBlank { cfi },
        paragraphIndex = prefs.getInt(READ_ALOUD_PROGRESS_KEY_PARAGRAPH_INDEX, 0),
        elapsedMs = prefs.getLong(READ_ALOUD_PROGRESS_KEY_ELAPSED_MS, 0L).coerceAtLeast(0L),
        recordedElapsedMs = prefs.getLong(READ_ALOUD_PROGRESS_KEY_RECORDED_ELAPSED_MS, 0L).coerceAtLeast(0L),
    )
}

internal fun ReaderHook.isPersistedReadAloudProgressForCurrentBook(persisted: PersistedReadAloudProgress): Boolean {
    if (System.currentTimeMillis() - persisted.timestamp > READ_ALOUD_PROGRESS_MAX_AGE_MS) return false
    lastCatalogContext?.let { context ->
        val currentKey = bookKey(context)
        if (currentKey.isNotBlank() && persisted.bookKey.isNotBlank() && currentKey == persisted.bookKey) {
            return true
        }
        val currentIdentity = searchBookIdentity(context)
        if (
            currentIdentity.isNotBlank() &&
            persisted.bookIdentity.isNotBlank() &&
            currentIdentity == persisted.bookIdentity
        ) {
            return true
        }
    }
    val currentRoot = currentEpubRoot()?.absolutePath.orEmpty()
    return currentRoot.isNotBlank() &&
        persisted.epubRoot.isNotBlank() &&
        currentRoot == persisted.epubRoot
}

internal fun ReaderHook.readAloudProgressRestoreKey(progress: PersistedReadAloudProgress): String =
    listOf(progress.sessionId, progress.bookKey, progress.target.cfi, progress.timestamp.toString()).joinToString("|")

internal fun ReaderHook.openReadAloud() {
    val activity = activityProvider() ?: return
    if (!canShowReaderReadAloudEntry()) {
        removeReadAloudMenuButton()
        Toast.makeText(activity, "\u6682\u65e0\u6cd5\u542c\u5f53\u524d\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
        return
    }
    val context = bottomReadAloudContext(bottomReadAloudReceiverRef?.get(), bottomReadAloudBookRef?.get())
    if (context == null) {
        Toast.makeText(activity, "\u6682\u65e0\u6cd5\u542c\u5f53\u524d\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
        return
    }
    ensureReadAloudHighlightReceiver()
    Toast.makeText(activity, "\u6b63\u5728\u51c6\u5907\u542c\u4e66", Toast.LENGTH_SHORT).show()
    val requestedAt = SystemClock.elapsedRealtime()
    Thread {
        var sessionId = ""
        runCatching {
            val buildStartedAt = SystemClock.elapsedRealtime()
            val segments = buildReadAloudSegments(
                context = context,
                maxSegments = READ_ALOUD_INITIAL_SEGMENTS,
                currentFileOnly = true,
            )
            val buildMs = SystemClock.elapsedRealtime() - buildStartedAt
            XposedBridge.log(
                "$LOG_PREFIX read aloud initial queue built segments=${segments.size} " +
                    "chars=${segments.sumOf { it.text.length }} buildMs=$buildMs",
            )
            if (segments.isEmpty()) error("empty read-aloud queue")
            val queueStartedAt = SystemClock.elapsedRealtime()
            val source = selectedTtsSource(activity)
            sessionId = startReadAloudService(
                activity = activity,
                bookKey = bookKey(context),
                bookTitle = bookTitle(context).ifBlank { titleFromCurrentEpubRoot() },
                coverUri = currentBookCoverUri(context),
                segments = segments,
                source = source,
            )
            appendReadAloudRemainderAsync(activity, context, sessionId, segments.size)
            XposedBridge.log(
                "$LOG_PREFIX read aloud play requested session=$sessionId " +
                    "source=${source?.name ?: "system"} " +
                    "queueMs=${SystemClock.elapsedRealtime() - queueStartedAt} " +
                    "totalMs=${SystemClock.elapsedRealtime() - requestedAt}",
            )
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    if (source != null) {
                        "\u5df2\u5f00\u59cb\u542c\u4e66\uff1a${source.name}"
                    } else {
                        "\u5df2\u5f00\u59cb\u542c\u4e66\uff1a\u7cfb\u7edf TTS"
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud start failed: ${it.stackTraceToString()}")
            if (sessionId.isNotBlank()) stopReadAloudSession(activity, sessionId, "start-failed")
            activity.runOnUiThread {
                Toast.makeText(activity, "\u542c\u4e66\u542f\u52a8\u5931\u8d25", Toast.LENGTH_SHORT).show()
            }
        }
    }.apply {
        name = "ReaMicroReadAloudPrepare"
        isDaemon = true
        start()
    }
}

internal fun ReaderHook.scheduleReadAloudRestartFromPage(page: Any) {
    val snapshot = settingsProvider()
    if (!snapshot.canRestartReadAloudOnPageTurn) return
    if (activeReadAloudSessionId.isBlank() || activeReadAloudBookKey.isBlank()) return
    val pageKey = currentReadAloudPageKey(page)
    if (pageKey.isBlank()) return
    val now = SystemClock.elapsedRealtime()
    if (now < suppressReadAloudRestartUntilMs) {
        lastReadAloudPageKey = pageKey
        return
    }
    if (pageKey == lastReadAloudPageKey) return
    val context = bottomReadAloudContext(bottomReadAloudReceiverRef?.get(), bottomReadAloudBookRef?.get())
        ?: lastCatalogContext
        ?: return
    val currentBookKey = bookKey(context)
    if (currentBookKey.isBlank() || currentBookKey != activeReadAloudBookKey) return
    lastReadAloudPageKey = pageKey
    val seq = synchronized(readAloudRestartLock) {
        readAloudRestartSeq += 1
        readAloudRestartSeq
    }
    Thread {
        Thread.sleep(READ_ALOUD_PAGE_RESTART_DELAY_MS)
        if (seq != readAloudRestartSeq) return@Thread
        restartReadAloudFromCurrentPage(context, currentBookKey, pageKey)
    }.apply {
        name = "ReaMicroReadAloudPageRestart"
        isDaemon = true
        start()
    }
}

internal fun ReaderHook.restartReadAloudFromCurrentPage(context: CatalogContext, expectedBookKey: String, pageKey: String) {
    val activity = activityProvider() ?: return
    if (!settingsProvider().canRestartReadAloudOnPageTurn) return
    if (activeReadAloudBookKey != expectedBookKey) return
    runCatching {
        val segments = buildReadAloudSegments(
            context = context,
            maxSegments = READ_ALOUD_INITIAL_SEGMENTS,
            currentFileOnly = true,
        )
        if (segments.isEmpty()) error("empty read-aloud queue")
        val source = selectedTtsSource(activity)
        val sessionId = startReadAloudService(
            activity = activity,
            bookKey = expectedBookKey,
            bookTitle = bookTitle(context).ifBlank { titleFromCurrentEpubRoot() },
            coverUri = currentBookCoverUri(context),
            segments = segments,
            source = source,
        )
        appendReadAloudRemainderAsync(activity, context, sessionId, segments.size)
        XposedBridge.log("$LOG_PREFIX read aloud restarted after page turn key=$pageKey")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX read aloud page restart failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaderHook.currentReadAloudPageKey(page: Any?): String =
    epubPageSignature(page)
        ?: epubPageNumber(page)?.let { "n=$it" }
        ?: callString(page, "getAnchor")
            .ifBlank { callNoArg(page, "getStart")?.toString().orEmpty() }

internal fun ReaderHook.createNativeReadAloudAction(icon: Any): Any? =
    createSelectionMenuAction(
        if (settingsProvider().canUseCompactReaderSelectionMenu) "" else "\u968f\u542c",
        icon,
        nativeFunction0 {
            openNativeSelectionReadAloud()
        },
        "create native read aloud action",
    )

internal fun ReaderHook.readAloudImageVector(): Any? {
    val outlined = runCatching {
        classLoader.loadClass(ICONS_OUTLINED_CLASS).getField("INSTANCE").get(null)
    }.getOrNull() ?: return null
    return listOf(
        READ_ALOUD_ICON_VOLUME_UP_CLASS to "getVolumeUp",
        READ_ALOUD_ICON_RECORD_VOICE_OVER_CLASS to "getRecordVoiceOver",
        DICTIONARY_ICON_AUTO_STORIES_CLASS to "getAutoStories",
    ).firstNotNullOfOrNull { (className, methodName) ->
        runCatching {
            classLoader.loadClass(className).declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == 1
            }?.apply { isAccessible = true }?.invoke(null, outlined)
        }.getOrNull()
    }
}

internal fun ReaderHook.openNativeSelectionReadAloud() {
    val activity = activityProvider() ?: return
    if (!canUseReadAloudSelection()) return
    val selection = currentNativeSelectionPayload()
    val quote = selection.quote
    if (quote.isBlank()) {
        Toast.makeText(activity, "\u672a\u83b7\u53d6\u5230\u9009\u4e2d\u6587\u672c", Toast.LENGTH_SHORT).show()
        return
    }
    val context = bottomReadAloudContext(bottomReadAloudReceiverRef?.get(), bottomReadAloudBookRef?.get())
        ?: lastCatalogContext
    if (context == null) {
        Toast.makeText(activity, "\u6682\u65e0\u6cd5\u542c\u5f53\u524d\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
        return
    }
    ensureReadAloudHighlightReceiver()
    callNoArg(selection.controller, "clearSelection")
    Toast.makeText(activity, "\u6b63\u5728\u51c6\u5907\u968f\u542c", Toast.LENGTH_SHORT).show()
    Thread {
        runCatching {
            val segments = buildReadAloudSegmentsFromSelection(
                context = context,
                selection = selection,
                maxSegments = READ_ALOUD_INITIAL_SEGMENTS,
                currentFileOnly = true,
            )
            if (segments.isEmpty()) error("empty read-aloud selection queue")
            val source = selectedTtsSource(activity)
            val sessionId = startReadAloudService(
                activity = activity,
                bookKey = bookKey(context),
                bookTitle = bookTitle(context).ifBlank { titleFromCurrentEpubRoot() },
                coverUri = currentBookCoverUri(context),
                segments = segments,
                source = source,
            )
            appendReadAloudSelectionRemainderAsync(activity, context, selection, sessionId, segments.size)
            activity.runOnUiThread {
                Toast.makeText(activity, "\u5df2\u4ece\u9009\u4e2d\u6bb5\u843d\u5f00\u59cb\u542c\u4e66", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud selection failed: ${it.stackTraceToString()}")
            activity.runOnUiThread {
                Toast.makeText(activity, "\u968f\u542c\u542f\u52a8\u5931\u8d25", Toast.LENGTH_SHORT).show()
            }
        }
    }.apply {
        name = "ReaMicroReadAloudSelection"
        isDaemon = true
        start()
    }
}

internal fun ReaderHook.injectReadAloudHighlight(mark: Any, viewModel: Any? = currentViewModelRef?.get()): Boolean =
    updateSearchMarks(viewModel, "read-aloud-inject") { marks ->
        marks.filterNot(::isReadAloudHighlightMark) + mark
    }

internal fun ReaderHook.clearReadAloudHighlight(viewModel: Any? = currentViewModelRef?.get()): Boolean {
    activeReadAloudHighlightId = null
    activeReadAloudHighlightMark = null
    return updateSearchMarks(viewModel, "read-aloud-clear") { marks ->
        val filtered = marks.filterNot(::isReadAloudHighlightMark)
        if (filtered.size == marks.size) marks else filtered
    }
}

internal fun ReaderHook.isReadAloudHighlightMark(mark: Any): Boolean {
    val id = readAloudHighlightMarkId(mark) ?: return false
    return id >= READ_ALOUD_HIGHLIGHT_MARK_ID_BASE &&
        id < READ_ALOUD_HIGHLIGHT_MARK_ID_BASE + READ_ALOUD_HIGHLIGHT_MARK_ID_RANGE
}

internal fun ReaderHook.readAloudHighlightMarkId(mark: Any): Long? {
    val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: return null
    return if (id >= READ_ALOUD_HIGHLIGHT_MARK_ID_BASE &&
        id < READ_ALOUD_HIGHLIGHT_MARK_ID_BASE + READ_ALOUD_HIGHLIGHT_MARK_ID_RANGE
    ) {
        id
    } else {
        null
    }
}

internal fun ReaderHook.buildReadAloudSegments(
    context: CatalogContext,
    maxSegments: Int = MAX_READ_ALOUD_SEGMENTS,
    currentFileOnly: Boolean = false,
): List<ReadAloudSegment> {
    val key = bookKey(context)
    val startChapterIndex = currentPageRef?.get()
        ?.let { callNoArg(it, "getChapterIndex") as? Number }
        ?.toInt()
        ?: -1
    val cached = searchIndexState
        ?.takeIf { it.bookKey == key && !currentFileOnly }
        ?.documents
    val documents = cached ?: buildReadAloudDocuments(context, maxSegments, currentFileOnly)
    val filtered = if (currentFileOnly) {
        documents
    } else if (startChapterIndex >= 0) {
        documents.filter { it.chapterIndex < 0 || it.chapterIndex >= startChapterIndex }
    } else {
        documents
    }
    val result = ArrayList<ReadAloudSegment>()
    for ((documentIndex, document) in filtered.withIndex()) {
        val offset = readAloudStartOffsetForDocument(document)
        splitReadAloudTextParts(document.text, offset).forEach { part ->
            if (result.size >= maxSegments) return result
            result += ReadAloudSegment(
                chapterTitle = readAloudSegmentChapterTitle(
                    document,
                    isCurrentDocument = documentIndex == 0,
                    textOffset = part.startOffset,
                ),
                chapterIndex = document.chapterIndex,
                text = part.text,
                highlightText = part.highlightText,
                startCfi = document.indexedText.cfiAt(part.startOffset).orEmpty(),
                endCfi = document.indexedText.cfiAtBoundary(part.endOffset)
                    ?: document.indexedText.cfiAt(part.startOffset).orEmpty(),
            )
        }
        if (result.size >= maxSegments) return result
    }
    return result
}

internal fun ReaderHook.buildReadAloudDocuments(
    context: CatalogContext,
    maxSegments: Int,
    currentFileOnly: Boolean,
): List<SearchDocument> {
    val root = currentEpubRoot()
    val epub = currentEpubRef?.get()
    val itemRefs = (epub?.let { callNoArg(it, "getItemRefs") } as? Iterable<*>)?.filterNotNull().orEmpty()
    if (root == null) return emptyList()
    val files = readAloudDocumentFiles(root, context.catalog, itemRefs, currentFileOnly)
    if (files.isEmpty()) {
        logReadAloudCurrentPageProbe(root, itemRefs, "no-current-file")
        return emptyList()
    }
    val documents = ArrayList<SearchDocument>()
    var segmentCount = 0
    forEachSearchDocument(context, files) { document ->
        documents += document
        val offset = readAloudStartOffsetForDocument(document)
        segmentCount += splitReadAloudTextParts(document.text, offset).size
        !currentFileOnly && segmentCount < maxSegments
    }
    return documents
}

internal fun ReaderHook.readAloudDocumentFiles(
    root: File,
    catalog: List<Any>,
    itemRefs: List<Any>,
    currentFileOnly: Boolean,
): List<File> {
    val currentFile = currentTextContentFile(root, itemRefs)
    if (currentFileOnly) return listOfNotNull(currentFile)
    val catalogFiles = catalogTextFiles(root, catalog, itemRefs)
    if (currentFile == null) return catalogFiles
    val currentKey = currentFile.absolutePath
    val currentIndex = catalogFiles.indexOfFirst { file ->
        (file.canonicalFileSafe() ?: file).absolutePath == currentKey
    }
    return if (currentIndex >= 0) {
        catalogFiles.drop(currentIndex)
    } else {
        listOf(currentFile) + catalogFiles
    }
}

internal fun ReaderHook.readAloudStartOffsetForDocument(document: SearchDocument): Int {
    val page = currentPageRef?.get() ?: return 0
    val currentChapterIndex = (callNoArg(page, "getChapterIndex") as? Number)?.toInt() ?: -1
    if (currentChapterIndex >= 0 && document.chapterIndex >= 0 && document.chapterIndex != currentChapterIndex) {
        return 0
    }
    val summary = callString(page, "getSummary").trim()
    if (summary.length >= 8) {
        val offset = selectionOffsetInDocument(document.text, summary)
        if (offset >= 0) return offset
    }
    return 0
}

internal fun ReaderHook.buildReadAloudSegmentsFromSelection(
    context: CatalogContext,
    selection: NativeSelectionPayload,
    maxSegments: Int = MAX_READ_ALOUD_SEGMENTS,
    currentFileOnly: Boolean = false,
): List<ReadAloudSegment> {
    val key = bookKey(context)
    val documents = if (currentFileOnly) {
        buildReadAloudDocuments(context, maxSegments, currentFileOnly = true)
    } else {
        searchIndexState
            ?.takeIf { it.bookKey == key }
            ?.documents
            ?: buildReadAloudDocuments(context, maxSegments, currentFileOnly = false)
    }
    if (documents.isEmpty()) {
        return selection.quote.takeIf { it.isNotBlank() }?.let {
            listOf(
                ReadAloudSegment(
                    fallbackCurrentChapterTitle(),
                    -1,
                    it,
                    it,
                    selection.startCfi,
                    selection.endCfi,
                ),
            )
        }.orEmpty()
    }
    val startChapterIndex = currentPageRef?.get()
        ?.let { callNoArg(it, "getChapterIndex") as? Number }
        ?.toInt()
        ?: -1
    val candidateStart = documents.indexOfFirst { document ->
        startChapterIndex < 0 || document.chapterIndex < 0 || document.chapterIndex >= startChapterIndex
    }.takeIf { it >= 0 } ?: 0
    val location = locateReadAloudSelection(documents, selection.quote, candidateStart)
    val result = ArrayList<ReadAloudSegment>()
    if (location == null && selection.quote.isNotBlank()) {
        result += ReadAloudSegment(
            chapterTitle = fallbackCurrentChapterTitle(),
            chapterIndex = startChapterIndex,
            text = selection.quote,
            highlightText = selection.quote,
            startCfi = selection.startCfi,
            endCfi = selection.endCfi,
        )
    }
    val startIndex = location?.documentIndex ?: candidateStart
    for (index in startIndex until documents.size) {
        val document = documents[index]
        val startOffset = if (index == location?.documentIndex) {
            location.offset.coerceIn(0, document.text.length)
        } else {
            0
        }
        splitReadAloudTextParts(document.text, startOffset).forEach { part ->
            if (result.size >= maxSegments) return@forEach
            result += ReadAloudSegment(
                chapterTitle = readAloudSegmentChapterTitle(
                    document,
                    isCurrentDocument = index == startIndex,
                    textOffset = part.startOffset,
                ),
                chapterIndex = document.chapterIndex,
                text = part.text,
                highlightText = part.highlightText,
                startCfi = document.indexedText.cfiAt(part.startOffset).orEmpty(),
                endCfi = document.indexedText.cfiAtBoundary(part.endOffset)
                    ?: document.indexedText.cfiAt(part.startOffset).orEmpty(),
            )
        }
        if (result.size >= maxSegments) break
    }
    return result
}

internal fun ReaderHook.locateReadAloudSelection(
    documents: List<SearchDocument>,
    quote: String,
    startIndex: Int,
): ReadAloudSelectionLocation? {
    val cleanQuote = quote.trim()
    if (cleanQuote.isBlank()) return null
    val searchOrder = (startIndex until documents.size).asSequence() + (0 until startIndex).asSequence()
    searchOrder.forEach { index ->
        val document = documents[index]
        val offset = selectionOffsetInDocument(document.text, cleanQuote)
        if (offset >= 0) return ReadAloudSelectionLocation(index, offset)
    }
    return null
}

internal fun ReaderHook.splitReadAloudTextParts(
    text: String,
    startOffset: Int = 0,
    endOffset: Int = text.length,
): List<ReadAloudTextPart> {
    val result = ArrayList<ReadAloudTextPart>()
    val safeStart = startOffset.coerceIn(0, text.length)
    val safeEnd = endOffset.coerceIn(safeStart, text.length)
    var lineStart = safeStart
    var index = safeStart
    while (index <= safeEnd) {
        val atEnd = index == safeEnd
        val char = if (atEnd) '\u0000' else text[index]
        if (atEnd || char == '\n' || char == '\r') {
            appendReadAloudChunks(text, lineStart, index, result)
            if (!atEnd && char == '\r' && index + 1 < safeEnd && text[index + 1] == '\n') {
                index++
            }
            lineStart = index + 1
        }
        index++
    }
    if (result.isEmpty()) {
        appendReadAloudChunks(text, safeStart, safeEnd, result)
    }
    return result
}

internal fun ReaderHook.appendReadAloudChunks(
    source: String,
    rangeStart: Int,
    rangeEnd: Int,
    output: MutableList<ReadAloudTextPart>,
) {
    var start = rangeStart.coerceIn(0, source.length)
    var end = rangeEnd.coerceIn(start, source.length)
    while (start < end && source[start].isReadAloudWhitespace()) start++
    while (end > start && source[end - 1].isReadAloudWhitespace()) end--
    if (start >= end) return
    val builder = StringBuilder()
    var chunkStart = start
    var previousWasSpace = false
    fun emit(endExclusive: Int) {
        val spoken = builder.toString().trim()
        if (spoken.isNotBlank()) {
            val safeEnd = endExclusive.coerceIn(chunkStart, source.length)
            val highlight = source.substring(chunkStart, safeEnd).trim()
            output += ReadAloudTextPart(
                text = spoken,
                highlightText = highlight.ifBlank { spoken },
                startOffset = chunkStart,
                endOffset = safeEnd,
            )
        }
        builder.clear()
        previousWasSpace = false
    }
    var index = start
    while (index < end) {
        val char = source[index]
        if (builder.isEmpty()) {
            chunkStart = index
        }
        val normalized = if (char.isReadAloudWhitespace()) ' ' else char
        if (normalized == ' ' && (builder.isEmpty() || previousWasSpace)) {
            if (builder.isEmpty()) chunkStart = index + 1
            index++
            continue
        }
        builder.append(normalized)
        previousWasSpace = normalized == ' '
        val softBreak = char in READ_ALOUD_CHUNK_SOFT_BREAK_CHARS
        var emitEnd = index + 1
        if (softBreak) {
            while (emitEnd < end && source[emitEnd] in READ_ALOUD_CHUNK_TRAILING_CHARS) {
                builder.append(source[emitEnd])
                previousWasSpace = false
                emitEnd++
            }
        }
        val canBreakAtSoftPoint = softBreak && builder.length >= READ_ALOUD_SEGMENT_TARGET_CHARS
        val forcedBreak = builder.length >= READ_ALOUD_SEGMENT_MAX_CHARS
        if (canBreakAtSoftPoint || forcedBreak) {
            emit(emitEnd)
            index = emitEnd
            continue
        }
        index++
    }
    if (builder.isNotBlank()) emit(end)
}

internal fun ReaderHook.mergeShortReadAloudParts(parts: List<ReadAloudTextPart>): List<ReadAloudTextPart> {
    if (parts.size <= 1) return parts
    val merged = ArrayList<ReadAloudTextPart>(parts.size)
    parts.forEach { part ->
        if (merged.isNotEmpty() && shouldMergeReadAloudPart(part.text)) {
            val previous = merged.removeAt(merged.lastIndex)
            merged += previous.mergeWith(part)
        } else {
            merged += part
        }
    }
    return merged
}

internal fun ReaderHook.shouldMergeReadAloudPart(text: String): Boolean {
    val clean = text.trim()
    if (clean.isBlank()) return true
    val core = clean.trim(*READ_ALOUD_WRAPPER_CHARS)
    return clean.firstOrNull() in READ_ALOUD_OPEN_WRAPPER_CHARS &&
        clean.lastOrNull() in READ_ALOUD_CLOSE_WRAPPER_CHARS &&
        core.none { it in READ_ALOUD_INNER_PUNCTUATION_CHARS }
}

internal fun ReaderHook.selectedTtsSource(activity: Activity): TtsSourceEntry? {
    TtsSourceStore.list(activity.applicationContext)
        .firstOrNull { source -> settings?.isTtsSourceEnabled(source.id) == true }
        ?.let { return it }
    if (settings?.isTtsSourceEnabled(ModuleSettings.SYSTEM_TTS_SOURCE_ID) != false) {
        return null
    }
    error("no read-aloud TTS source enabled")
}

internal fun ReaderHook.startReadAloudService(
    activity: Activity,
    bookKey: String,
    bookTitle: String,
    coverUri: String,
    segments: List<ReadAloudSegment>,
    source: TtsSourceEntry?,
): String {
    val chunks = readAloudChunks(segments)
    if (chunks.isEmpty()) error("empty read-aloud chunks")
    val sessionId = prepareReadAloudService(
        activity = activity,
        bookKey = bookKey,
        bookTitle = bookTitle,
        coverUri = coverUri,
        source = source,
        totalChunks = chunks.size,
        initialChunk = chunks.first(),
        autoPlay = true,
    )
    sendReadAloudChunks(activity, sessionId, chunks.drop(1), chunkOffset = 1, totalChunks = chunks.size)
    return sessionId
}

internal fun ReaderHook.prepareReadAloudService(
    activity: Activity,
    bookKey: String,
    bookTitle: String,
    coverUri: String,
    source: TtsSourceEntry?,
    totalChunks: Int,
    initialChunk: List<ReadAloudSegment> = emptyList(),
    autoPlay: Boolean = false,
): String {
    val sessionId = "read_aloud_${System.currentTimeMillis()}"
    synchronized(readAloudRestartLock) {
        readAloudRestartSeq += 1
    }
    activeReadAloudBookKey = bookKey
    activeReadAloudSessionId = sessionId
    activeReadAloudPaused = false
    lastReadAloudFollowCfi = ""
    lastReadAloudFollowAtMs = 0L
    lastReadAloudPageKey = currentReadAloudPageKey(currentPageRef?.get())
    suppressReadAloudRestartUntilMs = SystemClock.elapsedRealtime() + READ_ALOUD_RESTART_SUPPRESS_MS
    sendReadAloudIntent(
        activity,
        Intent(ReadAloudIntents.ACTION_PREPARE)
            .setReadAloudService()
            .putExtra(ReadAloudIntents.EXTRA_SESSION_ID, sessionId)
            .putExtra(ReadAloudIntents.EXTRA_BOOK_KEY, bookKey)
            .putExtra(ReadAloudIntents.EXTRA_BOOK_TITLE, bookTitle)
            .putExtra(ReadAloudIntents.EXTRA_COVER_URI, coverUri)
            .putExtra(ReadAloudIntents.EXTRA_SOURCE_JSON, source?.toJson().orEmpty())
            .putExtra(ReadAloudIntents.EXTRA_TOTAL_CHUNKS, totalChunks.coerceAtLeast(0))
            .putExtra(ReadAloudIntents.EXTRA_AUTO_PLAY, autoPlay)
            .putReadAloudChunkExtras(initialChunk)
            .putExtra(
                ReadAloudIntents.EXTRA_IGNORE_AUDIO_FOCUS,
                settingsProvider().canIgnoreReaderReadAloudAudioFocus,
            )
            .putExtra(
                ReadAloudIntents.EXTRA_LYRICON_ENABLED,
                settingsProvider().canUseReaderReadAloudLyricon,
            )
            .putExtra(
                ModuleLogState.EXTRA_CONCISE_LOG_ENABLED,
                settingsProvider().conciseLogEnabled,
            ),
    )
    return sessionId
}

internal fun ReaderHook.queuePreparedReadAloudService(
    activity: Activity,
    sessionId: String,
    chunks: List<List<ReadAloudSegment>>,
) {
    sendReadAloudChunks(activity, sessionId, chunks, chunkOffset = 0, totalChunks = chunks.size)
    sendReadAloudIntent(
        activity,
        Intent(ReadAloudIntents.ACTION_PLAY)
            .setReadAloudService()
            .putExtra(ReadAloudIntents.EXTRA_SESSION_ID, sessionId),
    )
}

internal fun ReaderHook.stopReadAloudSession(activity: Activity, sessionId: String, reason: String) {
    if (sessionId != activeReadAloudSessionId) return
    XposedBridge.log("$LOG_PREFIX read aloud stop requested session=$sessionId reason=$reason")
    activeReadAloudSessionId = ""
    activeReadAloudBookKey = ""
    activeReadAloudPaused = false
    lastReadAloudFollowCfi = ""
    lastReadAloudFollowAtMs = 0L
    lastReadAloudPageKey = ""
    clearPersistedReadAloudProgress("stop session: $reason")
    clearReadAloudHighlight()
    runCatching {
        sendReadAloudIntent(
            activity,
            Intent(ReadAloudIntents.ACTION_STOP)
                .setReadAloudService()
                .putExtra(ReadAloudIntents.EXTRA_SESSION_ID, sessionId),
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX read aloud stop delivery failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaderHook.stopReadAloudIfPausedOnLeaveReader(reason: String) {
    val sessionId = activeReadAloudSessionId
    if (sessionId.isBlank() || !activeReadAloudPaused) return
    val activity = activityProvider() ?: return
    XposedBridge.log("$LOG_PREFIX read aloud paused on reader leave, stopping session=$sessionId reason=$reason")
    stopReadAloudSession(activity, sessionId, reason)
}

internal fun ReaderHook.appendReadAloudRemainderAsync(
    activity: Activity,
    context: CatalogContext,
    sessionId: String,
    alreadyQueuedSegments: Int,
) {
    Thread {
        runCatching {
            Thread.sleep(READ_ALOUD_BACKGROUND_APPEND_DELAY_MS)
            if (sessionId != activeReadAloudSessionId) return@Thread
            val allSegments = buildReadAloudSegments(
                context = context,
                maxSegments = MAX_READ_ALOUD_SEGMENTS,
                currentFileOnly = false,
            )
            if (sessionId != activeReadAloudSessionId) return@Thread
            val remaining = allSegments.drop(alreadyQueuedSegments)
            if (remaining.isEmpty()) return@Thread
            val initialChunkCount = readAloudChunks(allSegments.take(alreadyQueuedSegments)).size
            val remainingChunks = readAloudChunks(remaining)
            sendReadAloudChunks(
                activity = activity,
                sessionId = sessionId,
                chunks = remainingChunks,
                chunkOffset = initialChunkCount,
                totalChunks = initialChunkCount + remainingChunks.size,
                delayBetweenChunksMs = READ_ALOUD_REMAINDER_CHUNK_DELAY_MS,
            )
            XposedBridge.log(
                "$LOG_PREFIX read aloud appended remainder chunks=${remainingChunks.size} segments=${remaining.size}",
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud append remainder failed: ${it.stackTraceToString()}")
        }
    }.apply {
        name = "ReaMicroReadAloudAppend"
        isDaemon = true
        start()
    }
}

internal fun ReaderHook.appendReadAloudSelectionRemainderAsync(
    activity: Activity,
    context: CatalogContext,
    selection: NativeSelectionPayload,
    sessionId: String,
    alreadyQueuedSegments: Int,
) {
    Thread {
        runCatching {
            Thread.sleep(READ_ALOUD_BACKGROUND_APPEND_DELAY_MS)
            if (sessionId != activeReadAloudSessionId) return@Thread
            val allSegments = buildReadAloudSegmentsFromSelection(
                context = context,
                selection = selection,
                maxSegments = MAX_READ_ALOUD_SEGMENTS,
                currentFileOnly = false,
            )
            if (sessionId != activeReadAloudSessionId) return@Thread
            val remaining = allSegments.drop(alreadyQueuedSegments)
            if (remaining.isEmpty()) return@Thread
            val initialChunkCount = readAloudChunks(allSegments.take(alreadyQueuedSegments)).size
            val remainingChunks = readAloudChunks(remaining)
            sendReadAloudChunks(
                activity = activity,
                sessionId = sessionId,
                chunks = remainingChunks,
                chunkOffset = initialChunkCount,
                totalChunks = initialChunkCount + remainingChunks.size,
                delayBetweenChunksMs = READ_ALOUD_REMAINDER_CHUNK_DELAY_MS,
            )
            XposedBridge.log(
                "$LOG_PREFIX read aloud appended selection remainder chunks=${remainingChunks.size} " +
                    "segments=${remaining.size}",
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud append selection remainder failed: ${it.stackTraceToString()}")
        }
    }.apply {
        name = "ReaMicroReadAloudSelectionAppend"
        isDaemon = true
        start()
    }
}

internal fun ReaderHook.sendReadAloudChunks(
    activity: Activity,
    sessionId: String,
    chunks: List<List<ReadAloudSegment>>,
    chunkOffset: Int,
    totalChunks: Int,
    delayBetweenChunksMs: Long = 0L,
) {
    chunks.forEachIndexed { index, chunk ->
        if (sessionId != activeReadAloudSessionId) return
        sendReadAloudIntent(
            activity,
            Intent(ReadAloudIntents.ACTION_APPEND)
                .setReadAloudService()
                .putExtra(ReadAloudIntents.EXTRA_SESSION_ID, sessionId)
                .putExtra(ReadAloudIntents.EXTRA_CHUNK_INDEX, chunkOffset + index)
                .putExtra(ReadAloudIntents.EXTRA_TOTAL_CHUNKS, totalChunks)
                .putReadAloudChunkExtras(chunk),
        )
        if (delayBetweenChunksMs > 0L && index < chunks.lastIndex) {
            Thread.sleep(delayBetweenChunksMs)
        }
    }
}

internal fun ReaderHook.readAloudChunks(segments: List<ReadAloudSegment>): List<List<ReadAloudSegment>> {
    val chunks = ArrayList<List<ReadAloudSegment>>()
    var current = ArrayList<ReadAloudSegment>()
    var currentChars = 0
    segments.forEach { segment ->
        val nextChars = currentChars + segment.text.length
        if (current.isNotEmpty() && (current.size >= READ_ALOUD_SEGMENTS_PER_CHUNK || nextChars >= READ_ALOUD_CHUNK_MAX_CHARS)) {
            chunks += current
            current = ArrayList()
            currentChars = 0
        }
        current += segment
        currentChars += segment.text.length
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

internal fun ReaderHook.sendReadAloudIntent(activity: Activity, intent: Intent) {
    val action = intent.action.orEmpty()
    if (startReadAloudServiceFromContext(activity, intent, "host")) return
    val moduleContext = runCatching {
        activity.createPackageContext(ReadAloudIntents.MODULE_PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY)
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX read aloud module context unavailable action=$action: ${it.message}")
    }.getOrNull()
    if (moduleContext != null && startReadAloudServiceFromContext(moduleContext, intent, "module-context")) return
    val preferActivity = action == ReadAloudIntents.ACTION_PREPARE
    if (preferActivity && startReadAloudCommandActivity(activity, intent)) return
    if (sendReadAloudCommandBroadcast(activity, intent)) return
    if (!preferActivity && startReadAloudCommandActivity(activity, intent)) return
    error("read aloud command delivery failed action=$action")
}

internal fun ReaderHook.sendReadAloudCommandBroadcast(activity: Activity, intent: Intent): Boolean {
    val action = intent.action.orEmpty()
    return runCatching {
        val command = Intent(intent).apply {
            setClassName(ReadAloudIntents.MODULE_PACKAGE_NAME, ReadAloudIntents.COMMAND_RECEIVER_CLASS_NAME)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
        }
        activity.sendBroadcast(command)
        XposedBridge.log("$LOG_PREFIX read aloud command broadcast sent action=$action")
        true
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX read aloud command broadcast failed action=$action: ${it.stackTraceToString()}")
    }.getOrDefault(false)
}

internal fun ReaderHook.startReadAloudServiceFromContext(context: Context, intent: Intent, label: String): Boolean {
    val action = intent.action.orEmpty()
    val started = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(Intent(intent).setReadAloudService())
        } else {
            context.startService(Intent(intent).setReadAloudService())
        }
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX read aloud $label service intent failed action=$action: ${it.stackTraceToString()}")
    }.getOrNull()
    if (started == null) {
        XposedBridge.log("$LOG_PREFIX read aloud $label service returned null action=$action")
        return false
    }
    XposedBridge.log("$LOG_PREFIX read aloud $label service started action=$action component=$started")
    return true
}

internal fun ReaderHook.startReadAloudCommandActivity(activity: Activity, intent: Intent): Boolean {
    val action = intent.action.orEmpty()
    return runCatching {
        val command = Intent(intent).apply {
            setReadAloudCommandActivity()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        activity.startActivity(command)
        XposedBridge.log("$LOG_PREFIX read aloud command activity started action=$action")
        true
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX read aloud command activity failed action=$action: ${it.stackTraceToString()}")
    }.getOrDefault(false)
}

internal fun ReaderHook.directReadAloudChapterTitle(chapter: CatalogChapterEntry?): String =
    chapter?.chapter
        ?.let(::catalogChapterTitle)
        ?.normalizeChapterTitle()
        .orEmpty()

internal fun ReaderHook.readAloudSegmentChapterTitle(
    document: SearchDocument,
    isCurrentDocument: Boolean,
    textOffset: Int,
): String {
    val anchorTitle = document.chapterAnchors
        .lastOrNull { it.textStart <= textOffset }
        ?.let { anchor -> catalogChapterTitle(anchor.chapter).normalizeChapterTitle() }
        .orEmpty()
    return chooseReadAloudDirectTitle(document.readAloudChapterTitle, anchorTitle)
        .ifBlank {
            if (isCurrentDocument) fallbackCurrentChapterTitle().normalizeChapterTitle() else ""
        }
        .ifBlank { document.chapterTitle }
}

internal fun ReaderHook.chooseReadAloudDirectTitle(primary: String, fallback: String): String {
    val cleanPrimary = primary.normalizeChapterTitle()
    val cleanFallback = fallback.normalizeChapterTitle()
    return when {
        cleanPrimary.isBlank() -> cleanFallback
        cleanFallback.isBlank() -> cleanPrimary
        cleanPrimary == cleanFallback -> cleanPrimary
        cleanFallback.length >= 4 && cleanPrimary.endsWith(cleanFallback) -> cleanFallback
        cleanFallback.length >= 4 && cleanPrimary.contains(cleanFallback) -> cleanFallback
        else -> cleanPrimary
    }
}

internal fun ReaderHook.readAloudHtmlChapterTitleHint(raw: String): String {
    Regex("""(?is)<h[1-3]\b[^>]*>(.*?)</h[1-3]>""")
        .find(raw)
        ?.groupValues
        ?.getOrNull(1)
        ?.normalizeChapterTitle()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return Regex("""(?is)<title\b[^>]*>(.*?)</title>""")
        .find(raw)
        ?.groupValues
        ?.getOrNull(1)
        ?.normalizeChapterTitle()
        .orEmpty()
}

internal fun ReaderHook.readAloudPageHrefCandidates(page: Any): List<String> =
    buildList {
        listOf(
            "getHref",
            "getPath",
            "getFile",
            "getFileName",
            "getSource",
            "getSrc",
        ).forEach { method ->
            callString(page, method).takeIf { it.isNotBlank() }?.let(::add)
        }
        val chapter = callNoArg(page, "getChapter")
        listOf("getHref", "getPath", "getFile", "getSource", "getSrc").forEach { method ->
            callString(chapter, method).takeIf { it.isNotBlank() }?.let(::add)
        }
    }.distinct()

internal fun ReaderHook.logReadAloudCurrentPageProbe(root: File, itemRefs: Iterable<*>, reason: String) {
    val page = currentPageRef?.get()
    val key = listOf(
        reason,
        root.absolutePath,
        page?.javaClass?.name.orEmpty(),
        callString(page, "getAnchor"),
        callString(page, "getSummary").take(48),
    ).joinToString("|")
    if (key == readAloudPageProbeLogKey) return
    readAloudPageProbeLogKey = key
    val methodDump = listOf(
        "getAnchor",
        "getStart",
        "getSpineIndex",
        "getChapterIndex",
        "getPageIndex",
        "getSummary",
        "getHref",
        "getPath",
        "getFile",
    ).joinToString(";") { method ->
        "$method=${callNoArg(page, method)?.toString()?.take(120).orEmpty()}"
    }
    val fieldDump = page?.javaClass?.declaredFields
        ?.take(16)
        ?.joinToString(";") { field ->
            runCatching {
                field.isAccessible = true
                "${field.name}=${field.get(page)?.toString()?.take(120).orEmpty()}"
            }.getOrDefault("${field.name}=?")
        }
        .orEmpty()
    val itemRefDump = itemRefs.take(8).joinToString(";") { item ->
        "href=${callString(item, "getHref")},index=${callNoArg(item, "getIndex")},spine=${callNoArg(item, "getSpineIndex")}"
    }
    XposedBridge.log(
        "$LOG_PREFIX read aloud current file probe reason=$reason page=${page?.javaClass?.name} " +
            "methods=[$methodDump] fields=[$fieldDump] itemRefs=[$itemRefDump]",
    )
}
