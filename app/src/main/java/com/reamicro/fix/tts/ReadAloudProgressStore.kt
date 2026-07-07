package com.reamicro.fix.tts

import android.content.Context
import android.content.Intent

object ReadAloudProgressStore {
    private const val PREFS_NAME = "reamicro_read_aloud_progress"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_BOOK_KEY = "book_key"
    private const val KEY_BOOK_TITLE = "book_title"
    private const val KEY_CHAPTER_TITLE = "chapter_title"
    private const val KEY_CHAPTER_INDEX = "chapter_index"
    private const val KEY_PARAGRAPH_INDEX = "paragraph_index"
    private const val KEY_TEXT = "text"
    private const val KEY_HIGHLIGHT_TEXT = "highlight_text"
    private const val KEY_START_CFI = "start_cfi"
    private const val KEY_END_CFI = "end_cfi"
    private const val KEY_PLAYING = "playing"
    private const val KEY_ELAPSED_MS = "elapsed_ms"
    private const val MAX_TEXT_CHARS = 4_000

    fun save(
        context: Context,
        sessionId: String,
        bookKey: String,
        bookTitle: String,
        paragraph: ReadAloudParagraphSnapshot,
        index: Int,
        playing: Boolean,
        elapsedMs: Long,
    ) {
        if (sessionId.isBlank() || bookKey.isBlank() || paragraph.startCfi.isBlank()) return
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_BOOK_KEY, bookKey)
            .putString(KEY_BOOK_TITLE, bookTitle)
            .putString(KEY_CHAPTER_TITLE, paragraph.title)
            .putInt(KEY_CHAPTER_INDEX, paragraph.chapterIndex)
            .putInt(KEY_PARAGRAPH_INDEX, index)
            .putString(KEY_TEXT, paragraph.text.take(MAX_TEXT_CHARS))
            .putString(KEY_HIGHLIGHT_TEXT, paragraph.highlightText.take(MAX_TEXT_CHARS))
            .putString(KEY_START_CFI, paragraph.startCfi)
            .putString(KEY_END_CFI, paragraph.endCfi)
            .putBoolean(KEY_PLAYING, playing)
            .putLong(KEY_ELAPSED_MS, elapsedMs.coerceAtLeast(0L))
            .apply()
    }

    fun broadcast(context: Context): Boolean {
        val progress = read(context) ?: return false
        context.applicationContext.sendBroadcast(progress.toIntent())
        return true
    }

    fun read(context: Context): PersistedReadAloudProgress? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val startCfi = prefs.getString(KEY_START_CFI, null)?.takeIf { it.isNotBlank() } ?: return null
        val sessionId = prefs.getString(KEY_SESSION_ID, null).orEmpty()
        val bookKey = prefs.getString(KEY_BOOK_KEY, null).orEmpty()
        if (sessionId.isBlank() || bookKey.isBlank()) return null
        val text = prefs.getString(KEY_TEXT, null).orEmpty()
        if (text.isBlank()) return null
        return PersistedReadAloudProgress(
            sessionId = sessionId,
            bookKey = bookKey,
            bookTitle = prefs.getString(KEY_BOOK_TITLE, null).orEmpty(),
            chapterTitle = prefs.getString(KEY_CHAPTER_TITLE, null).orEmpty(),
            chapterIndex = prefs.getInt(KEY_CHAPTER_INDEX, 0),
            paragraphIndex = prefs.getInt(KEY_PARAGRAPH_INDEX, 0),
            text = text,
            highlightText = prefs.getString(KEY_HIGHLIGHT_TEXT, null).orEmpty().ifBlank { text },
            startCfi = startCfi,
            endCfi = prefs.getString(KEY_END_CFI, null).orEmpty().ifBlank { startCfi },
            playing = prefs.getBoolean(KEY_PLAYING, false),
            elapsedMs = prefs.getLong(KEY_ELAPSED_MS, 0L).coerceAtLeast(0L),
            timestamp = prefs.getLong(KEY_TIMESTAMP, 0L),
        )
    }

    data class ReadAloudParagraphSnapshot(
        val title: String,
        val text: String,
        val highlightText: String,
        val chapterIndex: Int,
        val startCfi: String,
        val endCfi: String,
    )

    data class PersistedReadAloudProgress(
        val sessionId: String,
        val bookKey: String,
        val bookTitle: String,
        val chapterTitle: String,
        val chapterIndex: Int,
        val paragraphIndex: Int,
        val text: String,
        val highlightText: String,
        val startCfi: String,
        val endCfi: String,
        val playing: Boolean,
        val elapsedMs: Long,
        val timestamp: Long,
    ) {
        fun toIntent(): Intent =
            Intent(ReadAloudIntents.ACTION_CURRENT).apply {
                setPackage(ReadAloudIntents.HOST_PACKAGE_NAME)
                putExtra(ReadAloudIntents.EXTRA_SESSION_ID, sessionId)
                putExtra(ReadAloudIntents.EXTRA_BOOK_KEY, bookKey)
                putExtra(ReadAloudIntents.EXTRA_BOOK_TITLE, bookTitle)
                putExtra(ReadAloudIntents.EXTRA_CHAPTER_TITLE, chapterTitle)
                putExtra(ReadAloudIntents.EXTRA_CHAPTER_INDEX, chapterIndex)
                putExtra(ReadAloudIntents.EXTRA_PARAGRAPH_INDEX, paragraphIndex)
                putExtra(ReadAloudIntents.EXTRA_TEXT, text)
                putExtra(ReadAloudIntents.EXTRA_HIGHLIGHT_TEXT, highlightText)
                putExtra(ReadAloudIntents.EXTRA_START_CFI, startCfi)
                putExtra(ReadAloudIntents.EXTRA_END_CFI, endCfi)
                putExtra(ReadAloudIntents.EXTRA_PLAYING, false)
                putExtra(ReadAloudIntents.EXTRA_PLAYBACK_ELAPSED_MS, elapsedMs)
                putExtra(ReadAloudIntents.EXTRA_RESTORED_PROGRESS, true)
            }
    }
}
