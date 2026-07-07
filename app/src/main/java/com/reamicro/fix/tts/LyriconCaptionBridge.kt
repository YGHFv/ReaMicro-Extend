package com.reamicro.fix.tts

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider

object LyriconCaptionBridge {
    @Volatile private var provider: LyriconProvider? = null
    @Volatile private var lastRegisterAtMs: Long = 0L
    @Volatile private var lastText: String = ""

    fun sendText(context: Context, text: String?, playing: Boolean = true) {
        val clean = text.orEmpty().trim()
        if (clean.isBlank()) {
            clear(context)
            return
        }
        if (clean == lastText) {
            syncPlaybackState(context, playing)
            return
        }
        val player = ensureProvider(context)?.player ?: return
        runCatching {
            player.setPlaybackState(playing)
            val sent = player.sendText(clean)
            if (!sent) requestRegister(context)
            lastText = clean
        }.onFailure {
            Log.i(LOG_TAG, "send Lyricon text failed", it)
            requestRegister(context)
        }
    }

    fun syncPlaybackState(context: Context, playing: Boolean) {
        val player = ensureProvider(context)?.player ?: return
        runCatching {
            player.setPlaybackState(playing)
        }.onFailure {
            Log.i(LOG_TAG, "sync Lyricon playback failed", it)
            requestRegister(context)
        }
    }

    fun clear(context: Context) {
        val player = ensureProvider(context)?.player ?: return
        runCatching {
            player.setPlaybackState(false)
            player.sendText(null)
            lastText = ""
        }.onFailure {
            Log.i(LOG_TAG, "clear Lyricon text failed", it)
        }
    }

    fun destroy() {
        runCatching { provider?.destroy() }
        provider = null
        lastText = ""
        lastRegisterAtMs = 0L
    }

    private fun ensureProvider(context: Context): LyriconProvider? {
        provider?.let {
            if (it.service.isActive && it.player.isActive) return it
            requestRegister(context)
            return it
        }
        synchronized(this) {
            provider?.let { return it }
            return runCatching {
                LyriconFactory.createProvider(
                    context = context.applicationContext,
                    providerPackageName = context.packageName,
                    playerPackageName = HOST_PACKAGE_NAME,
                ).also {
                    provider = it
                    it.autoSync = true
                    requestRegister(context)
                }
            }.onFailure {
                Log.i(LOG_TAG, "create Lyricon provider failed", it)
            }.getOrNull()
        }
    }

    private fun requestRegister(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRegisterAtMs < REGISTER_RETRY_INTERVAL_MS) return
        lastRegisterAtMs = now
        runCatching {
            provider?.register()
        }.onFailure {
            Log.i(LOG_TAG, "register Lyricon provider failed", it)
        }
    }

    private const val LOG_TAG = "ReaMicroLyricon"
    private const val HOST_PACKAGE_NAME = "app.zhendong.reamicro"
    private const val REGISTER_RETRY_INTERVAL_MS = 2_000L
}
