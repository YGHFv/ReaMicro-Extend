package com.reamicro.fix.cloud.github

import com.reamicro.fix.cloud.webdav.WebDavHttpResponse
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubSyncTransportTest {
    private class HttpError(val code: Int) : RuntimeException()
    private class Remote {
        val head = "1".repeat(40)
        val tree = "2".repeat(40)
        val old = "3".repeat(40)
        val blob = "4".repeat(40)
        val nextTree = "5".repeat(40)
        val commit = "6".repeat(40)
        var privateRepo = true
        var missing = false
        var stale = false
        val calls = arrayListOf<Pair<String, String>>()
        var publication: JSONObject? = null
        var treeEntry: JSONObject? = null
        fun request(url: String, method: String, body: JSONObject?): WebDavHttpResponse {
            calls += method to url
            val path = url.removePrefix("https://api.github.com")
            val value = when {
                path == "/repos/user/backup" -> JSONObject().put("private", privateRepo).put("node_id", "repo")
                    .put("default_branch", "main").put("permissions", JSONObject().put("push", true))
                path == "/repos/user/backup/git/ref/heads/main" -> JSONObject().put("object", JSONObject().put("sha", head))
                path == "/repos/user/backup/git/commits/$head" -> JSONObject().put("tree", JSONObject().put("sha", tree))
                path.startsWith("/repos/user/backup/contents/") -> {
                    assertTrue(path.endsWith("?ref=$head"))
                    if (missing) return WebDavHttpResponse(404, "{}")
                    JSONObject().put("type", "file").put("sha", old).put("size", 4)
                }
                path == "/repos/user/backup/git/blobs/$old" -> JSONObject().put("sha", old).put("size", 4)
                    .put("encoding", "base64").put("content", Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2, -1)))
                path == "/repos/user/backup/git/blobs" -> {
                    assertEquals("POST", method)
                    assertEquals("base64", body!!.getString("encoding"))
                    JSONObject().put("sha", blob)
                }
                path == "/repos/user/backup/git/trees" -> {
                    assertEquals(tree, body!!.getString("base_tree"))
                    treeEntry = body.getJSONArray("tree").getJSONObject(0)
                    JSONObject().put("sha", nextTree)
                }
                path == "/repos/user/backup/git/commits" -> {
                    assertEquals(head, body!!.getJSONArray("parents").getString(0))
                    JSONObject().put("sha", commit)
                }
                path == "/graphql" -> {
                    publication = body!!.getJSONObject("variables").getJSONObject("input")
                    if (stale) JSONObject("""{"errors":[{"type":"STALE_DATA","message":"Reference changed"}]}""")
                    else JSONObject().put("data", JSONObject().put("updateRefs", JSONObject().put("clientMutationId", commit)))
                }
                else -> error("Unexpected request $method $url")
            }
            return WebDavHttpResponse(200, value.toString())
        }
        fun transport() = GitHubSyncTransport("user/backup", "", ::request) { throw HttpError(it) }
    }

    @Test fun readsBinaryAtFixedCommitAndUsesObjectEtag() {
        val remote = Remote()
        val result = remote.transport().get("reamicro/书架.cbor")!!
        assertArrayEquals(byteArrayOf(0, 1, 2, -1), result.bodyBytes)
        assertEquals("\"${remote.old}\"", result.headers["ETag"])
        assertTrue(remote.calls.any { "%E4%B9%A6%E6%9E%B6.cbor" in it.second })
    }
    @Test fun existingObjectRejectsCreateWithoutAnyWrite() {
        val remote = Remote()
        val error = assertThrows(HttpError::class.java) {
            remote.transport().put("reamicro/manifest", byteArrayOf(1), null, "application/octet-stream", "*")
        }
        assertEquals(412, error.code)
        assertTrue(remote.calls.all { it.first == "GET" })
    }
    @Test fun staleObjectRejectsOverwriteWithoutAnyWrite() {
        val remote = Remote()
        val error = assertThrows(HttpError::class.java) {
            remote.transport().put("reamicro/manifest", byteArrayOf(1), null, "application/octet-stream", "\"${remote.head}\"")
        }
        assertEquals(412, error.code)
        assertTrue(remote.calls.all { it.first == "GET" })
    }
    @Test fun publicationAtomicallyChecksBranchHeadAndReportsConflict() {
        val remote = Remote().apply { stale = true }
        val error = assertThrows(HttpError::class.java) {
            remote.transport().put("reamicro/manifest", byteArrayOf(1), null, "application/octet-stream", "\"${remote.old}\"")
        }
        assertEquals(412, error.code)
        val update = remote.publication!!.getJSONArray("refUpdates").getJSONObject(0)
        assertEquals(remote.head, update.getString("beforeOid"))
        assertEquals(remote.commit, update.getString("afterOid"))
        assertFalse(update.getBoolean("force"))
    }
    @Test fun privateRepositoryRequiredBeforeReadingOrWritingObjects() {
        val remote = Remote().apply { privateRepo = false }
        assertThrows(IllegalArgumentException::class.java) { remote.transport().verify() }
        assertEquals(1, remote.calls.size)
    }
    @Test fun createsMissingObjectAndMissingDeleteDoesNotPublish() {
        val remote = Remote().apply { missing = true }
        remote.transport().delete("reamicro/missing")
        assertNull(remote.publication)
        remote.transport().put("reamicro/new", byteArrayOf(1), null, "application/octet-stream", "*")
        assertNotNull(remote.publication)
    }
    @Test fun deletionOnlyRemovesRequestedPathAndChecksBranchVersion() {
        val remote = Remote()
        remote.transport().delete("reamicro/deleted.epub")
        assertEquals("reamicro/deleted.epub", remote.treeEntry!!.getString("path"))
        assertTrue(remote.treeEntry!!.isNull("sha"))
        assertEquals(remote.head, remote.publication!!.getJSONArray("refUpdates").getJSONObject(0).getString("beforeOid"))
    }
    @Test fun repositoryAndBranchRejectUnsafeValues() {
        assertEquals("user/backup", GitHubSyncProtocol.repository("https://github.com/user/backup.git"))
        for (value in listOf("https://github.com.evil/user/backup", "https://github.com/user/backup?token=secret", "user/../backup"))
            assertThrows(IllegalArgumentException::class.java) { GitHubSyncProtocol.repository(value) }
        for (value in listOf("../main", "main.lock", "a//b", "a@{b", "a\nb"))
            assertThrows(IllegalArgumentException::class.java) { GitHubSyncProtocol.branch(value) }
        assertEquals("", GitHubSyncProtocol.branch(""))
        assertEquals("sync/books", GitHubSyncProtocol.branch("sync/books"))
    }
}
