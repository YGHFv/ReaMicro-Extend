package com.reamicro.fix.core

/** B2 的 tryLock(owner) 被 R8 改为 tryLock()，锁的 owner 固定为 null。 */
internal class HostMutex(private val target: Any, private val owner: Any) {
    private val tryLockWithOwner = target.javaClass.hostMethodOrNull("tryLock", Any::class.java)
    private val tryLock = tryLockWithOwner ?: target.javaClass.hostMethod("tryLock")
    private val unlock = target.javaClass.hostMethod("unlock", Any::class.java)
    private var acquired = false

    fun tryAcquire(): Boolean {
        check(!acquired) { "Mutex lease already acquired" }
        acquired = if (tryLockWithOwner != null) tryLock.invoke(target, owner) == true else tryLock.invoke(target) == true
        return acquired
    }

    fun release() {
        check(acquired) { "Mutex lease was not acquired" }
        unlock.invoke(target, if (tryLockWithOwner != null) owner else null)
        acquired = false
    }
}
