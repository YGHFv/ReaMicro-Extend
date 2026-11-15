package com.reamicro.fix.online

import com.reamicro.fix.hook.webdav.OnlineDownloadTarget

/** Reading a legacy task's mode must not initialize the upstream parser. */
internal fun OnlineParserMode.forTarget(target: OnlineDownloadTarget): OnlineParsingState =
    synchronized(target) {
        val state = target.result.parsingState ?: capture().also {
            target.result = target.result.copy(parsingState = it)
        }
        requireCurrent(state.generation)
        state
    }
