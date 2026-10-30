package yangzhou.api.business.github

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.format.DateTimeFormatter

/** 真实现:java.net.http + Jackson 手写,不加依赖(spec 定案)。 */
@Component
class GithubAdapter(
    private val mapper: ObjectMapper,
) : VcsGateway {
    private val http: HttpClient = HttpClient.newHttpClient()

    private fun get(
        repo: String,
        path: String,
        token: String,
    ): String {
        val request =
            HttpRequest
                .newBuilder(URI.create("https://api.github.com/repos/$repo$path"))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 300) throw VcsApiException(response.statusCode(), "GitHub API ${response.statusCode()} on $path")
        return response.body()
    }

    // ponytail: 只取第一页(per_page=100/50)——团队仓库量级足够,翻页等真实需要再加
    override fun listBranches(
        repo: String,
        token: String,
    ): List<String> =
        mapper
            .readTree(get(repo, "/branches?per_page=100", token))
            .map { it.get("name").asString() }

    override fun listPullRequests(
        repo: String,
        token: String,
    ): List<RepoPr> =
        mapper
            .readTree(get(repo, "/pulls?state=all&sort=updated&direction=desc&per_page=50", token))
            .map { toRepoPr(it) }

    // head=owner:branch 平台侧过滤,不受首页窗口限制(老 PR 也能回溯,YPJ-52)
    override fun findPrByHead(
        repo: String,
        headRef: String,
        token: String,
    ): RepoPr? {
        val owner = repo.substringBefore('/')
        return mapper
            .readTree(get(repo, "/pulls?state=all&head=$owner:$headRef&per_page=1", token))
            .map { toRepoPr(it) }
            .firstOrNull()
    }

    // ponytail: 只取第一页 per_page=20——按需查看量级足够,翻页等真实需要再加
    override fun listCommits(
        repo: String,
        sha: String,
        since: Instant?,
        token: String,
    ): List<RepoCommit> =
        try {
            val sinceQ = if (since != null) "&since=" + DateTimeFormatter.ISO_INSTANT.format(since) else ""
            mapper
                .readTree(get(repo, "/commits?sha=$sha$sinceQ&per_page=20", token))
                .map { parseCommit(it) }
        } catch (e: VcsApiException) {
            if (e.status == 404) emptyList() else throw e // ref 已删 → 视为无提交
        }

    // ponytail: pulls commits 无 since 参数,过滤交给调用方;第一页 per_page=100 同量级假设
    override fun listPullRequestCommits(
        repo: String,
        number: Int,
        token: String,
    ): List<RepoCommit> =
        try {
            mapper
                .readTree(get(repo, "/pulls/$number/commits?per_page=100", token))
                .map { parseCommit(it) }
        } catch (e: VcsApiException) {
            if (e.status == 404) emptyList() else throw e
        }

    private fun parseCommit(c: tools.jackson.databind.JsonNode) =
        RepoCommit(
            sha = c.get("sha").asString(),
            message =
                c
                    .path("commit")
                    .path("message")
                    .asString()
                    .lineSequence()
                    .firstOrNull()
                    .orEmpty(),
            author =
                c.path("author").path("login").asString(null)
                    ?: c
                        .path("commit")
                        .path("author")
                        .path("name")
                        .asString(null),
            date =
                c
                    .path("commit")
                    .path("author")
                    .path("date")
                    .asString(null),
            url = c.path("html_url").asString(null),
        )

    companion object {
        /** 列表载荷无 merged 布尔,只有 merged_at(dogfood JCW-113 抓到的 NPE):非空即已合并。 */
        fun toRepoPr(pr: tools.jackson.databind.JsonNode): RepoPr =
            RepoPr(
                number = pr.get("number").asInt(),
                headRef = pr.get("head").get("ref").asString(),
                state =
                    when {
                        !pr.path("merged_at").isNull() -> "merged"
                        pr.get("state").asString() == "open" -> "open"
                        else -> "closed"
                    },
                url = pr.path("html_url").asString(null),
            )
    }

    override fun createBranch(
        repo: String,
        branch: String,
        fromBranch: String,
        token: String,
    ) {
        val sha =
            mapper
                .readTree(get(repo, "/git/ref/heads/$fromBranch", token))
                .path("object")
                .path("sha")
                .asString()
        val body = mapper.writeValueAsString(mapOf("ref" to "refs/heads/$branch", "sha" to sha))
        val request =
            HttpRequest
                .newBuilder(URI.create("https://api.github.com/repos/$repo/git/refs"))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 300) throw VcsApiException(response.statusCode(), "GitHub API ${response.statusCode()} on git/refs")
    }
}
