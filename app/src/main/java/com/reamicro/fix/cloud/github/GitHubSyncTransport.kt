package com.reamicro.fix.cloud.github

import com.reamicro.fix.cloud.webdav.ShelfSyncTransport
import com.reamicro.fix.cloud.webdav.WebDavHttpResponse
import java.io.File
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** 参考 NeriPlayer 的 blob/tree/commit + updateRefs；不依赖 Git 程序或第三方 SDK。 */
internal class GitHubSyncTransport(
    repository: String,
    branch: String,
    private val request: (String, String, JSONObject?) -> WebDavHttpResponse,
    private val onHttpError: (Int) -> Nothing,
) : ShelfSyncTransport {
    private val repository = GitHubSyncProtocol.repository(repository)
    private val configuredBranch = GitHubSyncProtocol.branch(branch)
    private val root = "${GitHubSyncProtocol.API}/repos/${this.repository}"
    private data class Repository(val node: String, val branch: String)
    private data class Head(val repository: Repository, val commit: String, val tree: String)
    private data class ObjectInfo(val sha: String, val size: Long)

    private fun json(response: WebDavHttpResponse): JSONObject {
        checkStatus(response.code)
        return JSONObject(requireNotNull(response.bodyString) { "GitHub 返回了空响应" })
    }
    private fun checkStatus(code: Int) {
        if (code !in 200..299) onHttpError(code)
    }
    private fun readRepository(): Repository {
        val value = json(request(root, "GET", null))
        require(value.getBoolean("private")) { "GitHub 同步仅支持私有仓库，请先把仓库设为私有" }
        require(value.optJSONObject("permissions")?.optBoolean("push") == true) {
            "Token 没有仓库写入权限，请授权 Contents 读写或 repo 权限"
        }
        val branch = configuredBranch.ifEmpty { GitHubSyncProtocol.branch(value.getString("default_branch")) }
        require(branch.isNotEmpty()) { "GitHub 仓库没有默认分支，请先创建一次提交" }
        return Repository(value.getString("node_id"), branch)
    }
    private fun head(): Head {
        // 每次操作确认仓库仍是私有，且用固定提交读取正文，避免元数据与正文串版本。
        val repo = readRepository()
        val ref = request("$root/git/ref/heads/${GitHubSyncProtocol.encode(repo.branch)}", "GET", null)
        if (ref.code == 404 || ref.code == 409) error("GitHub 分支不存在或仓库为空，请先创建一次提交")
        val commit = GitHubSyncProtocol.oid(json(ref).getJSONObject("object").getString("sha"))
        val tree = GitHubSyncProtocol.oid(json(request("$root/git/commits/$commit", "GET", null))
            .getJSONObject("tree").getString("sha"))
        return Head(repo, commit, tree)
    }
    private fun info(key: String, head: Head): ObjectInfo? {
        val path = GitHubSyncProtocol.path(key).split('/').joinToString("/") { GitHubSyncProtocol.encode(it) }
        val response = request("$root/contents/$path?ref=${head.commit}", "GET", null)
        if (response.code == 404) return null
        val value = json(response)
        require(value.getString("type") == "file") { "GitHub 同步路径被目录或符号链接占用" }
        return ObjectInfo(GitHubSyncProtocol.oid(value.getString("sha")), value.getLong("size"))
    }
    private fun content(info: ObjectInfo): WebDavHttpResponse {
        require(info.size in 0..GitHubSyncProtocol.MAX_FILE_BYTES.toLong()) {
            "GitHub 单个同步文件不能超过 40 MiB，请使用 WebDAV 同步较大的书籍"
        }
        val blob = json(request("$root/git/blobs/${info.sha}", "GET", null))
        require(blob.getString("encoding") == "base64" && blob.getString("sha") == info.sha) { "GitHub 正文版本不一致" }
        val bytes = Base64.getDecoder().decode(blob.getString("content").replace("\n", "").replace("\r", ""))
        require(bytes.size.toLong() == info.size && blob.getLong("size") == info.size) { "GitHub 正文大小不一致" }
        return WebDavHttpResponse(200, null, mapOf("ETag" to GitHubSyncProtocol.etag(info.sha),
            "Content-Length" to info.size.toString()), bytes)
    }
    override fun get(key: String): WebDavHttpResponse? {
        val head = head()
        return info(key, head)?.let { content(it) }
    }
    override fun head(key: String): WebDavHttpResponse? {
        val head = head()
        return info(key, head)?.let {
            WebDavHttpResponse(200, null, mapOf("ETag" to GitHubSyncProtocol.etag(it.sha), "Content-Length" to it.size.toString()))
        }
    }
    override fun put(key: String, bytes: ByteArray?, file: File?, mime: String, condition: String?) {
        GitHubSyncProtocol.path(key)
        require(file == null || file.length() <= GitHubSyncProtocol.MAX_FILE_BYTES) {
            "GitHub 单个同步文件不能超过 40 MiB，请使用 WebDAV 同步较大的书籍"
        }
        val data = bytes ?: requireNotNull(file).readBytes()
        require(data.size <= GitHubSyncProtocol.MAX_FILE_BYTES) { "GitHub 单个同步文件不能超过 40 MiB" }
        val head = head()
        val old = info(key, head)
        // 使用对象 SHA 作为 ETag，自身上传其他书籍不应使尚未更新的清单版本失效。
        if (condition == "*" && old != null || condition != null && condition != "*" &&
            old?.sha != GitHubSyncProtocol.expected(condition, head.commit)) onHttpError(412)
        val blob = json(request("$root/git/blobs", "POST", JSONObject()
            .put("content", Base64.getEncoder().encodeToString(data)).put("encoding", "base64")))
        val sha = GitHubSyncProtocol.oid(blob.getString("sha"))
        if (old?.sha == sha) return
        publish(key, sha, head)
    }
    override fun delete(key: String) {
        val head = head()
        if (info(key, head) != null) publish(key, null, head)
    }
    private fun publish(key: String, blob: String?, head: Head) {
        val entry = JSONObject().put("path", GitHubSyncProtocol.path(key)).put("mode", "100644")
            .put("type", "blob").put("sha", blob ?: JSONObject.NULL)
        val tree = json(request("$root/git/trees", "POST",
            JSONObject().put("base_tree", head.tree).put("tree", JSONArray().put(entry))))
        val commit = json(request("$root/git/commits", "POST", JSONObject()
            .put("message", "ReaMicro sync").put("tree", GitHubSyncProtocol.oid(tree.getString("sha")))
            .put("parents", JSONArray().put(head.commit))))
        val after = GitHubSyncProtocol.oid(commit.getString("sha"))
        val result = json(request("${GitHubSyncProtocol.API}/graphql", "POST",
            GitHubSyncProtocol.publication(head.repository.node, head.repository.branch, head.commit, after)))
        val errors = result.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val kinds = (0 until errors.length()).map { errors.getJSONObject(it) }
            if (kinds.any { it.optString("type") == "STALE_DATA" ||
                    it.optJSONObject("extensions")?.optString("code") == "STALE_DATA" }) onHttpError(412)
            if (kinds.any { it.optString("type") == "RATE_LIMITED" ||
                    it.optString("message").contains("rate limit", true) }) onHttpError(429)
            // 部分 GitHub 错误没有 STALE_DATA 类型；回读仅用于分类，不代替原子条件。
            if (head().commit != head.commit) onHttpError(412)
            error("GitHub 无法更新同步分支，请检查 Token 写入权限与分支保护规则")
        }
        require(result.getJSONObject("data").getJSONObject("updateRefs").getString("clientMutationId") == after) {
            "GitHub 同步提交确认不一致"
        }
    }
    override fun download(key: String, output: File) {
        val bytes = get(key)?.bodyBytes ?: onHttpError(404)
        val parent = requireNotNull(output.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "无法创建同步下载目录" }
        val temp = File.createTempFile("github-sync-", ".part", parent)
        try {
            temp.writeBytes(bytes)
            check(temp.renameTo(output)) { "无法替换同步下载文件" }
        } finally { temp.delete() }
    }
    override fun verify() {
        // 保存配置只验证私有仓库、目标分支与权限，不在用户仓库写入测试提交。
        head()
    }
}
