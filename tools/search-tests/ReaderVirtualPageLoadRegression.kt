package com.reamicro.fix.hook

internal fun runVirtualPageLoadRegression() {
    val middle = (4986..5011).toSet()
    val beginning = (0..3).toSet()
    check(ReaderVirtualPageLoadPolicy.accepts(0, beginning))
    check(ReaderVirtualPageLoadPolicy.accepts(5000, middle))
    check(!ReaderVirtualPageLoadPolicy.accepts(0, middle))
    check(!ReaderVirtualPageLoadPolicy.accepts(5000, beginning))
    for (distance in 1..32) {
        check(ReaderVirtualPageLoadPolicy.accepts(4986 - distance, middle))
        check(ReaderVirtualPageLoadPolicy.accepts(5011 + distance, middle))
    }
    check(!ReaderVirtualPageLoadPolicy.accepts(4986 - 33, middle))
    check(!ReaderVirtualPageLoadPolicy.accepts(5011 + 33, middle))
    check(ReaderVirtualPageLoadPolicy.accepts(5000, emptySet()))
    check(ReaderVirtualPageLoadPolicy.accepts(5000, setOf(4999, 5001)))
    check(!ReaderVirtualPageLoadPolicy.accepts(Int.MIN_VALUE, setOf(Int.MAX_VALUE)))
    check(!ReaderVirtualPageLoadPolicy.accepts(Int.MAX_VALUE, setOf(Int.MIN_VALUE)))
    // 旧请求在等待加载锁期间经历窗口重建，必须按取得锁后的窗口判断，而非入队时的窗口。
    var mapped = beginning
    val queued = listOf(0, 1, 2)
    check(queued.all { ReaderVirtualPageLoadPolicy.accepts(it, mapped) })
    mapped = middle
    check(queued.none { ReaderVirtualPageLoadPolicy.accepts(it, mapped) })
    mapped = beginning
    check((4999..5001).none { ReaderVirtualPageLoadPolicy.accepts(it, mapped) })
    println("Virtual page regression passed: stale requests after recenter, neighboring prefetch, overflow")
}
