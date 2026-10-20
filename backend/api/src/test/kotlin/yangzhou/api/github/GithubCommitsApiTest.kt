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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeCommitGateway : VcsGateway {
    var failWith: VcsApiException? = null
    var ioError: java.io.IOException? = null
    val commits = mutableMapOf<String, List<RepoCommit>>() // key = repo@sha
    val prCommits = mutableMapOf<Int, List<RepoCommit>>() // key = PR 号
    var prs: Map<String, List<RepoPr>> = emptyMap() // key = repo
    var lastSince: Instant? = null

    override fun listBranches(repo: String, token: String): List<String> = emptyList()
    override fun listPullRequests(repo: String, token: String): List<RepoPr> = prs[repo].orEmpty()
    override fun createBranch(repo: String, branch: String, fromBranch: String, token: String) {}

    // 故意不 honor since(模拟 GitHub 不过滤)——服务侧兜底过滤由 YPJ-42 测试验证
    override fun listCommits(repo: String, sha: String, since: Instant?, token: String): List<RepoCommit> {
        ioError?.let { throw it }
        failWith?.let { throw it }
        lastSince = since
        return commits["$repo@$sha"].orEmpty()
    }

    override fun listPullRequestCommits(repo: String, number: Int, token: String): List<RepoCommit> {
        failWith?.let { throw it }
        return prCommits[number].orEmpty()
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


    private lateinit var authed: RestTestClient

    /** PAT + CHE 项目 + 挂载 octo/r + item CHE-1;返回 itemId。 */
    private fun setup(pat: Boolean = true): String {
        fake.failWith = null // 用例序无关:失败注入不复位会泄漏到后续用例
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
            "insert into item_git_ref (object_id, item_id, kind, repo, ref, created_at) " +
                "values (md5(random()::text || clock_timestamp()::text)::uuid, (select id from item where object_id = ?::uuid), 'branch', 'octo/r', ?, '2026-10-01T00:00:00Z')",
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
            RepoCommit("abc1234full", "YPJ-7: 加功能", "me", "2026-10-06T12:00:00Z", "https://github.com/octo/r/commit/abc1234full"),
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
    fun `分支创建前的提交被兜底过滤(fake 忽略 since 模拟 GitHub 不过滤)`() {
        val itemId = setup()
        seedBranchRef(itemId) // created_at = 2026-10-01
        fake.commits["octo/r@CHE-1-x"] = listOf(
            RepoCommit("old111full", "YPJ-7: 历史提交", "me", "2026-09-20T12:00:00Z", null),
            RepoCommit("new222full", "YPJ-42: 新提交", "me", "2026-10-06T12:00:00Z", null),
        )

        val res = body(getCommits(itemId))

        assertEquals(1, res[0]["commits"].size())
        assertEquals("new222full", res[0]["commits"][0]["sha"].asText())
        assertEquals(java.time.Instant.parse("2026-10-01T00:00:00Z"), fake.lastSince)
    }

    @Test
    fun `分支已删——降级按 head 分支名匹配 PR 取提交(YPJ-69)`() {
        val itemId = setup()
        seedBranchRef(itemId) // created_at = 2026-10-01
        fake.commits.clear() // 分支已删:listCommits 空 → 走 PR
        fake.prs = mapOf("octo/r" to listOf(RepoPr(7, "CHE-1-x", "merged")))
        fake.prCommits[7] = listOf(
            RepoCommit("old333full", "基线提交", "me", "2026-09-20T12:00:00Z", null),
            RepoCommit("new444full", "YPJ-69: 降级取回", "me", "2026-10-06T12:00:00Z", "https://github.com/octo/r/pull/7/commits/new444full"),
        )

        val res = body(getCommits(itemId))

        assertEquals(1, res.size())
        assertEquals(true, res[0]["viaPr"].asBoolean())
        assertEquals(7, res[0]["prNumber"].asInt())
        assertEquals(1, res[0]["commits"].size()) // PR 路径同样按 branch createdAt 过滤
        assertEquals("new444full", res[0]["commits"][0]["sha"].asText())
    }

    @Test
    fun `分支与 PR 均无提交——空数组不报错`() {
        val itemId = setup()
        seedBranchRef(itemId)
        fake.commits.clear() // fake 不随 cleanDatabase 复位;别依赖用例序
        fake.prs = mapOf("octo/r" to listOf(RepoPr(8, "other-branch", "open")))

        val res = body(getCommits(itemId))

        assertEquals(1, res.size())
        assertEquals(false, res[0]["viaPr"].asBoolean())
        assertEquals(0, res[0]["commits"].size())
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
        fake.failWith = VcsApiException(401, "unauthorized")
        val res = authed.get().uri("/api/items/$itemId/commits").exchange()
            .expectStatus().isBadRequest()
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(res.contains("401"))
    }

    @Test
    fun `GitHub 网络错误——400 可重试提示而非 500(YPJ-52 实测)`() {
        val itemId = setup()
        seedBranchRef(itemId)
        fake.ioError = java.io.IOException("connection reset")
        val res = authed.get().uri("/api/items/$itemId/commits").exchange()
            .expectStatus().isBadRequest()
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(res.contains("暂时不可达"))
    }

    @Test
    fun `YPJ-39 共享分支——两票各自 commits 均可拉`() {
        val itemId1 = setup()
        seedBranchRef(itemId1) // 先入册 CHE-1-x,再对另一票补挂
        val itemId2 = createItem(authed, "CHE", "second")["itemId"].asText()
        authed.post().uri("/api/items/$itemId2/git-refs")
            .body(mapOf("repo" to "octo/r", "ref" to "CHE-1-x"))
            .exchange().expectStatus().isOk()
                fake.commits["octo/r@CHE-1-x"] = listOf(
            RepoCommit("abc123full", "YPJ-7: 加功能", "me", "2026-10-06T12:00:00Z", "https://github.com/octo/r/commit/abc123full"),
        )

        for (id in listOf(itemId1, itemId2)) {
            val res = json.readTree(
                authed.get().uri("/api/items/$id/commits").exchange()
                    .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
            )
            assertEquals(1, res.size())
            assertEquals(1, res[0]["commits"].size())
            assertEquals("abc123full", res[0]["commits"][0]["sha"].asText())
        }
    }

}
