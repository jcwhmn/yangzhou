package yangzhou.api.github

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** 真实现:java.net.http + Jackson 手写,不加依赖(spec 定案)。 */
@Component
class RealGithubGateway(private val mapper: ObjectMapper) : GithubGateway {

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun get(repo: String, path: String, token: String): String {
        val request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/$repo$path"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 300) throw GithubApiException(response.statusCode(), "GitHub API ${response.statusCode()} on $path")
        return response.body()
    }

    // ponytail: 只取第一页(per_page=100/50)——团队仓库量级足够,翻页等真实需要再加
    override fun listBranches(repo: String, token: String): List<String> =
        mapper.readTree(get(repo, "/branches?per_page=100", token))
            .map { it.get("name").asString() }

    override fun listPullRequests(repo: String, token: String): List<GithubPr> =
        mapper.readTree(get(repo, "/pulls?state=all&sort=updated&direction=desc&per_page=50", token))
            .map { pr ->
                GithubPr(
                    number = pr.get("number").asInt(),
                    headRef = pr.get("head").get("ref").asString(),
                    state = when {
                        pr.get("merged").asBoolean() -> "merged"
                        pr.get("state").asString() == "open" -> "open"
                        else -> "closed"
                    },
                    url = pr.path("html_url").asString(null),
                )
            }
}
