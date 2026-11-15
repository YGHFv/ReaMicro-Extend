package io.legado.app.help.exoplayer

import android.annotation.SuppressLint
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.google.gson.reflect.TypeToken
import io.legado.app.utils.GSON

/** Upstream MediaItem URL/header representation. Playback is supplied by the host. */
@SuppressLint("UnsafeOptInUsageError")
object ExoPlayerHelper {
    private const val SPLIT_TAG = "\uD83D\uDEA7"

    private val mapType by lazy {
        object : TypeToken<Map<String, String>>() {}.type
    }

    fun createMediaItem(url: String, headers: Map<String, String>): MediaItem {
        val formatUrl = url + SPLIT_TAG + GSON.toJson(headers, mapType)
        val builder = MediaItem.Builder().setUri(formatUrl)
        // SPLIT_TAG 破坏了 URI 后缀，DefaultMediaSourceFactory 无法据扩展名推断类型
        val lastPathSegment = Uri.parse(url).lastPathSegment.orEmpty()
        if (lastPathSegment.endsWith(".mpd", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD)
        } else if (url.contains(".m3u8", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return builder.build()
    }

}
