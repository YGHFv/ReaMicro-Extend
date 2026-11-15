package com.reamicro.fix.core

/** Exact native mutex methods are validated with the reader window contract. */
internal class HostMutex(private val target: Any, private val owner: Any) {
    private val host = requireNotNull(HostReaderWindowBindings.current) { "Reader mutex contract unavailable" }
    private val tryLock = host.method("tryLock")
    private val unlock = host.method("unlock")
    private val withOwner = tryLock.parameterCount == 1
    private var acquired = false
    init { require(host.type("mutex").isInstance(target)) }
    fun tryAcquire(): Boolean {
        check(!acquired) { "Mutex lease already acquired" }
        acquired = if (withOwner) tryLock.invoke(target, owner) == true else tryLock.invoke(target) == true
        return acquired
    }
    fun release() {
        check(acquired) { "Mutex lease was not acquired" }
        unlock.invoke(target, if (withOwner) owner else null)
        acquired = false
    }
}
