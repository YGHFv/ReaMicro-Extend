package com.reamicro.fix.cloud.webdav

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.reamicro.fix.R
import com.reamicro.fix.logging.ModuleLogLevel
import com.reamicro.fix.logging.legacyModuleLogLevel
import com.reamicro.fix.webdav.CloudDownloadCancelledException
import com.reamicro.fix.webdav.CancellableWebDavDownload
import com.reamicro.fix.webdav.WebDavBackupSnapshot
import com.reamicro.fix.webdav.WebDavCredentials
import com.reamicro.fix.webdav.WebDavHttpException
import com.reamicro.fix.webdav.WebDavImportSource
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.ref.WeakReference
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import org.w3c.dom.Element
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.online.search.homeCloudSearchResults
import com.reamicro.fix.online.download.importCacheFile
import com.reamicro.fix.online.download.enqueueNativeImport
import com.reamicro.fix.online.download.cleanupDownloadToken
import com.reamicro.fix.online.download.cancelExistingBackupTasks
import com.reamicro.fix.online.download.findImportBookMethod
import com.reamicro.fix.logging.logWebDav

// WebDAV 的纯逻辑部分。
//
// 路径规整与父子推导、href 解析、日志脱敏、文件名安全化、登录页 HTML。
//
// 这些函数不依赖 hook 实例——由 tools/find-pure-functions.mjs 编译验证。
internal fun cleartextHostOf(value: Any?): String =
    when (value) {
        null -> ""
        is URL -> value.host.orEmpty()
        is URI -> value.host.orEmpty()
        is String -> {
            val text = value.trim()
            if (text.isBlank()) {
                ""
            } else if (text.contains("://")) {
                runCatching { URI(text).host.orEmpty() }
                    .getOrDefault("")
                    .ifBlank { runCatching { URL(text).host.orEmpty() }.getOrDefault("") }
            } else {
                text.substringBefore('/').substringBefore(':').trim()
            }
        }
        else -> cleartextHostOf(value.toString())
    }

internal fun throwIfWebDavDownloadCancelled(token: CancellableWebDavDownload) {
    if (token.cancelRequested || Thread.currentThread().isInterrupted) {
        throw CloudDownloadCancelledException()
    }
}

internal fun webDavDownloadKey(path: String, name: String): String =
    normalizeWebDavPath(path.ifBlank { name })

internal fun syntheticWebDavBookEntry(path: String): WebDavEntry {
    val normalized = normalizeWebDavPath(path)
    val name = normalized.substringAfterLast('/').ifBlank { WEBDAV_TITLE }
    logWebDav("getInfo fallback synthetic path=$normalized")
    return WebDavEntry(
        name = name,
        path = normalized,
        isDirectory = false,
        size = 0L,
        updatedAt = System.currentTimeMillis(),
    )
}

internal fun webDavBookUrl(path: String): String =
    WEBDAV_SOURCE_PREFIX + normalizeWebDavPath(path)

internal fun hasWebDavLogin(context: Context?): Boolean {
    val prefs = webDavPrefs(context) ?: return false
    val hasCredentials = !prefs.getString(KEY_URL, "").isNullOrBlank() &&
        !prefs.getString(KEY_USERNAME, "").isNullOrBlank() &&
        !prefs.getString(KEY_PASSWORD, "").isNullOrBlank()
    return hasCredentials && prefs.getBoolean(KEY_AUTHORIZED, hasCredentials)
}

internal fun webDavPrefs(context: Context?): android.content.SharedPreferences? {
    val appContext = context ?: runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Context
    }.getOrNull()?.applicationContext
    return appContext?.getSharedPreferences(WEBDAV_PREFS, Context.MODE_PRIVATE)
}

internal fun webDavDisplayDir(path: String): String {
    val normalized = normalizeWebDavPath(path.ifBlank { "/" })
    if (normalized == "/") return ROOT_DIR_NAME
    return (ROOT_DIR_NAME + normalized)
        .trimEnd('/')
        .replace("/", " \u25cb ")
        .uppercase(Locale.ROOT)
}

internal fun childWebDavPath(parent: String, name: String): String {
    val cleanName = name.trim().trim('/')
    return normalizeWebDavPath("${normalizeWebDavPath(parent).trimEnd('/')}/$cleanName")
}

internal fun webDavPathArg(path: String): String =
    if (path.isBlank() || path == "root") "/" else normalizeWebDavPath(path)

internal fun parentWebDavPath(path: String): String {
    val normalized = normalizeWebDavPath(path)
    return normalized.substringBeforeLast('/', "").ifBlank { "/" }
}

internal fun normalizeWebDavPath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank() || trimmed == "root") return "/"
    return "/" + trimmed.trim('/').replace(Regex("/{2,}"), "/")
}


internal fun webDavLoginHtml(url: String, username: String): String {
    val escapedUrl = url.htmlAttrEscape()
    val escapedUsername = username.htmlAttrEscape()
    return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
              <style>
                * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                html, body {
                  margin: 0;
                  width: 100%;
                  height: 100%;
                  min-height: 100vh;
                  background: #ffffff;
                  color: #222628;
                  font-family: "Noto Serif CJK SC", "Songti SC", STSong, SimSun, serif;
                  -webkit-text-size-adjust: 100%;
                  text-size-adjust: 100%;
                  overflow-x: hidden;
                }
                body { padding: env(safe-area-inset-top) 0 env(safe-area-inset-bottom); }
                body {
                  transform: translateX(0);
                  opacity: 1;
                  transition: transform 260ms cubic-bezier(.2, 0, 0, 1), opacity 220ms linear;
                  will-change: transform, opacity;
                }
                body.closing {
                  transform: translateX(100%);
                  opacity: 0.98;
                }
                .topbar {
                  height: 64px;
                  display: flex;
                  align-items: center;
                  padding: 0 14px;
                }
                .back {
                  width: 40px;
                  height: 40px;
                  border: none;
                  border-radius: 20px;
                  background: transparent;
                  padding: 8px;
                  display: grid;
                  place-items: center;
                }
                main {
                  padding: 18px 28px 32px;
                  max-width: 500px;
                  margin: 0 auto;
                }
                .brand {
                  display: flex;
                  align-items: center;
                  gap: 14px;
                  margin-bottom: 28px;
                }
                .logo {
                  width: 34px;
                  height: 34px;
                  border-radius: 7px;
                  background: #4bafa7;
                  display: grid;
                  place-items: center;
                }
                .brand span {
                  color: #3570c4;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  font-size: 24px;
                  line-height: 1;
                  font-weight: 600;
                  letter-spacing: 0;
                }
                h1 {
                  margin: 0 0 26px;
                  color: #222628;
                  font-size: 24px;
                  line-height: 1.28;
                  font-weight: 700;
                  letter-spacing: 0;
                }
                .field { margin-top: 12px; }
                input {
                  width: 100%;
                  height: 52px;
                  border: 0;
                  border-radius: 8px;
                  outline: none;
                  background: #f7f7f7;
                  color: #222628;
                  font-family: inherit;
                  font-size: 16px;
                  line-height: 52px;
                  padding: 0 16px;
                  letter-spacing: 0;
                  font-weight: 600;
                }
                input::placeholder { color: #a6a6a6; }
                .note {
                  margin: 10px 2px 0;
                  color: #8b8f94;
                  font-size: 14px;
                  line-height: 1.5;
                }
                .submit {
                  width: 100%;
                  height: 50px;
                  margin-top: 22px;
                  border: 0;
                  border-radius: 8px;
                  background: #dddddd;
                  color: #ffffff;
                  font-family: inherit;
                  font-size: 16px;
                  font-weight: 700;
                  letter-spacing: 0;
                }
              </style>
            </head>
            <body>
              <div class="topbar">
                <button class="back" type="button" aria-label="返回" onclick="ReaMicroWebDav.back()">
                  <svg width="28" height="28" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path d="M20 11H7.83L13.42 5.41L12 4L4 12L12 20L13.42 18.59L7.83 13H20V11Z" fill="#222628"/>
                  </svg>
                </button>
              </div>
              <main>
                <div class="brand">
                  <div class="logo" aria-hidden="true">
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
                      <path d="M8.6 14.4c-1.35 0-2.35-.96-2.35-2.15 0-1.22 1-2.12 2.34-2.12.23 0 .47.03.7.1C9.95 8.9 11.1 8.15 12.45 8.15c1.74 0 3.13 1.13 3.5 2.73 1.08.15 1.92 1.03 1.92 2.12 0 1.2-.97 2.15-2.22 2.15H8.6Z" fill="white"/>
                      <rect x="6.9" y="17" width="10.2" height="1.35" rx=".65" fill="white" opacity=".84"/>
                    </svg>
                  </div>
                  <span>WebDAV</span>
                </div>
                <h1>欢迎登录 WebDAV 账号</h1>
                <div class="field">
                  <input id="server" value="$escapedUrl" autocomplete="url" inputmode="url" placeholder="服务器地址">
                </div>
                <div class="field">
                  <input id="username" value="$escapedUsername" autocomplete="username" placeholder="账号">
                </div>
                <div class="field">
                  <input id="password" autocomplete="current-password" placeholder="密码" type="password">
                </div>
                <p class="note">登录信息仅保存在本机。</p>
                <button class="submit" type="button" onclick="submitWebDav()">授权并登录</button>
              </main>
              <script>
                let closing = false;
                window.ReaMicroClose = function() {
                  if (closing) return;
                  closing = true;
                  document.body.classList.add('closing');
                  setTimeout(function() {
                    ReaMicroWebDav.closeNow();
                  }, 230);
                };
                function submitWebDav() {
                  ReaMicroWebDav.save(
                    document.getElementById('server').value,
                    document.getElementById('username').value,
                    document.getElementById('password').value
                  );
                }
              </script>
            </body>
            </html>
        """.trimIndent()
}
