package com.reamicro.fix.cloud.webdav

import java.io.File

/** 共享宿主对象存储契约，清单、基线和冲突合并仍由阅微管理。 */
internal interface ShelfSyncTransport {
    fun get(key: String): WebDavHttpResponse?
    fun head(key: String): WebDavHttpResponse?
    fun put(key: String, bytes: ByteArray?, file: File?, mime: String, condition: String?)
    fun delete(key: String)
    fun download(key: String, output: File)
    fun verify()
}
