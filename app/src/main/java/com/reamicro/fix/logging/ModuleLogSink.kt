package com.reamicro.fix.logging

internal interface ModuleLogSink {
    fun log(priority: Int, tag: String, text: String, throwable: Throwable?)
}
