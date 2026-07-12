package com.reamicro.fix.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.Base64
import com.reamicro.fix.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

class ReadAloudService : Service() {
    private val paragraphs = ArrayList<ReadAloudParagraph>()
    private val stateLock = Object()
    private var currentSessionId = ""
    private var currentBookKey = ""
    private var currentBookTitle = ""
    private var currentCoverUri = ""
    private var currentCoverBitmap: Bitmap? = null
    private var totalChunks = 0
    private var loadedChunks = 0
    private var source: NetworkTtsSource? = null
    private val networkAudioLock = Object()
    private val networkAudioCache = LinkedHashMap<Int, ByteArray>()
    private val networkAudioInflight = HashMap<Int, CountDownLatch>()
    private val playbackTimeLock = Object()
    @Volatile private var currentIndex = 0
    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    @Volatile private var worker: Thread? = null
    @Volatile private var networkPrefetchThread: Thread? = null
    @Volatile private var networkPrefetchGeneration = 0L
    @Volatile private var playbackGeneration = 0L
    @Volatile private var workerGeneration = 0L
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var mediaPlayer: MediaPlayer? = null
    @Volatile private var verifiedProgressSessionId = ""
    @Volatile private var verifiedProgressIndex = -1
    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequested = false
    private var ignoreAudioFocus = false
    private var lyriconEnabled = false
    private var playbackElapsedMs = 0L
    private var playbackActiveSinceMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground(status = "\u51c6\u5907\u542c\u4e66")
        Log.i(LOG_TAG, "onStartCommand action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ReadAloudIntents.ACTION_PREPARE -> handlePrepare(intent)
            ReadAloudIntents.ACTION_APPEND -> handleAppend(intent)
            ReadAloudIntents.ACTION_PLAY -> handlePlay(intent)
            ReadAloudIntents.ACTION_PAUSE -> pause()
            ReadAloudIntents.ACTION_RESUME -> resume()
            ReadAloudIntents.ACTION_STOP -> stop()
            ReadAloudIntents.ACTION_PREV -> jumpChapter(-1)
            ReadAloudIntents.ACTION_NEXT -> jumpChapter(1)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRequested = true
        setPlaybackElapsedActive(false)
        persistCurrentProgress(playing = false)
        releasePlayer()
        releaseTts()
        abandonAudioFocus()
        releaseWakeLock()
        releaseMediaSession()
        LyriconCaptionBridge.destroy()
        super.onDestroy()
    }

    private fun handlePrepare(intent: Intent) {
        var initialParagraphs = 0
        val autoPlay = intent.getBooleanExtra(ReadAloudIntents.EXTRA_AUTO_PLAY, false)
        synchronized(stateLock) {
            currentSessionId = intent.getStringExtra(ReadAloudIntents.EXTRA_SESSION_ID).orEmpty()
            currentBookKey = intent.getStringExtra(ReadAloudIntents.EXTRA_BOOK_KEY).orEmpty()
            currentBookTitle = intent.getStringExtra(ReadAloudIntents.EXTRA_BOOK_TITLE).orEmpty()
            currentCoverUri = intent.getStringExtra(ReadAloudIntents.EXTRA_COVER_URI).orEmpty()
            currentCoverBitmap = loadCoverBitmap(currentCoverUri)
            totalChunks = intent.getIntExtra(ReadAloudIntents.EXTRA_TOTAL_CHUNKS, 0).coerceAtLeast(0)
            loadedChunks = 0
            paragraphs.clear()
            currentIndex = 0
            verifiedProgressSessionId = ""
            verifiedProgressIndex = -1
            playbackGeneration++
            paused = false
            stopRequested = false
            source = NetworkTtsSource.fromJson(intent.getStringExtra(ReadAloudIntents.EXTRA_SOURCE_JSON))
            ignoreAudioFocus = intent.getBooleanExtra(ReadAloudIntents.EXTRA_IGNORE_AUDIO_FOCUS, false)
            lyriconEnabled = intent.getBooleanExtra(ReadAloudIntents.EXTRA_LYRICON_ENABLED, false)
            initialParagraphs = appendParagraphsFromIntentLocked(intent)
            if (initialParagraphs > 0) {
                loadedChunks = 1
            }
        }
        resetPlaybackElapsed()
        clearNetworkAudioCache()
        tts?.stop()
        releasePlayer()
        if (!lyriconEnabled) LyriconCaptionBridge.destroy()
        sendClearHighlight(clearSession = false)
        ensureForeground(status = if (initialParagraphs > 0) "\u5f00\u59cb\u542c\u4e66" else "\u6b63\u5728\u8f7d\u5165\u7ae0\u8282")
        Log.i(
            LOG_TAG,
            "prepare session=$currentSessionId chunks=$totalChunks initial=$initialParagraphs " +
                "autoPlay=$autoPlay lyricon=$lyriconEnabled source=${source?.name.orEmpty()} " +
                "cover=${coverLogValue(currentCoverUri)}",
        )
        if (autoPlay && initialParagraphs > 0) {
            playPreparedSession()
        }
    }

    private fun handleAppend(intent: Intent) {
        val sessionId = intent.getStringExtra(ReadAloudIntents.EXTRA_SESSION_ID).orEmpty()
        if (sessionId.isBlank() || sessionId != currentSessionId) {
            Log.i(LOG_TAG, "append ignored session=$sessionId current=$currentSessionId")
            return
        }
        val incomingTotalChunks = intent.getIntExtra(ReadAloudIntents.EXTRA_TOTAL_CHUNKS, totalChunks)
        var added = 0
        synchronized(stateLock) {
            if (incomingTotalChunks > totalChunks) {
                totalChunks = incomingTotalChunks
            }
            added = appendParagraphsFromIntentLocked(intent)
            if (added > 0) loadedChunks++
        }
        ensureForeground(status = "\u5df2\u8f7d\u5165 $loadedChunks/$totalChunks")
        Log.i(
            LOG_TAG,
            "append session=$sessionId chunk=${intent.getIntExtra(ReadAloudIntents.EXTRA_CHUNK_INDEX, -1)} " +
                "added=$added total=${paragraphs.size}",
        )
        if (added > 0) {
            scheduleNetworkPrefetch(currentIndex + 1, playbackGeneration)
        }
    }

    private fun handlePlay(intent: Intent) {
        val sessionId = intent.getStringExtra(ReadAloudIntents.EXTRA_SESSION_ID).orEmpty()
        if (sessionId.isNotBlank() && sessionId != currentSessionId) {
            Log.i(LOG_TAG, "play ignored session=$sessionId current=$currentSessionId")
            return
        }
        playPreparedSession()
    }

    private fun playPreparedSession() {
        paused = false
        stopRequested = false
        if (ignoreAudioFocus) {
            abandonAudioFocus()
        } else {
            requestAudioFocus()
        }
        acquireWakeLock()
        Log.i(LOG_TAG, "play session=$currentSessionId loaded=$loadedChunks/$totalChunks paragraphs=${paragraphs.size}")
        scheduleNetworkPrefetch(currentIndex + 1, playbackGeneration)
        startWorkerIfNeeded()
    }

    private fun appendParagraphsFromIntentLocked(intent: Intent): Int {
        val texts = intent.getStringArrayListExtra(ReadAloudIntents.EXTRA_TEXTS).orEmpty()
        val highlightTexts = intent.getStringArrayListExtra(ReadAloudIntents.EXTRA_HIGHLIGHT_TEXTS).orEmpty()
        val titles = intent.getStringArrayListExtra(ReadAloudIntents.EXTRA_TITLES).orEmpty()
        val indices = intent.getIntegerArrayListExtra(ReadAloudIntents.EXTRA_CHAPTER_INDICES).orEmpty()
        val startCfis = intent.getStringArrayListExtra(ReadAloudIntents.EXTRA_START_CFIS).orEmpty()
        val endCfis = intent.getStringArrayListExtra(ReadAloudIntents.EXTRA_END_CFIS).orEmpty()
        var added = 0
        texts.forEachIndexed { index, text ->
            val clean = text.trim()
            if (clean.isBlank()) return@forEachIndexed
            paragraphs += ReadAloudParagraph(
                title = titles.getOrNull(index).orEmpty(),
                text = clean,
                highlightText = highlightTexts.getOrNull(index)?.trim().orEmpty().ifBlank { clean },
                chapterIndex = indices.getOrNull(index) ?: -1,
                startCfi = startCfis.getOrNull(index).orEmpty(),
                endCfi = endCfis.getOrNull(index).orEmpty(),
            )
            added++
        }
        return added
    }

    private fun startWorkerIfNeeded() {
        val generation = playbackGeneration
        val existing = worker
        if (existing != null && existing.isAlive && workerGeneration == generation) return
        workerGeneration = generation
        worker = Thread {
            runPlaybackLoop(generation)
        }.apply {
            name = "ReaMicroReadAloud"
            isDaemon = true
            start()
        }
    }

    private fun runPlaybackLoop(generation: Long) {
        ensureForeground(status = "\u5f00\u59cb\u542c\u4e66")
        var consecutiveFailures = 0
        var playbackFailed = false
        while (!stopRequested && generation == playbackGeneration) {
            waitIfPaused(generation)
            if (generation != playbackGeneration) break
            val paragraph = synchronized(stateLock) { paragraphs.getOrNull(currentIndex) }
            if (paragraph == null) {
                if (loadedChunks < totalChunks) {
                    Thread.sleep(150)
                    continue
                }
                break
            }
            acquireWakeLock()
            Log.i(LOG_TAG, "speak index=$currentIndex title=${paragraph.title} chars=${paragraph.text.length}")
            ensureForeground(status = paragraph.title.ifBlank { "\u6b63\u5728\u6717\u8bfb" })
            val networkSource = source
            if (networkSource != null) {
                scheduleNetworkPrefetch(currentIndex + 1, generation)
            }
            val completed = if (networkSource != null) {
                playNetwork(currentIndex, paragraph, networkSource, generation)
            } else {
                speakSystem(paragraph, currentIndex, generation)
            }
            if (completed && !paused && !stopRequested) {
                sendCurrentParagraph(paragraph, currentIndex, playing = true, recordProgress = true)
                currentIndex++
                consecutiveFailures = 0
            } else if (!paused && !stopRequested && generation == playbackGeneration) {
                consecutiveFailures++
                Log.i(LOG_TAG, "speak failed index=$currentIndex failures=$consecutiveFailures")
                if (consecutiveFailures >= MAX_SPEAK_FAILURES) {
                    playbackFailed = true
                    ensureForeground(
                        status = if (networkSource != null) "\u5728\u7ebf TTS \u6717\u8bfb\u5931\u8d25" else "\u7cfb\u7edf TTS \u6717\u8bfb\u5931\u8d25",
                        done = true,
                    )
                    syncLyriconPlaybackState(false)
                    sendCurrentParagraph(paragraph, currentIndex, playing = false, recordProgress = false)
                    break
                }
                Thread.sleep(SPEAK_FAILURE_RETRY_DELAY_MS)
            }
        }
        if (playbackFailed && generation == playbackGeneration) {
            setPlaybackElapsedActive(false)
            releaseWakeLock()
            clearLyriconCaption()
            sendClearHighlight(clearSession = true)
            clearPlaybackSession()
            stopForegroundCompat(removeNotification = false)
            stopSelf()
        } else if (!stopRequested && generation == playbackGeneration) {
            setPlaybackElapsedActive(false)
            ensureForeground(status = "\u542c\u4e66\u5b8c\u6210", done = true)
            releaseWakeLock()
            clearLyriconCaption()
            sendClearHighlight(clearSession = true)
            clearPlaybackSession()
            stopForegroundCompat(removeNotification = false)
            stopSelf()
        }
    }

    private fun waitIfPaused(generation: Long) {
        while (paused && !stopRequested && generation == playbackGeneration) {
            Thread.sleep(150)
        }
    }

    private fun pause() {
        paused = true
        setPlaybackElapsedActive(false)
        tts?.stop()
        releasePlayer()
        cancelNetworkPrefetch()
        releaseWakeLock()
        syncLyriconPlaybackState(false)
        sendCurrentParagraph(
            synchronized(stateLock) { paragraphs.getOrNull(currentIndex) },
            currentIndex,
            playing = false,
            recordProgress = true,
        )
        ensureForeground(status = "\u5df2\u6682\u505c")
    }

    private fun resume() {
        if (!paused) return
        paused = false
        acquireWakeLock()
        syncLyriconPlaybackState(true)
        ensureForeground(status = "\u7ee7\u7eed\u542c\u4e66")
        startWorkerIfNeeded()
    }

    private fun stop() {
        stopRequested = true
        paused = false
        setPlaybackElapsedActive(false)
        sendCurrentParagraph(
            synchronized(stateLock) { paragraphs.getOrNull(currentIndex) },
            currentIndex,
            playing = false,
            recordProgress = true,
        )
        ReadAloudProgressStore.clear(applicationContext)
        tts?.stop()
        releasePlayer()
        cancelNetworkPrefetch()
        clearNetworkAudioCache()
        abandonAudioFocus()
        releaseWakeLock()
        clearLyriconCaption()
        sendClearHighlight(clearSession = true)
        clearPlaybackSession()
        stopForegroundCompat(removeNotification = true)
        stopSelf()
    }

    private fun jumpBy(delta: Int) {
        synchronized(stateLock) {
            currentIndex = (currentIndex + delta).coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
            verifiedProgressIndex = -1
            playbackGeneration++
        }
        cancelNetworkPrefetch()
        clearNetworkAudioCache()
        tts?.stop()
        releasePlayer()
        paused = false
        stopRequested = false
        acquireWakeLock()
        startWorkerIfNeeded()
        ensureForeground(status = "\u6b63\u5728\u5207\u6362")
    }

    private fun jumpChapter(delta: Int) {
        val targetIndex = synchronized(stateLock) {
            if (paragraphs.isEmpty()) return
            val current = currentIndex.coerceIn(0, paragraphs.lastIndex)
            val currentKey = paragraphChapterKey(paragraphs[current])
            if (delta > 0) {
                (current + 1..paragraphs.lastIndex)
                    .firstOrNull { paragraphChapterKey(paragraphs[it]) != currentKey }
                    ?: current
            } else {
                val previousLast = (current - 1 downTo 0)
                    .firstOrNull { paragraphChapterKey(paragraphs[it]) != currentKey }
                if (previousLast == null) {
                    0
                } else {
                    val previousKey = paragraphChapterKey(paragraphs[previousLast])
                    paragraphs.indexOfFirst { paragraphChapterKey(it) == previousKey }.takeIf { it >= 0 } ?: previousLast
                }
            }
        }
        synchronized(stateLock) {
            currentIndex = targetIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
            verifiedProgressIndex = -1
            playbackGeneration++
        }
        cancelNetworkPrefetch()
        clearNetworkAudioCache()
        tts?.stop()
        releasePlayer()
        paused = false
        stopRequested = false
        acquireWakeLock()
        startWorkerIfNeeded()
        ensureForeground(status = synchronized(stateLock) {
            paragraphs.getOrNull(currentIndex)?.title?.ifBlank { "\u6b63\u5728\u5207\u6362\u7ae0\u8282" }
                ?: "\u6b63\u5728\u5207\u6362\u7ae0\u8282"
        })
    }

    private fun paragraphChapterKey(paragraph: ReadAloudParagraph): String =
        if (paragraph.chapterIndex >= 0) {
            "i:${paragraph.chapterIndex}"
        } else {
            "t:${paragraph.title}"
        }

    private fun sendCurrentParagraph(
        paragraph: ReadAloudParagraph?,
        index: Int,
        playing: Boolean,
        recordProgress: Boolean = false,
    ) {
        paragraph ?: return
        val elapsedMs = currentPlaybackElapsedMs()
        val playbackStarted = isVerifiedPlaybackProgress(index)
        val progressRecordable = playbackStarted && recordProgress
        if (progressRecordable) {
            persistProgress(paragraph, index, playing, elapsedMs)
        }
        val intent = Intent(ReadAloudIntents.ACTION_CURRENT).apply {
            setPackage(ReadAloudIntents.HOST_PACKAGE_NAME)
            putExtra(ReadAloudIntents.EXTRA_SESSION_ID, currentSessionId)
            putExtra(ReadAloudIntents.EXTRA_BOOK_KEY, currentBookKey)
            putExtra(ReadAloudIntents.EXTRA_BOOK_TITLE, currentBookTitle)
            putExtra(ReadAloudIntents.EXTRA_CHAPTER_TITLE, paragraph.title)
            putExtra(ReadAloudIntents.EXTRA_CHAPTER_INDEX, paragraph.chapterIndex)
            putExtra(ReadAloudIntents.EXTRA_PARAGRAPH_INDEX, index)
            putExtra(ReadAloudIntents.EXTRA_TEXT, paragraph.text)
            putExtra(ReadAloudIntents.EXTRA_HIGHLIGHT_TEXT, paragraph.highlightText)
            putExtra(ReadAloudIntents.EXTRA_START_CFI, paragraph.startCfi)
            putExtra(ReadAloudIntents.EXTRA_END_CFI, paragraph.endCfi)
            putExtra(ReadAloudIntents.EXTRA_PLAYING, playing)
            putExtra(ReadAloudIntents.EXTRA_PLAYBACK_STARTED, playbackStarted)
            putExtra(ReadAloudIntents.EXTRA_PROGRESS_RECORDABLE, progressRecordable)
            putExtra(ReadAloudIntents.EXTRA_PLAYBACK_ELAPSED_MS, elapsedMs)
        }
        sendBroadcast(intent)
    }

    private fun persistCurrentProgress(playing: Boolean) {
        val index = currentIndex
        val paragraph = synchronized(stateLock) { paragraphs.getOrNull(index) } ?: return
        if (!isVerifiedPlaybackProgress(index)) return
        persistProgress(paragraph, index, playing, currentPlaybackElapsedMs())
    }

    private fun markPlaybackStarted(paragraph: ReadAloudParagraph?, index: Int) {
        paragraph ?: return
        verifiedProgressSessionId = currentSessionId
        verifiedProgressIndex = index
        setPlaybackElapsedActive(true)
        sendCurrentParagraph(paragraph, index, playing = true, recordProgress = false)
    }

    private fun isVerifiedPlaybackProgress(index: Int): Boolean =
        currentSessionId.isNotBlank() &&
            verifiedProgressSessionId == currentSessionId &&
            verifiedProgressIndex == index

    private fun persistProgress(paragraph: ReadAloudParagraph, index: Int, playing: Boolean, elapsedMs: Long) {
        ReadAloudProgressStore.save(
            context = applicationContext,
            sessionId = currentSessionId,
            bookKey = currentBookKey,
            bookTitle = currentBookTitle,
            paragraph = ReadAloudProgressStore.ReadAloudParagraphSnapshot(
                title = paragraph.title,
                text = paragraph.text,
                highlightText = paragraph.highlightText,
                chapterIndex = paragraph.chapterIndex,
                startCfi = paragraph.startCfi,
                endCfi = paragraph.endCfi,
            ),
            index = index,
            playing = playing,
            elapsedMs = elapsedMs,
        )
    }

    private fun sendClearHighlight(clearSession: Boolean) {
        val intent = Intent(ReadAloudIntents.ACTION_CLEAR).apply {
            setPackage(ReadAloudIntents.HOST_PACKAGE_NAME)
            putExtra(ReadAloudIntents.EXTRA_SESSION_ID, currentSessionId)
            putExtra(ReadAloudIntents.EXTRA_BOOK_KEY, currentBookKey)
            putExtra(ReadAloudIntents.EXTRA_CLEAR_SESSION, clearSession)
        }
        sendBroadcast(intent)
    }

    private fun sendLyriconText(text: String, playing: Boolean) {
        if (!lyriconEnabled) return
        Log.i(LOG_TAG, "lyricon send chars=${text.length} playing=$playing")
        LyriconCaptionBridge.sendText(applicationContext, text, playing)
    }

    private fun syncLyriconPlaybackState(playing: Boolean) {
        if (!lyriconEnabled) return
        LyriconCaptionBridge.syncPlaybackState(applicationContext, playing)
    }

    private fun clearLyriconCaption() {
        if (!lyriconEnabled) return
        LyriconCaptionBridge.clear(applicationContext)
    }

    private fun clearPlaybackSession() {
        synchronized(stateLock) {
            currentSessionId = ""
            currentBookKey = ""
            currentBookTitle = ""
            currentCoverUri = ""
            currentCoverBitmap = null
            totalChunks = 0
            loadedChunks = 0
            paragraphs.clear()
            currentIndex = 0
            verifiedProgressSessionId = ""
            verifiedProgressIndex = -1
            playbackGeneration++
            source = null
            lyriconEnabled = false
        }
        resetPlaybackElapsed()
        cancelNetworkPrefetch()
        clearNetworkAudioCache()
    }

    private fun resetPlaybackElapsed() {
        synchronized(playbackTimeLock) {
            playbackElapsedMs = 0L
            playbackActiveSinceMs = 0L
        }
    }

    private fun setPlaybackElapsedActive(active: Boolean) {
        synchronized(playbackTimeLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            val activeSince = playbackActiveSinceMs
            if (activeSince > 0L) {
                playbackElapsedMs += (now - activeSince).coerceAtLeast(0L)
                playbackActiveSinceMs = 0L
            }
            if (active) playbackActiveSinceMs = now
        }
    }

    private fun currentPlaybackElapsedMs(): Long =
        synchronized(playbackTimeLock) {
            val activeSince = playbackActiveSinceMs
            playbackElapsedMs + if (activeSince > 0L) {
                (android.os.SystemClock.elapsedRealtime() - activeSince).coerceAtLeast(0L)
            } else {
                0L
            }
        }

    private fun speakSystem(paragraph: ReadAloudParagraph, index: Int, generation: Long): Boolean {
        if (generation != playbackGeneration) return false
        val engine = ensureTts() ?: return false
        if (!ttsReady) return false
        val text = paragraph.text
        val utteranceId = "reamicro_${System.nanoTime()}"
        val latch = CountDownLatch(1)
        var utteranceFailed = false
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                markPlaybackStarted(paragraph, index)
                sendLyriconText(text, true)
            }

            override fun onDone(utteranceId: String?) {
                setPlaybackElapsedActive(false)
                latch.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceFailed = true
                setPlaybackElapsedActive(false)
                Log.i(LOG_TAG, "system tts onError utterance=$utteranceId")
                latch.countDown()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceFailed = true
                setPlaybackElapsedActive(false)
                Log.i(LOG_TAG, "system tts onError utterance=$utteranceId code=$errorCode")
                latch.countDown()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceFailed = interrupted
                setPlaybackElapsedActive(false)
                latch.countDown()
            }
        })
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
        } else {
            @Suppress("DEPRECATION")
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, HashMap<String, String>().apply {
                put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            })
        }
        Log.i(LOG_TAG, "system tts speak result=$result chars=${text.length}")
        if (result == TextToSpeech.ERROR) return false
        while (!stopRequested && !paused && generation == playbackGeneration) {
            if (latch.await(200, TimeUnit.MILLISECONDS)) return !utteranceFailed
        }
        return false
    }

    private fun ensureTts(): TextToSpeech? {
        tts?.let { existing ->
            if (ttsReady) return existing
            releaseTts()
        }
        val readyLatch = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        val created = TextToSpeech(applicationContext) { status ->
            initStatus = status
            Log.i(LOG_TAG, "tts init callback status=$status")
            readyLatch.countDown()
        }
        val callbackArrived = readyLatch.await(5, TimeUnit.SECONDS)
        if (callbackArrived && initStatus == TextToSpeech.SUCCESS) {
            tts = created
            ttsReady = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                created.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
            val languageResult = created.setLanguage(Locale.CHINESE)
            if (
                languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                created.setLanguage(Locale.getDefault())
            }
            created.setSpeechRate(1.0f)
            Log.i(LOG_TAG, "tts ready languageResult=$languageResult default=${Locale.getDefault()}")
        } else {
            Log.i(LOG_TAG, "tts init timeout or failed callback=$callbackArrived status=$initStatus")
            runCatching { created.shutdown() }
            tts = null
            ttsReady = false
        }
        return created.takeIf { ttsReady }
    }

    private fun playNetwork(index: Int, paragraph: ReadAloudParagraph, source: NetworkTtsSource, generation: Long): Boolean {
        val text = paragraph.text
        val audio = getNetworkAudio(index, text, source, generation, waitForInflight = true, prefetch = false)
            ?: return false
        if (generation != playbackGeneration) return false
        if (audio.isEmpty()) return false
        val file = File(cacheDir, "read_aloud_audio.tmp")
        runCatching { file.writeBytes(audio) }.getOrElse { return false }
        val latch = CountDownLatch(1)
        var playerFailed = false
        return runCatching {
            val player = MediaPlayer().apply {
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                }
                setDataSource(file.absolutePath)
                setOnCompletionListener { latch.countDown() }
                setOnErrorListener { _, _, _ ->
                    playerFailed = true
                    latch.countDown()
                    true
                }
                prepare()
            }
            mediaPlayer = player
            sendLyriconText(text, true)
            player.start()
            markPlaybackStarted(paragraph, index)
            while (!stopRequested && !paused && generation == playbackGeneration) {
                if (latch.await(200, TimeUnit.MILLISECONDS)) return@runCatching !playerFailed
            }
            false
        }.getOrDefault(false).also {
            setPlaybackElapsedActive(false)
            releasePlayer()
        }
    }

    private fun scheduleNetworkPrefetch(startIndex: Int, generation: Long) {
        val networkSource = source ?: return
        if (generation != playbackGeneration || stopRequested) return
        val existing = networkPrefetchThread
        if (existing != null && existing.isAlive && networkPrefetchGeneration == generation) return
        networkPrefetchGeneration = generation
        networkPrefetchThread = Thread {
            runNetworkPrefetch(startIndex.coerceAtLeast(0), networkSource, generation)
        }.apply {
            name = "ReaMicroReadAloudPrefetch"
            isDaemon = true
            start()
        }
    }

    private fun runNetworkPrefetch(startIndex: Int, networkSource: NetworkTtsSource, generation: Long) {
        var index = startIndex
        val maxIndex = startIndex + NETWORK_TTS_PREFETCH_WINDOW
        while (!stopRequested && !paused && generation == playbackGeneration && index <= maxIndex) {
            val paragraph = synchronized(stateLock) { paragraphs.getOrNull(index) } ?: break
            if (!hasNetworkAudioOrInflight(index)) {
                getNetworkAudio(index, paragraph.text, networkSource, generation, waitForInflight = false, prefetch = true)
            }
            index++
        }
    }

    private fun getNetworkAudio(
        index: Int,
        text: String,
        networkSource: NetworkTtsSource,
        generation: Long,
        waitForInflight: Boolean,
        prefetch: Boolean,
    ): ByteArray? {
        var ownsFetch = false
        val latch: CountDownLatch
        synchronized(networkAudioLock) {
            networkAudioCache[index]?.let { cached ->
                if (!prefetch) Log.i(LOG_TAG, "network tts cache hit index=$index bytes=${cached.size}")
                return cached
            }
            val existing = networkAudioInflight[index]
            if (existing != null) {
                if (!waitForInflight) return null
                latch = existing
            } else {
                latch = CountDownLatch(1)
                networkAudioInflight[index] = latch
                ownsFetch = true
            }
        }
        if (!ownsFetch) {
            while (!stopRequested && generation == playbackGeneration) {
                if (latch.await(NETWORK_TTS_INFLIGHT_WAIT_MS, TimeUnit.MILLISECONDS)) break
            }
            return synchronized(networkAudioLock) { networkAudioCache[index] }
        }

        val audio = runCatching { networkSource.fetchAudio(text) }
            .onFailure {
                Log.i(LOG_TAG, if (prefetch) "network tts prefetch failed index=$index" else "network tts failed index=$index", it)
            }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
        synchronized(networkAudioLock) {
            networkAudioInflight.remove(index)
            if (audio != null && generation == playbackGeneration && !stopRequested) {
                networkAudioCache[index] = audio
                trimNetworkAudioCacheLocked()
                Log.i(LOG_TAG, if (prefetch) "network tts prefetched index=$index bytes=${audio.size}" else "network tts cached index=$index bytes=${audio.size}")
            }
            latch.countDown()
        }
        return audio
    }

    private fun hasNetworkAudioOrInflight(index: Int): Boolean =
        synchronized(networkAudioLock) {
            networkAudioCache.containsKey(index) || networkAudioInflight.containsKey(index)
        }

    private fun trimNetworkAudioCacheLocked() {
        val keepFrom = (currentIndex - 1).coerceAtLeast(0)
        val iterator = networkAudioCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key < keepFrom || networkAudioCache.size > NETWORK_TTS_CACHE_MAX_ITEMS) {
                iterator.remove()
            }
        }
    }

    private fun cancelNetworkPrefetch() {
        networkPrefetchThread?.interrupt()
        networkPrefetchThread = null
        networkPrefetchGeneration = 0L
    }

    private fun clearNetworkAudioCache() {
        synchronized(networkAudioLock) {
            networkAudioCache.clear()
            networkAudioInflight.values.forEach { it.countDown() }
            networkAudioInflight.clear()
        }
    }

    private fun ensureForeground(status: String, done: Boolean = false) {
        ensureChannel()
        val notification = buildNotification(status, done)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(status: String, done: Boolean): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val bookTitle = currentBookTitle.ifBlank { "\u9605\u5fae" }
        val current = synchronized(stateLock) { paragraphs.getOrNull(currentIndex) }
        val chapterTitle = current?.title?.ifBlank { "\u6b63\u5728\u6717\u8bfb" } ?: status
        val progress = if (paragraphs.isNotEmpty()) "${(currentIndex + 1).coerceAtMost(paragraphs.size)}/${paragraphs.size}" else status
        updateMediaSession(chapterTitle, progress, done)
        builder
            .setSmallIcon(R.drawable.ic_notification_reamicro)
            .setContentTitle(chapterTitle)
            .setContentText(bookTitle)
            .setSubText(progress)
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setAutoCancel(done)
            .setContentIntent(openHostPendingIntent())
            .setShowWhen(false)
        currentCoverBitmap?.let { builder.setLargeIcon(it) }
        if (!done) {
            builder.addAction(android.R.drawable.ic_media_previous, "\u4e0a\u4e00\u7ae0", servicePendingIntent(ReadAloudIntents.ACTION_PREV, 1))
            builder.addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "\u7ee7\u7eed" else "\u6682\u505c",
                servicePendingIntent(if (paused) ReadAloudIntents.ACTION_RESUME else ReadAloudIntents.ACTION_PAUSE, 2),
            )
            builder.addAction(android.R.drawable.ic_media_next, "\u4e0b\u4e00\u7ae0", servicePendingIntent(ReadAloudIntents.ACTION_NEXT, 3))
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "\u505c\u6b62", servicePendingIntent(ReadAloudIntents.ACTION_STOP, 4))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ensureMediaSession()?.sessionToken?.let { token ->
                    builder.setStyle(
                        Notification.MediaStyle()
                            .setMediaSession(token)
                            .setShowActionsInCompactView(0, 1, 2),
                    )
                }
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            builder.setStyle(Notification.BigTextStyle().bigText(status))
        }
        return builder.build()
    }

    private fun ensureMediaSession(): MediaSession? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        mediaSession?.let { return it }
        return MediaSession(this, "ReaMicroReadAloud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    resume()
                }

                override fun onPause() {
                    pause()
                }

                override fun onStop() {
                    stop()
                }

                override fun onSkipToPrevious() {
                    jumpChapter(-1)
                }

                override fun onSkipToNext() {
                    jumpChapter(1)
                }
            })
            isActive = true
            mediaSession = this
        }
    }

    private fun updateMediaSession(chapterTitle: String, progress: String, done: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val session = ensureMediaSession() ?: return
        val bookTitle = currentBookTitle.ifBlank { "\u9605\u5fae" }
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, chapterTitle)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, bookTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, bookTitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, chapterTitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, bookTitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, progress)
            .apply {
                currentCoverBitmap?.let { cover ->
                    putBitmap(MediaMetadata.METADATA_KEY_ART, cover)
                    putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover)
                    putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, cover)
                }
            }
            .build()
        session.setMetadata(metadata)
        val playPauseAction = if (paused) PlaybackState.ACTION_PLAY else PlaybackState.ACTION_PAUSE
        val actions = PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_STOP or
            playPauseAction
        val state = when {
            done -> PlaybackState.STATE_STOPPED
            paused -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, currentIndex.toLong(), if (paused || done) 0f else 1f)
                .build(),
        )
    }

    private fun releaseMediaSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        runCatching {
            mediaSession?.isActive = false
            mediaSession?.release()
        }
        mediaSession = null
    }

    private fun openHostPendingIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(HOST_PACKAGE)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: return null
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).apply {
            setClass(this@ReadAloudService, ReadAloudService::class.java)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, requestCode, intent, flags)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "\u542c\u4e66", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    private fun acquireWakeLock() {
        val existing = wakeLock
        if (existing?.isHeld == true) return
        val lock = existing ?: ((getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReaMicro:ReadAloud")
            ?.also {
                it.setReferenceCounted(false)
                wakeLock = it
            } ?: return)
        runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
    }

    private fun loadCoverBitmap(value: String): Bitmap? =
        runCatching {
            val raw = value.trim()
            if (raw.isBlank()) return@runCatching null
            when {
                raw.startsWith("data:", ignoreCase = true) -> {
                    val comma = raw.indexOf(',')
                    if (comma <= 0) return@runCatching null
                    decodeCoverBytes(Base64.decode(raw.substring(comma + 1), Base64.DEFAULT))
                }
                raw.startsWith("content://", ignoreCase = true) -> {
                    contentResolver.openInputStream(Uri.parse(raw))?.use { input ->
                        decodeCoverBytes(input.readBytes())
                    }
                }
                raw.startsWith("file://", ignoreCase = true) -> {
                    decodeCoverFile(File(Uri.parse(raw).path.orEmpty()))
                }
                raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> {
                    decodeNetworkCover(raw)
                }
                else -> decodeCoverFile(File(raw))
            }
        }.onFailure {
            Log.i(LOG_TAG, "cover decode failed", it)
        }.getOrNull()

    private fun coverLogValue(value: String): String =
        when {
            value.isBlank() -> "blank"
            value.startsWith("data:", ignoreCase = true) -> "data:${value.length}"
            value.startsWith("content://", ignoreCase = true) -> "content"
            value.startsWith("file://", ignoreCase = true) -> "file"
            value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) -> "network"
            else -> "path"
        }

    private fun decodeNetworkCover(value: String): Bitmap? {
        val connection = (URL(value).openConnection() as? HttpURLConnection)?.apply {
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "ReaMicro-Extend/read-aloud")
            setRequestProperty("Accept", "image/*,*/*")
        } ?: return null
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            connection.inputStream.use { input -> decodeCoverBytes(input.readBytes()) }
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeCoverFile(file: File): Bitmap? {
        if (!file.isFile) return null
        return BitmapFactory.Options().run {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
            inSampleSize = coverSampleSize(outWidth, outHeight)
            inJustDecodeBounds = false
            BitmapFactory.decodeFile(file.absolutePath, this)
        }
    }

    private fun decodeCoverBytes(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        return BitmapFactory.Options().run {
            inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
            inSampleSize = coverSampleSize(outWidth, outHeight)
            inJustDecodeBounds = false
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
        }
    }

    private fun coverSampleSize(width: Int, height: Int): Int {
        var sample = 1
        var maxEdge = maxOf(width, height)
        while (maxEdge / sample > COVER_MAX_EDGE_PX) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun requestAudioFocus() {
        if (audioFocusRequested) return
        val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        @Suppress("DEPRECATION")
        audioFocusRequested = manager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (!audioFocusRequested) return
        val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        @Suppress("DEPRECATION")
        manager.abandonAudioFocus(null)
        audioFocusRequested = false
    }

    private fun releasePlayer() {
        runCatching {
            mediaPlayer?.setOnCompletionListener(null)
            mediaPlayer?.setOnErrorListener(null)
            mediaPlayer?.stop()
        }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    private fun releaseTts() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    private data class ReadAloudParagraph(
        val title: String,
        val text: String,
        val highlightText: String,
        val chapterIndex: Int,
        val startCfi: String,
        val endCfi: String,
    )

    private data class NetworkTtsSource(
        val name: String,
        val url: String,
        val contentType: String,
        val header: String,
    ) {
        fun fetchAudio(text: String): ByteArray {
            val sourceName = cleanString(name).ifBlank { "TTS" }
            val acceptType = cleanString(contentType).ifBlank { "audio/*,*/*" }
            val request = NetworkTtsRequest.from(cleanString(url), cleanString(header), text)
            if (request.url.isBlank()) error("TTS URL is blank")
            Log.i(LOG_TAG, "network tts request source=$sourceName method=${request.method} url=${safeUrlForLog(request.url)}")
            return fetchAudio(request, sourceName, acceptType, 0)
        }

        private fun fetchAudio(
            request: NetworkTtsRequest,
            sourceName: String,
            acceptType: String,
            redirectCount: Int,
        ): ByteArray {
            if (redirectCount > MAX_TTS_REDIRECTS) error("too many TTS redirects")
            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = request.method.ifBlank { "GET" }
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "ReaMicro-Extend/read-aloud")
                setRequestProperty("Accept", acceptType)
                request.headers.forEach { (key, value) ->
                    val cleanKey = cleanString(key)
                    val cleanValue = cleanString(value)
                    if (cleanKey.isNotBlank() && cleanValue.isNotBlank()) {
                        setRequestProperty(cleanKey, cleanValue)
                    }
                }
                if (request.body != null) {
                    doOutput = true
                    if (getRequestProperty("Content-Type").isNullOrBlank()) {
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    }
                    outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
                }
            }
            return try {
                val code = connection.responseCode
                val responseType = connection.contentType.orEmpty()
                val redirectUrl = redirectUrl(request.url, connection, code)
                if (redirectUrl.isNotBlank()) {
                    Log.i(LOG_TAG, "network tts redirect source=$sourceName code=$code url=${safeUrlForLog(redirectUrl)}")
                    return fetchAudio(request.redirectTo(redirectUrl, code), sourceName, acceptType, redirectCount + 1)
                }
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                Log.i(LOG_TAG, "network tts response source=$sourceName code=$code type=$responseType bytes=${bytes.size}")
                if (code !in 200..299) error("HTTP $code")
                resolveAudioResponse(bytes, responseType, request.url)
            } finally {
                connection.disconnect()
            }
        }

        private fun resolveAudioResponse(bytes: ByteArray, responseType: String, requestUrl: String): ByteArray {
            if (bytes.isEmpty()) return bytes
            val type = responseType.lowercase(Locale.ROOT)
            if (type.startsWith("audio/") || type.contains("octet-stream") || looksLikeAudioBytes(bytes)) {
                return bytes
            }
            val text = runCatching { bytes.toString(Charsets.UTF_8).trim() }.getOrDefault("")
            audioBytesFromDataUrl(text)?.let { return it }
            val audioUrl = when {
                text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> text
                text.startsWith("{") -> extractAudioUrl(JSONObject(text))
                text.startsWith("[") -> extractAudioUrl(JSONArray(text))
                else -> Regex("""https?://[^\s"'<>]+""").find(text)?.value
            }?.trim().orEmpty()
            if (audioUrl.isBlank()) return bytes
            val resolved = URL(URL(requestUrl), audioUrl).toString()
            Log.i(LOG_TAG, "network tts resolved audio url source=${cleanString(name).ifBlank { "TTS" }} url=${safeUrlForLog(resolved)}")
            return downloadAudio(resolved)
        }

        private fun downloadAudio(rawUrl: String): ByteArray {
            val acceptType = cleanString(contentType).ifBlank { "audio/*,*/*" }
            return downloadAudio(rawUrl, acceptType, 0)
        }

        private fun downloadAudio(rawUrl: String, acceptType: String, redirectCount: Int): ByteArray {
            if (redirectCount > MAX_TTS_REDIRECTS) error("too many TTS audio redirects")
            val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "ReaMicro-Extend/read-aloud")
                setRequestProperty("Accept", acceptType)
            }
            return try {
                val code = connection.responseCode
                val responseType = connection.contentType.orEmpty()
                val redirectUrl = redirectUrl(rawUrl, connection, code)
                if (redirectUrl.isNotBlank()) {
                    Log.i(LOG_TAG, "network tts audio redirect source=${cleanString(name).ifBlank { "TTS" }} code=$code url=${safeUrlForLog(redirectUrl)}")
                    return downloadAudio(redirectUrl, acceptType, redirectCount + 1)
                }
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                Log.i(LOG_TAG, "network tts audio response source=${cleanString(name).ifBlank { "TTS" }} code=$code type=$responseType bytes=${bytes.size}")
                if (code !in 200..299) error("audio HTTP $code")
                bytes
            } finally {
                connection.disconnect()
            }
        }

        private fun extractAudioUrl(value: Any?): String? {
            return when (value) {
                is JSONObject -> {
                    AUDIO_URL_KEYS.asSequence()
                        .mapNotNull { key -> cleanJsonString(value, key).takeIf { it.isAudioReference() } }
                        .firstOrNull()
                        ?: value.keys().asSequence()
                            .mapNotNull { key -> extractAudioUrl(value.opt(key)) }
                            .firstOrNull()
                }
                is JSONArray -> (0 until value.length())
                    .asSequence()
                    .mapNotNull { index -> extractAudioUrl(value.opt(index)) }
                    .firstOrNull()
                is String -> value.takeIf { it.isAudioReference() }
                    ?: Regex("""https?://[^\s"'<>]+""").find(value)?.value
                else -> null
            }
        }

        private fun audioBytesFromDataUrl(value: String): ByteArray? {
            if (!value.startsWith("data:audio/", ignoreCase = true)) return null
            val comma = value.indexOf(',')
            if (comma < 0) return null
            val meta = value.substring(0, comma)
            val payload = value.substring(comma + 1)
            return if (meta.contains(";base64", ignoreCase = true)) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            }
        }

        private fun String.isAudioReference(): Boolean =
            startsWith("http://", ignoreCase = true) ||
                startsWith("https://", ignoreCase = true) ||
                startsWith("data:audio/", ignoreCase = true)

        private fun looksLikeAudioBytes(bytes: ByteArray): Boolean =
            bytes.size >= 4 && (
                bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte() ||
                    bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() ||
                    bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() && bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte() ||
                    bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() && bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte() ||
                    bytes[0].toInt().and(0xFF) == 0xFF && bytes[1].toInt().and(0xE0) == 0xE0 ||
                    bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
                    bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
                )

        private fun safeUrlForLog(value: String): String =
            runCatching {
                val parsed = URL(value)
                val path = parsed.path.orEmpty().take(96)
                "${parsed.protocol}://${parsed.host}$path"
            }.getOrDefault(value.take(120))

        private fun redirectUrl(currentUrl: String, connection: HttpURLConnection, code: Int): String =
            if (code in 300..399) {
                val location = connection.getHeaderField("Location").orEmpty().trim()
                if (location.isBlank()) "" else URL(URL(currentUrl), location).toString()
            } else {
                ""
            }

        companion object {
            private val AUDIO_URL_KEYS = listOf(
                "audio",
                "audioUrl",
                "audioURL",
                "url",
                "src",
                "source",
                "media",
                "data",
                "file",
                "path",
            )

            fun fromJson(value: String?): NetworkTtsSource? = runCatching {
                if (value.isNullOrBlank()) return@runCatching null
                val json = JSONObject(value)
                val url = cleanJsonString(json, "url")
                if (url.isBlank()) return@runCatching null
                NetworkTtsSource(
                    name = cleanJsonString(json, "name"),
                    url = url,
                    contentType = cleanJsonString(json, "contentType"),
                    header = cleanJsonString(json, "header"),
                )
            }.getOrNull()
        }
    }

    private data class NetworkTtsRequest(
        val url: String,
        val method: String,
        val body: String?,
        val headers: Map<String, String>,
    ) {
        fun redirectTo(location: String, code: Int): NetworkTtsRequest {
            val redirectedMethod = when {
                code == HttpURLConnection.HTTP_SEE_OTHER -> "GET"
                (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP) &&
                    method != "GET" && method != "HEAD" -> "GET"
                else -> method
            }
            return copy(
                url = location,
                method = redirectedMethod,
                body = if (redirectedMethod == method) body else null,
            )
        }

        companion object {
            fun from(rawUrl: String?, rawHeader: String?, text: String): NetworkTtsRequest {
                val (urlPart, optionJson) = splitUrlOption(rawUrl)
                val headers = parseHeaders(rawHeader).toMutableMap()
                var method = "GET"
                var body: String? = null
                if (optionJson.isNotBlank()) {
                    runCatching {
                        val json = JSONObject(optionJson)
                        method = cleanJsonString(json, "method").ifBlank { method }.uppercase(Locale.ROOT)
                        body = cleanJsonString(json, "body").takeIf { it.isNotBlank() }?.let { applyTemplate(it, text) }
                        json.optJSONObject("headers")?.let { headerJson ->
                            headerJson.keys().forEach { key ->
                                val cleanKey = cleanString(key)
                                val cleanValue = applyTemplate(cleanJsonString(headerJson, cleanKey), text)
                                if (cleanKey.isNotBlank() && cleanValue.isNotBlank()) {
                                    headers[cleanKey] = cleanValue
                                }
                            }
                        }
                        json.optJSONObject("header")?.let { headerJson ->
                            headerJson.keys().forEach { key ->
                                val cleanKey = cleanString(key)
                                val cleanValue = applyTemplate(cleanJsonString(headerJson, cleanKey), text)
                                if (cleanKey.isNotBlank() && cleanValue.isNotBlank()) {
                                    headers[cleanKey] = cleanValue
                                }
                            }
                        }
                    }
                }
                return NetworkTtsRequest(
                    url = applyTemplate(urlPart, text),
                    method = method,
                    body = body,
                    headers = headers.mapNotNull { (key, value) ->
                        val cleanKey = cleanString(key)
                        val cleanValue = applyTemplate(cleanString(value), text)
                        if (cleanKey.isBlank() || cleanValue.isBlank()) null else cleanKey to cleanValue
                    }.toMap(),
                )
            }

            private fun splitUrlOption(value: String?): Pair<String, String> {
                val text = cleanString(value)
                val index = text.indexOf(",{")
                if (index < 0) return text to ""
                return text.substring(0, index).trim() to text.substring(index + 1).trim()
            }

            private fun parseHeaders(value: String?): Map<String, String> {
                val text = cleanString(value)
                if (text.isBlank()) return emptyMap()
                if (text.startsWith("{")) {
                    return runCatching {
                        val json = JSONObject(text)
                        buildMap {
                            json.keys().forEach { key ->
                                val cleanKey = cleanString(key)
                                val cleanValue = cleanJsonString(json, cleanKey)
                                if (cleanKey.isNotBlank() && cleanValue.isNotBlank()) {
                                    put(cleanKey, cleanValue)
                                }
                            }
                        }
                    }.getOrDefault(emptyMap())
                }
                return text.lines()
                    .mapNotNull { line ->
                        val index = line.indexOf(':')
                        if (index <= 0) return@mapNotNull null
                        line.substring(0, index).trim() to line.substring(index + 1).trim()
                    }
                    .toMap()
            }

            private fun applyTemplate(value: String, text: String): String =
                Regex("""\{\{\s*(.*?)\s*\}\}""").replace(value) { match ->
                    evalPlaceholder(match.groupValues[1], text)
                }

            private fun evalPlaceholder(expr: String, text: String): String {
                val normalized = expr.replace(" ", "")
                return when {
                    normalized.equals("speakText", ignoreCase = true) -> text
                    normalized == "speakSpeed" -> "10"
                    normalized.equals("java.encodeURI(speakText)", ignoreCase = true) -> encodeUri(text)
                    normalized.equals("java.encodeURIComponent(speakText)", ignoreCase = true) -> encodeUriComponent(text)
                    normalized.equals("encodeURI(speakText)", ignoreCase = true) -> encodeUri(text)
                    normalized.equals("encodeURIComponent(speakText)", ignoreCase = true) -> encodeUriComponent(text)
                    else -> ""
                }
            }

            private fun encodeUri(value: String): String =
                percentEncode(value, URI_ALLOWED)

            private fun encodeUriComponent(value: String): String =
                percentEncode(value, URI_COMPONENT_ALLOWED)

            private fun percentEncode(value: String, allowed: Set<Char>): String {
                val builder = StringBuilder()
                value.forEach { char ->
                    if (char in allowed) {
                        builder.append(char)
                    } else {
                        char.toString().toByteArray(Charsets.UTF_8).forEach { byte ->
                            builder.append('%')
                            builder.append("%02X".format(byte.toInt() and 0xFF))
                        }
                    }
                }
                return builder.toString()
            }

            private val URI_COMPONENT_ALLOWED =
                ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()").toSet()
            private val URI_ALLOWED =
                ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'();/?:@&=+$,#").toSet()
        }
    }

    private companion object {
        const val LOG_TAG = "ReaMicroReadAloud"
        const val CHANNEL_ID = "reamicro_read_aloud"
        const val NOTIFICATION_ID = 4400
        const val HOST_PACKAGE = "app.zhendong.reamicro"
        const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L
        const val COVER_MAX_EDGE_PX = 512
        const val MAX_SPEAK_FAILURES = 3
        const val SPEAK_FAILURE_RETRY_DELAY_MS = 1_000L
        const val MAX_TTS_REDIRECTS = 5
        const val NETWORK_TTS_PREFETCH_WINDOW = 4
        const val NETWORK_TTS_CACHE_MAX_ITEMS = 12
        const val NETWORK_TTS_INFLIGHT_WAIT_MS = 120L

        fun cleanString(value: String?): String =
            value?.trim().orEmpty()

        fun cleanJsonString(json: JSONObject, key: String): String {
            if (key.isBlank() || !json.has(key) || json.isNull(key)) return ""
            val value = json.opt(key) ?: return ""
            return if (value === JSONObject.NULL) "" else value.toString().trim()
        }
    }
}
