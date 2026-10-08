package com.reamicro.fix.hook

internal enum class ReaderPagerConfirmation { WAITING, ACKNOWLEDGED, EXPIRED, CANCELLED }

internal fun readerPagerConfirmation(
    sameSession: Boolean, expectedPagePresent: Boolean, sameObservedPage: Boolean,
    now: Long, deadline: Long,
): ReaderPagerConfirmation = when {
    !sameSession || !expectedPagePresent -> ReaderPagerConfirmation.CANCELLED
    sameObservedPage -> ReaderPagerConfirmation.ACKNOWLEDGED
    now >= deadline -> ReaderPagerConfirmation.EXPIRED
    else -> ReaderPagerConfirmation.WAITING
}
