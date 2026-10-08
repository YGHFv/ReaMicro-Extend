package com.reamicro.fix.hook

import kotlinx.coroutines.CancellationException

internal suspend fun <T> structurePreviewResult(load: suspend () -> T): Result<T> = try {
    Result.success(load())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
