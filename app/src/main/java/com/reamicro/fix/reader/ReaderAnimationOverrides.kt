package com.reamicro.fix.reader

import java.lang.ref.WeakReference

/** Remember native specs by identity; Fling equality changes when its spec is replaced. */
internal class ReaderAnimationOverrides {
    private val originals = mutableListOf<Pair<WeakReference<Any>, Any>>()

    @Synchronized fun capture(owner: Any, nativeSpec: Any) {
        originals.removeAll { it.first.get() == null }
        if (originals.none { it.first.get() === owner }) originals += WeakReference(owner) to nativeSpec
    }

    @Synchronized fun restore(owner: Any, apply: (Any) -> Unit) {
        originals.removeAll { it.first.get() == null }
        originals.firstOrNull { it.first.get() === owner }?.second?.let(apply)
    }
}
