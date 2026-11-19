package com.reamicro.fix.online.download

import com.reamicro.fix.hook.webdav.ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT
import com.reamicro.fix.reload.TaskCancellation

/** Only unresolved, retryable chapters enter another round; finished chapters are never requested. */
internal fun retryOnlineCompletionChapters(
    candidates: () -> List<Int>,
    beforeRound: (Int, List<Int>) -> Unit,
    attempt: (Int, Int, Int) -> Unit,
) {
    repeat(ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT) { round ->
        TaskCancellation.checkCurrent()
        val remaining = candidates().distinct()
        if (remaining.isEmpty()) return
        beforeRound(round + 1, remaining)
        remaining.forEachIndexed { offset, index ->
            TaskCancellation.checkCurrent()
            attempt(index, offset, remaining.size)
        }
    }
}
