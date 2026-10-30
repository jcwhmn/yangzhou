package yangzhou.api.business.time

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import yangzhou.api.business.github.RepoCommit
import yangzhou.api.business.github.RepoPr
import yangzhou.api.business.github.VcsApiException
import yangzhou.api.business.github.VcsGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeSuggestGateway : VcsGateway {
    var failWith: VcsApiException? = null
    val commits = mutableMapOf<String, List<RepoCommit>>()

    override fun listBranches(
        repo: String,
        token: String,
    ): List<String> = emptyList()

    override fun listPullRequests(
        repo: String,
        token: String,
    ): List<RepoPr> = emptyList()

    override fun createBranch(
        repo: String,
        branch: String,
        fromBranch: String,
        token: String,
    ) {}

    override fun listCommits(
        repo: String,
        sha: String,
        since: java.time.Instant?,
        token: String,
    ): List<RepoCommit> {
        failWith?.let { throw it }
        return commits["$repo@$sha"].orEmpty()
    }

    override fun listPullRequestCommits(
        repo: String,
        number: Int,
        token: String,
    ): List<RepoCommit> = emptyList()
}

@TestConfiguration(proxyBeanMethods = false)
class FakeSuggestGatewayConfig {
    @Bean
    @Primary
    fun fakeSuggestGateway(): FakeSuggestGateway = FakeSuggestGateway()
}

/** V16-3 工时建议:分支 commit 时间轴 → 建议工时段草稿;只出草稿不落账,空态/无 PAT 400 可操作。 */
@Import(FakeSuggestGatewayConfig::class)
class TimeSuggestionApiTest : AbstractApiTest() {
    @Autowired
    lateinit var fake: FakeSuggestGateway

    private lateinit var authed: RestTestClient

    /** PAT + CHE 项目 + 挂载 octo/r + item CHE-1;返回 itemId。 */
    private fun setup(pat: Boolean = true): String {
        authed = bootstrapAndAuth()
        if (pat) {
            authed
                .put()
                .uri("/api/workspace/github-token")
                .body(mapOf("token" to "test-token"))
                .exchange()
                .expectStatus()
                .isOk()
        }
        createProject(authed, "CHE")
        authed
            .post()
            .uri("/api/projects/CHE/repos")
            .body(mapOf("repo" to "octo/r"))
            .exchange()
            .expectStatus()
            .isCreated()
        return createItem(authed, "CHE", "x")["itemId"].asText() // CHE-1
    }

    private fun seedBranchRef(
        itemId: String,
        ref: String = "cwjiang/YPJ-8-x",
    ) {
        jdbc.update(
            "insert into item_git_ref (object_id, item_object_id, kind, repo, ref, created_at) " +
                "values (md5(random()::text || clock_timestamp()::text)::uuid, (select object_id from item where object_id = ?::uuid), 'branch', 'octo/r', ?, '2026-01-01T00:00:00Z')",
            itemId,
            ref,
        )
    }

    private fun getSuggestions(itemId: String): RestTestClient.ResponseSpec = authed.get().uri("/api/items/$itemId/time-entry-suggestions").exchange()

    private fun body(spec: RestTestClient.ResponseSpec): JsonNode = json.readTree(
        spec
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    @Test
    fun `建议——首末 commit 成段——乱序日期取时间首尾`() {
        val itemId = setup()
        seedBranchRef(itemId)
        fake.commits["octo/r@cwjiang/YPJ-8-x"] =
            listOf(
                RepoCommit("c2", "末次提交", "me", "2026-10-07T18:00:00Z", null),
                RepoCommit("c1", "首次提交", "me", "2026-10-07T09:30:00Z", null),
                RepoCommit("c3", "中间提交", "me", "2026-10-07T12:00:00Z", null),
            )

        val res = body(getSuggestions(itemId))

        assertEquals(1, res.size())
        assertEquals("octo/r", res[0]["repo"].asText())
        assertEquals("cwjiang/YPJ-8-x", res[0]["ref"].asText())
        assertEquals(3, res[0]["commitCount"].asInt())
        assertEquals("2026-10-07T09:30:00Z", res[0]["startedAt"].asText())
        assertEquals("2026-10-07T18:00:00Z", res[0]["endedAt"].asText())
        assertTrue(res[0]["note"].asText().contains("cwjiang/YPJ-8-x"))
    }

    @Test
    fun `单 commit 不成段——空数组`() {
        val itemId = setup()
        seedBranchRef(itemId)
        fake.commits["octo/r@cwjiang/YPJ-8-x"] =
            listOf(
                RepoCommit("c1", "唯一提交", "me", "2026-10-07T09:30:00Z", null),
            )
        assertEquals(0, body(getSuggestions(itemId)).size())
    }

    @Test
    fun `无关联分支——空数组 200`() {
        val itemId = setup()
        assertEquals(0, body(getSuggestions(itemId)).size())
    }

    @Test
    fun `无 PAT——400 可操作报错`() {
        val itemId = setup(pat = false)
        seedBranchRef(itemId)
        val res =
            getSuggestions(itemId)
                .expectStatus()
                .isBadRequest()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertTrue(res.contains("PAT"))
    }
}
