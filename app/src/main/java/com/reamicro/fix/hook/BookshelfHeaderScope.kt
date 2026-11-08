package com.reamicro.fix.hook

/** Native header render scopes nest; an independent sync restart has its own layout scope. */
internal class BookshelfHeaderScope {
    internal class Frame(
        val composer: Any,
        val shelfVisible: Boolean,
        val selecting: Boolean,
        val selectionAvailable: Boolean,
        val parent: Frame?,
    ) {
        var actionsClaimed = false
        var selectionSpacingClaimed = false
        var skipDepth = 0
    }

    private val active = ThreadLocal<Frame?>()
    fun current(): Frame? = active.get()
    fun enter(composer: Any, shelfVisible: Boolean, selecting: Boolean, selectionAvailable: Boolean = true): Frame =
        Frame(composer, shelfVisible, selecting, selectionAvailable, active.get()).also(active::set)

    fun leave(frame: Frame) {
        if (active.get() !== frame) return
        if (frame.parent == null) active.remove() else active.set(frame.parent)
    }

    fun enterSkip(composer: Any): Frame? = active.get()?.takeIf { it.composer === composer }?.also { it.skipDepth++ }
    fun leaveSkip(frame: Frame) { frame.skipDepth-- }

    fun claimActions(composer: Any): Frame? {
        val frame = active.get() ?: return null
        if (frame.composer !== composer || !frame.shelfVisible || frame.selecting || !frame.selectionAvailable ||
            frame.actionsClaimed || frame.skipDepth > 0) return null
        frame.actionsClaimed = true
        return frame
    }

    fun claimSelectionSpacing(composer: Any): Boolean {
        val frame = active.get() ?: return false
        if (frame.composer !== composer || !frame.shelfVisible || !frame.selectionAvailable ||
            frame.skipDepth > 0 || frame.selectionSpacingClaimed) return false
        frame.selectionSpacingClaimed = true
        return true
    }
}
