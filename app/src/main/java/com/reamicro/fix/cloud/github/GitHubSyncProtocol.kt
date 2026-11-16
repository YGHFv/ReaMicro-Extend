package com.reamicro.fix.cloud.github

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** 固定官方 API；仓库地址和对象路径均不能携带凭据或越过同步根目录。 */
internal object GitHubSyncProtocol {
    const val BUCKET = "reamicro-github-sync-v1"
    const val API = "https://api.github.com"
    // 原生 HTTP 元数据读取上限为 64 MiB，给 Base64 与 JSON 留出空间。
    const val MAX_FILE_BYTES = 40 * 1024 * 1024
    private val id = Regex("[0-9a-f]{40}")

    fun repository(value: String): String {
        val text = value.trim()
        val path = if (text.startsWith("https://")) {
            val uri = URI(text)
            require(uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
                uri.query == null && uri.fragment == null) { "请输入 GitHub 私有仓库地址或 所有者/仓库" }
            uri.path.trim('/')
        } else text.trimEnd('/')
        val parts = path.removeSuffix(".git").split('/')
        require(parts.size == 2 && parts[0].matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")) &&
            parts[1].matches(Regex("[A-Za-z0-9_.-]+")) && parts[1] !in setOf(".", "..")) {
            "仓库格式应为 所有者/仓库，例如 username/reamicro-backup"
        }
        return parts.joinToString("/")
    }

    fun branch(value: String): String = value.trim().also { branch ->
        require(branch.isEmpty() || (
            branch.none { it <= ' ' || it in "~^:?*[\\" || it == '\u007f' } &&
                !branch.contains("..") && !branch.contains("@{") && branch != "@" &&
                branch.split('/').none { it.isBlank() || it.startsWith('.') || it.endsWith(".lock") } &&
                !branch.endsWith('.')
            )) { "请输入有效的 Git 分支名；留空使用仓库默认分支" }
    }

    fun token(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.all { char -> char in '!'..'~' }) { "请输入有效的 GitHub Token" }
    }

    fun path(key: String): String {
        require(key.isNotBlank() && !key.startsWith('/') && '\\' !in key &&
            key.none { it < ' ' || it == '\u007f' } &&
            key.split('/').none { it.isBlank() || it in setOf(".", "..") }) { "无效的 GitHub 同步对象路径" }
        return key
    }

    fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    fun oid(value: String): String = value.also { require(id.matches(it)) { "GitHub 返回了无效的 Git 对象版本" } }
    fun etag(value: String): String = "\"${oid(value)}\""
    fun expected(condition: String?, head: String): String = when (condition) {
        null, "*" -> oid(head)
        else -> {
            require(condition.length == 42 && condition.first() == '"' && condition.last() == '"') {
                "GitHub 同步版本条件无效"
            }
            oid(condition.substring(1, 41))
        }
    }

    /** beforeOid 在服务器端原子校验，不能用 REST 的非强制快进替代版本条件。 */
    fun publication(repositoryId: String, branch: String, before: String, after: String): JSONObject {
        val update = JSONObject().put("name", "refs/heads/${branch(branch)}")
            .put("beforeOid", oid(before)).put("afterOid", oid(after)).put("force", false)
        val input = JSONObject().put("repositoryId", repositoryId)
            .put("refUpdates", JSONArray().put(update)).put("clientMutationId", after)
        return JSONObject().put("query",
            "mutation ReaMicroSyncPublish(\$input: UpdateRefsInput!) { updateRefs(input: \$input) { clientMutationId } }")
            .put("variables", JSONObject().put("input", input))
    }
}
