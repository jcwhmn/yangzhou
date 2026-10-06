package yangzhou.api.github

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeCommitGateway : GithubGateway {
    var failWith: GithubApiException? = null
    val commits = mutableMapOf<String, List<GithubCommit>>() // key = repo@sha

    override fun listBranches(repo: String, token: String): List<String> = emptyList()
    override fun listPullRequests(repo: String, token: String): List<GithubPr> = emptyList()
    override fun createBranch(repo: String, branch: String, fromBranch: String, token: String) {}

    override fun listCommits(repo: String, sha: String, token: String): List<GithubCommit> {
        failWith?.let { throw it }
        return commits["$repo@$sha"].orEmpty()
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FakeCommitGatewayConfig {
    @Bean
    @Primary
    fun fakeCommitGateway(): FakeCommitGateway = FakeCommitGateway()
}

/** V16-2:按需拉取 item 分支提交历史——不落库不依赖轮询;无 PAT/无挂载 400 可操作报错。 */
@Import(FakeCommitGatewayConfig::class)
class GithubCommitsApiTest : AbstractApiTest() {

    @Autowired
    lateinit var fake: FakeCommitGateway

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var authed: RestTestClient

    /** PAT + CHE 项目 + 挂载 octo/r + item CHE-1;返回 itemId。 */
    private fun setup(pat: Boolean = true): String {
        authed = bootstrapAndAuth()
        if (pat) {
            authed.put().uri("/api/workspace/github-token")
                .body(mapOf("token" to "test-token"))
                .exchange().expectStatus().isOk()
        }
        createProject(authed, "CHE")
        authed.post().uri("/api/projects/CHE/repos")
            .body(mapOf("repo" to "octo/r"))
            .exchange().expectStatus().isCreated()
        return createItem(authed, "CHE", "x")["itemId"].asText() // CHE-1
    }

    private fun seedBranchRef(itemId: String, ref: String = "CHE-1-x") {
        jdbc.update(
            "insert into item_git_ref (object_id, item_id, kind, repo, ref) " +
                "values (md5(random()::text || clock_timestamp()::text)::uuid, (select id from item where object_id = ?::uuid), 'branch', 'octo/r', ?)",
            itemId, ref,
        )
    }

    private fun getCommits(itemId: String): RestTestClient.ResponseSpec =
        authed.get().uri("/api/items/$itemId/commits").exchange()

    private fun body(spec: RestTestClient.ResponseSpec): JsonNode =
        json.readTree(spec.expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!)

    @Test
    fun `按需拉取——按 branch ref 分组返回提交——不落库`() {
        val itemId = setup()
        seedBranchRef(itemId)
        fake.commits["octo/r@CHE-1-x"] = listOf(
            GithubCommit("abc1234full", "YPJ-7: 加功能", "me", "2026-10-06T12:00:00Z", "https://github.com/octo/r/commit/abc1234full"),
        )

        val res = body(getCommits(itemId))

        assertEquals(1, res.size())
        assertEquals("octo/r", res[0]["repo"].asText())
        assertEquals("CHE-1-x", res[0]["ref"].asText())
        assertEquals(1, res[0]["commits"].size())
        assertEquals("abc1234full", res[0]["commits"][0]["sha"].asText())
        assertEquals("YPJ-7: 加功能", res[0]["commits"][0]["message"].asText())
        assertEquals("me", res[0]["commits"][0]["author"].asText())
        // 只读直查:再次拉取不产生活动留痕
        val acts = json.readTree(
            authed.get().uri("/api/items/$itemId/activity").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        // 只读直查:拉取本身不产生 GitHub 类活动留痕(建 item 的 created 不算)
        assertTrue(acts.toList().none { it["kind"].asText().startsWith("github") })
    }

    @Test
    fun `无关联分支——空数组 200`() {
        val itemId = setup()
        val res = body(getCommits(itemId))
        assertEquals(0, res.size())
    }

    @Test
    fun `无 PAT——400 可操作报错`() {
        val itemId = setup(pat = false)
        seedBranchRef(itemId)
        val res = authed.get().uri("/api/items/$itemId/commits").exchange()
            .expectStatus().isBadRequest()
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(res.contains("PAT"))
    }

    @Test
    fun `GitHub 失败——400 带状态码提示而非 500`() {
        val itemId = setup()
        seedBranchRef(itemId)
        fake.failWith = GithubApiException(401, "unauthorized")
        val res = authed.get().uri("/api/items/$itemId/commits").exchange()
            .expectStatus().isBadRequest()
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(res.contains("401"))
    }
}
