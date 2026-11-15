package com.reamicro.fix.reader

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/** Keeps native paragraph identities without keeping a document or its DOM alive. */
internal class HighlightParagraphIndex<T : Any>(private val capacity: Int = 8, private val nodeLimit: Int = 65536) {
    private class Key<T : Any>(value: T, queue: ReferenceQueue<T>? = null) : WeakReference<T>(value, queue) {
        private val hash = System.identityHashCode(value)
        override fun hashCode() = hash
        override fun equals(other: Any?): Boolean = this === other ||
            other is Key<*> && get()?.let { it === other.get() } == true
    }
    private class Sequence<T : Any>(root: T, nodes: List<T>) {
        val root = WeakReference(root)
        val nodes = nodes.map { WeakReference(it) }
    }
    private class Root<T : Any>(val sequence: Sequence<T>, children: List<T>) {
        val children = children.map { WeakReference(it) }
        fun matches(current: List<T>): Boolean = children.size == current.size &&
            children.indices.all { children[it].get() === current[it] }
    }
    private class Position<T : Any>(val sequence: Sequence<T>, val index: Int)
    private val queue = ReferenceQueue<T>()
    private val entries = HashMap<Key<T>, Position<T>>()
    private val roots = LinkedHashMap<Key<T>, Root<T>>(16, .75f, true)
    private val sequences = ArrayDeque<Sequence<T>>()

    init { require(capacity > 0 && nodeLimit > 0) }

    @Synchronized
    fun register(root: T, isParagraph: (T) -> Boolean, children: (T) -> List<T>, required: Boolean = true): Boolean {
        if (!required) {
            clear()
            return false
        }
        drainQueue()
        roots[Key(root)]?.let { known ->
            if (known.matches(children(root))) {
                sequences.remove(known.sequence)
                sequences.addLast(known.sequence)
                return true
            }
            // Native trees may retain a container while replacing its logical children.
            // Rebuild the complete order, not a truncated local paragraph window.
            val completeRoot = known.sequence.root.get()
            removeSequence(known.sequence)
            if (completeRoot != null && completeRoot !== root &&
                register(completeRoot, isParagraph, children) && roots.containsKey(Key(root))) return true
        }
        // The bounded alias map may have evicted the root while its paragraph order remains.
        sequences.firstOrNull { it.root.get() === root }?.let(::removeSequence)
        val seen = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        val pending = ArrayDeque<T>().apply { add(root) }
        val paragraphs = ArrayList<T>()
        val containers = ArrayList<Pair<T, List<T>>>()
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!seen.add(node)) continue
            if (seen.size > nodeLimit) return false
            if (isParagraph(node)) paragraphs.add(node)
            else {
                val descendants = children(node)
                containers.add(node to descendants)
                descendants.asReversed().forEach(pending::addLast)
            }
        }
        if (paragraphs.isEmpty()) return false
        val first = entries[Key(paragraphs.first())]
        if (first != null && paragraphs.indices.all { offset ->
            first.sequence.nodes.getOrNull(first.index + offset)?.get() === paragraphs[offset]
        }) {
            // A late getBody/local-container hook must not replace an existing full
            // document with a smaller window or evict it from the bounded cache.
            sequences.remove(first.sequence)
            sequences.addLast(first.sequence)
            // A wrapper discovered after parsing can share the full document's order.
            // Record it too, so repeated native measurement does not walk it again.
            rememberRoot(root, first.sequence, children(root))
            return true
        }
        val sequence = Sequence(root, paragraphs)
        // A complete document can supersede an earlier local-container snapshot after reload.
        val identities = Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).apply { addAll(paragraphs) }
        val superseded = sequences.filter { old -> old.nodes.all { it.get()?.let(identities::contains) != false } }
        superseded.forEach(::removeSequence)
        paragraphs.forEachIndexed { index, node -> entries[Key(node, queue)] = Position(sequence, index) }
        containers.forEach { (container, descendants) -> rememberRoot(container, sequence, descendants) }
        sequences.addLast(sequence)
        while (sequences.size > capacity) {
            removeSequence(sequences.first)
        }
        return true
    }

    @Synchronized
    fun window(current: T, radius: Int): HighlightParagraphWindow<T>? {
        require(radius >= 0)
        drainQueue()
        val position = entries[Key(current)] ?: return null
        val nodes = position.sequence.nodes
        val before = ArrayList<T>()
        for (index in position.index - 1 downTo maxOf(0, position.index - radius)) {
            before.add(nodes[index].get() ?: break)
        }
        val result = ArrayList<T>(before.size + radius + 1)
        result.addAll(before.asReversed())
        result.add(current)
        for (index in position.index + 1 until minOf(nodes.size, position.index + radius + 1)) {
            result.add(nodes[index].get() ?: break)
        }
        return HighlightParagraphWindow(result, before.size)
    }

    @Synchronized
    fun clear() { entries.clear(); roots.clear(); sequences.clear(); drainQueue() }

    private fun rememberRoot(root: T, sequence: Sequence<T>, children: List<T>) {
        roots[Key(root, queue)] = Root(sequence, children)
        val limit = minOf(4096L, nodeLimit.toLong() * capacity).toInt()
        while (roots.size > limit) roots.keys.iterator().run { next(); remove() }
    }

    private fun removeSequence(old: Sequence<T>) {
        sequences.remove(old)
        old.nodes.forEach { ref -> ref.get()?.let { node ->
            val key = Key(node)
            if (entries[key]?.sequence === old) entries.remove(key)
        } }
        // Includes late wrappers as well as containers from the initial document.
        roots.entries.removeAll { it.value.sequence === old }
    }

    private fun drainQueue() {
        while (true) {
            val key = queue.poll() ?: return
            entries.remove(key)
            roots.remove(key)
        }
    }
}
