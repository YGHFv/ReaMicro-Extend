package com.reamicro.fix.hook

import com.reamicro.fix.online.download.OnlineOnDemandPrefetchPlanner
import java.lang.ref.WeakReference

internal data class ReaderOnDemandSpineTable(
    val owner: WeakReference<Any>,
    val itemRefs: WeakReference<Any>,
    val positions: Map<Int, Int>,
    val hrefs: List<String>,
)

internal fun ReaderHook.onDemandSpineTable(epub: Any): ReaderOnDemandSpineTable? {
    val host = com.reamicro.fix.core.HostEpubRuntimeBindings.current ?: return null
    val refs = host.value("epub.itemRefs", epub) as? List<*> ?: return null
    onDemandSpineTable?.takeIf { it.owner.get() === epub && it.itemRefs.get() === refs }?.let { return it }
    val indices = refs.map { host.int("item.index", it) ?: -1 }
    val positions = OnlineOnDemandPrefetchPlanner.itemRefPositions(indices)
    if (positions.isEmpty()) return null
    val table = ReaderOnDemandSpineTable(
        WeakReference(epub), WeakReference(refs), positions,
        refs.map { normalizeOnDemandHref(host.text("item.href", it)) },
    )
    if ((currentEpubStrong ?: currentEpubRef?.get()) === epub) onDemandSpineTable = table
    return table
}
