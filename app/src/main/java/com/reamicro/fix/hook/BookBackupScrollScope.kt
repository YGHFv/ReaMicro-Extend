package com.reamicro.fix.hook

/** Only the first layout of this backup render owns the scroll viewport. */
internal class BookBackupScrollScope {
    internal class Frame(val composer: Any, val parent: Frame?) {
        var layoutClaimed = false
        var skipDepth = 0
    }

    private val current = ThreadLocal<Frame?>()

    fun enter(composer: Any): Frame = Frame(composer, current.get()).also(current::set)

    fun leave(frame: Frame) {
        if (current.get() !== frame) return
        if (frame.parent == null) current.remove() else current.set(frame.parent)
    }

    fun claimOuterLayout(composer: Any): Boolean {
        val frame = current.get() ?: return false
        if (frame.composer !== composer || frame.layoutClaimed || frame.skipDepth > 0) return false
        frame.layoutClaimed = true
        return true
    }

    fun enterSkip(composer: Any): Frame? = current.get()?.takeIf { it.composer === composer }?.also { it.skipDepth++ }
    fun leaveSkip(frame: Frame) { frame.skipDepth-- }
}
