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
    val refs = callNoArg(epub, "getItemRefs") as? List<*> ?: return null
    onDemandSpineTable?.takeIf { it.owner.get() === epub && it.itemRefs.get() === refs }?.let { return it }
    val indices = refs.map { (it?.let { ref -> callNoArg(ref, "getIndex") } as? Number)?.toInt() ?: -1 }
    val positions = OnlineOnDemandPrefetchPlanner.itemRefPositions(indices)
    if (positions.isEmpty()) return null
    val table = ReaderOnDemandSpineTable(
        WeakReference(epub), WeakReference(refs), positions,
        refs.map { normalizeOnDemandHref(it?.let { ref -> callString(ref, "getHref") }.orEmpty()) },
    )
    if ((currentEpubStrong ?: currentEpubRef?.get()) === epub) onDemandSpineTable = table
    return table
}
