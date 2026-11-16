package com.reamicro.fix.cloud.webdav

import com.reamicro.fix.cloud.webdav.SyncCbor.Atom
import com.reamicro.fix.cloud.webdav.SyncCbor.Fields
import com.reamicro.fix.cloud.webdav.SyncCbor.Items
import com.reamicro.fix.cloud.webdav.SyncCbor.decode
import com.reamicro.fix.cloud.webdav.SyncCbor.encode
import com.reamicro.fix.cloud.webdav.SyncCbor.list
import com.reamicro.fix.cloud.webdav.SyncCbor.long
import com.reamicro.fix.cloud.webdav.SyncCbor.map
import com.reamicro.fix.cloud.webdav.SyncCbor.number
import com.reamicro.fix.cloud.webdav.SyncCbor.string
import com.reamicro.fix.cloud.webdav.SyncCbor.text
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ShelfSyncRetentionTest {
    private val rootKey = "reamicro/manifest.cbor"
    private fun book(id: String, timestamp: Long, metadata: Long = timestamp): Fields = Fields(mapOf(
        "b" to Fields(mapOf("u" to text(id), "t" to text("图书$id"))),
        "r" to Fields(mapOf("t" to number(timestamp), "c" to number(1), "d" to text("device"))),
        "m" to number(metadata),
        "e" to Fields(mapOf("o" to text("$timestamp-${"a".repeat(32)}"), "h" to Atom(byteArrayOf(0x41, 1)))),
    ))
    private class Store : ShelfSyncTransport {
        val data = linkedMapOf<String, ByteArray>()
        val writes = arrayListOf<String>()
        var rejectRoot = false
        override fun get(key: String): WebDavHttpResponse? = data[key]?.let {
            WebDavHttpResponse(200, null, mapOf("ETag" to "\"${it.contentHashCode()}\""), it)
        }
        override fun head(key: String) = get(key)
        override fun put(key: String, bytes: ByteArray?, file: File?, mime: String, condition: String?) {
            if (key.endsWith("/manifest.cbor") && rejectRoot) error("412")
            if (condition == "*" && key in data) error("412")
            if (condition != null && condition != "*" && condition != get(key)?.headers?.get("ETag")) error("412")
            writes += key
            data[key] = bytes ?: requireNotNull(file).readBytes()
        }
        override fun delete(key: String) { error("数量清理不能直接删除文件") }
        override fun download(key: String, output: File) { error("unused") }
        override fun verify() {}
    }
    private fun seed(vararg books: Fields): Store = Store().apply {
        val grouped = books.groupBy { ShelfSyncRetention.uuid(it).take(2) }
        val objects = grouped.mapValues { text("1-${"b".repeat(32)}") }
        grouped.forEach { (name, items) ->
            data["reamicro/manifests/$name/1-${"b".repeat(32)}.cbor"] = encode(Fields(mapOf("b" to Items(items))))
        }
        data[rootKey] = encode(Fields(mapOf(
            "v" to number(1), "r" to number(4),
            "s" to Items(grouped.keys.map(::text)), "sp" to Fields(objects),
            "sr" to Fields(grouped.keys.associateWith { number(1) }),
            "d" to Items(listOf(text("zz"))),
            "dr" to Fields(mapOf("zz" to number(5))),
            "custom" to Fields(mapOf("opaque" to Atom(byteArrayOf(0x43, 1, 2, 3)))),
        )))
    }
    private fun ids(store: Store): Set<String> {
        val root = map(decode(store.data.getValue(rootKey)))
        return map(root["sp"]).flatMap { (name, version) ->
            val path = "reamicro/manifests/$name/${string(version)}.cbor"
            list(map(decode(store.data.getValue(path)))["b"]).map { ShelfSyncRetention.uuid(it as Fields) }
        }.toSet()
    }
    @Test fun allShardsUseLatestModificationAndPreserveDeletionRecords() {
        val store = seed(book("aa-old", 1), book("ab-meta", 2, 8), book("ac-new", 7))
        var retired = emptyList<Fields>()
        ShelfSyncRetention(store, 2, { 100 }) { retired = it }.get(rootKey)
        assertEquals(setOf("ab-meta", "ac-new"), ids(store))
        assertEquals(listOf("aa-old"), retired.map(ShelfSyncRetention::uuid))
        val root = map(decode(store.data.getValue(rootKey)))
        assertEquals(listOf("zz"), list(root["d"]).map(::string))
        assertEquals(5, long(map(root["dr"])["zz"]))
        assertArrayEquals(byteArrayOf(0x43, 1, 2, 3), (map(root["custom"])["opaque"] as Atom).raw)
        val gc = map(root["gc"])
        assertTrue(gc.keys.any { it.endsWith("/book.epub") && it.contains("aa-old") })
        assertTrue(gc.values.all { long(it) == 86_400_100L })
    }
    @Test fun repeatedReadIsStableAndDisabledDoesNotMutate() {
        val store = seed(book("aa-a", 1), book("aa-b", 2))
        ShelfSyncRetention(store, null).get(rootKey)
        assertTrue(store.writes.isEmpty())
        val remote = ShelfSyncRetention(store, 1)
        remote.get(rootKey)
        val writes = store.writes.size
        remote.get(rootKey)
        assertEquals(writes, store.writes.size)
        assertEquals(setOf("aa-b"), ids(store))
    }
    @Test fun headEnforcesLimitEvenWhenHostWouldUseCachedRoot() {
        val store = seed(book("aa-a", 1), book("bb-b", 2))
        val response = ShelfSyncRetention(store, 1).head(rootKey)
        assertEquals(setOf("bb-b"), ids(store))
        assertEquals(store.data.getValue(rootKey).size.toString(), response!!.headers["Content-Length"])
    }
    @Test fun failedCasLeavesPublishedRootAndLocalDeletionGuardUntouched() {
        val store = seed(book("aa-a", 1), book("bb-b", 2))
        val original = store.data.getValue(rootKey).clone()
        store.rejectRoot = true
        var callback = false
        assertThrows(IllegalStateException::class.java) {
            ShelfSyncRetention(store, 1, onHistory = { if (it.isNotEmpty()) callback = true }).get(rootKey)
        }
        assertArrayEquals(original, store.data[rootKey])
        assertFalse(callback)
        assertEquals(setOf("aa-a", "bb-b"), ids(store))
    }
    @Test fun loweringLimitAndRootMaintenanceCarryHistory() {
        val store = seed(book("aa-a", 1), book("bb-b", 2), book("cc-c", 3))
        ShelfSyncRetention(store, 2).get(rootKey)
        ShelfSyncRetention(store, 1).get(rootKey)
        val bytes = store.data.getValue(rootKey)
        val next = encode(Fields(map(decode(bytes)) + ("r" to number(20))))
        ShelfSyncRetention(store, null).put(rootKey, next, null, "application/cbor", store.get(rootKey)!!.headers["ETag"])
        var history = emptyList<Fields>()
        ShelfSyncRetention(store, null, onHistory = { history = it }).get(rootKey)
        assertEquals(setOf("aa-a", "bb-b"), history.map(ShelfSyncRetention::uuid).toSet())
        assertEquals(setOf("cc-c"), ids(store))
    }
    @Test fun unchangedReuploadDoesNotDisplaceNewBookAndKeepsBothDeviceRevisions() {
        val store = seed(book("aa-a", 1), book("bb-b", 8))
        ShelfSyncRetention(store, 1).get(rootKey)
        val current = store.data.getValue(rootKey)
        val root = map(decode(current))
        val a = book("aa-a", 1).let { Fields(it.values + ("r" to Fields(mapOf(
            "t" to number(20), "c" to number(2), "d" to text("device2"),
        )))) }
        val shardVersion = "30-${"d".repeat(32)}"
        store.data["reamicro/manifests/aa/$shardVersion.cbor"] = encode(Fields(mapOf("b" to Items(listOf(a)))))
        val proposed = encode(Fields(root + mapOf(
            "s" to Items(listOf(text("aa"), text("bb"))),
            "sp" to Fields(map(root["sp"]) + ("aa" to text(shardVersion))),
        )))
        ShelfSyncRetention(store, 1).put(rootKey, proposed, null, "application/cbor", store.get(rootKey)!!.headers["ETag"])
        assertEquals(setOf("bb-b"), ids(store))
        var history = emptyList<Fields>()
        ShelfSyncRetention(store, null, onHistory = { history = it }).get(rootKey)
        assertEquals(setOf("device", "device2"), history.map { ShelfSyncRetention.revision(it).third }.toSet())
        // 元数据真实变化后，这本书可以重新进入同步范围。
        val updated = book("aa-a", 40)
        store.data["reamicro/manifests/aa/$shardVersion.cbor"] = encode(Fields(mapOf("b" to Items(listOf(updated)))))
        val latest = map(decode(store.data.getValue(rootKey)))
        val changed = encode(Fields(latest + mapOf(
            "s" to Items(listOf(text("aa"), text("bb"))),
            "sp" to Fields(map(latest["sp"]) + ("aa" to text(shardVersion))),
        )))
        ShelfSyncRetention(store, 1).put(rootKey, changed, null, "application/cbor", store.get(rootKey)!!.headers["ETag"])
        assertEquals(setOf("aa-a"), ids(store))
    }
    @Test fun unknownVersionAndMissingShardFailBeforeWriting() {
        val store = seed(book("aa-a", 1), book("bb-b", 2))
        val root = map(decode(store.data.getValue(rootKey)))
        store.data[rootKey] = encode(Fields(root + ("v" to number(2))))
        assertThrows(IllegalArgumentException::class.java) { ShelfSyncRetention(store, 1).get(rootKey) }
        assertTrue(store.writes.isEmpty())
        store.data[rootKey] = encode(Fields(root))
        store.data.remove(store.data.keys.first { "/manifests/aa/" in it })
        assertThrows(IllegalStateException::class.java) { ShelfSyncRetention(store, 1).get(rootKey) }
        assertTrue(store.writes.isEmpty())
    }
    @Test fun tiesAreDeterministicAndSwitchesAreIndependent() {
        val store = seed(book("bb-b", 2), book("aa-a", 2))
        ShelfSyncRetention(store, 1).get(rootKey)
        assertEquals(setOf("aa-a"), ids(store))
        assertTrue(ModuleSettingsSnapshot(cloudSyncExtensionEnabled = true, cloudGitHubSyncEnabled = false).canRunWebDavSync)
        assertFalse(ModuleSettingsSnapshot(cloudSyncExtensionEnabled = true, cloudGitHubSyncEnabled = false).canRunGitHubSync)
        assertTrue(ModuleSettingsSnapshot(cloudSyncExtensionEnabled = false, cloudGitHubSyncEnabled = true).hasShelfSync)
        assertFalse(ModuleSettingsSnapshot(moduleEnabled = false, cloudGitHubSyncEnabled = true).canRunGitHubSync)
    }
    @Test fun indefiniteCborAndDuplicateKeys() {
        val value = decode(byteArrayOf(0xbf.toByte(), 0x61, 0x62, 0x9f.toByte(), 1, 2, 0xff.toByte(), 0xff.toByte()))
        assertEquals(listOf(1L, 2L), list(map(value)["b"]).map { long(it) })
        assertThrows(IllegalArgumentException::class.java) {
            decode(byteArrayOf(0xa2.toByte(), 0x61, 0x62, 1, 0x61, 0x62, 2))
        }
    }
}
