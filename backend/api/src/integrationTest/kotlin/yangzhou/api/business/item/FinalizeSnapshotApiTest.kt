package yangzhou.api.business.item

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

class FakeFinalizeGateway : VcsGateway {
    var failWith: VcsApiException? = null
    val commits = mutableMapOf<String, List<RepoCommit>>() // key = repo@ref

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
class FakeFinalizeGatewayConfig {
    @Bean
    @Primary
    fun fakeFinalizeGateway(): FakeFinalizeGateway = FakeFinalizeGateway()
}

/**
 * YPJ-184(spec 0125):Done 联动快照 + 读时缓存——数据自持,离分支离 GitHub 均可得。
 * 集成黑盒:状态迁移(进 FINAL)→ 快照落库;建议经 GET time-entry-suggestions。
 */
@Import(FakeFinalizeGatewayConfig::class)
class FinalizeSnapshotApiTest : AbstractApiTest() {
    @Autowired
    lateinit var fake: FakeFinalizeGateway

    private lateinit var authed: RestTestClient

    private val ref = "cwjiang/YPJ-8-x"

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
        ref: String = this.ref,
    ) {
        jdbc.update(
            "insert into item_git_ref (object_id, item_object_id, kind, repo, ref, created_at) " +
                "values (md5(random()::text || clock_timestamp()::text)::uuid, (select object_id from item where object_id = ?::uuid), 'branch', 'octo/r', ?, '2026-01-01T00:00:00Z')",
            itemId,
            ref,
        )
    }

    private fun moveItem(
        itemId: String,
        statusName: String,
    ) {
        // 状态 id 从项目详情拉(三态 workflow:To Do / In Progress / Done)
        val project =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/CHE")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        val sid = project["statuses"].first { it["name"].asText() == statusName }["statusId"].asText()
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("statusItemId" to sid))
            .exchange()
            .expectStatus()
            .isOk()
    }

    private fun createAndFinalize(
        commitDates: List<String>,
        pat: Boolean = true,
    ): String {
        val itemId = setup(pat)
        seedBranchRef(itemId)
        assignMe(itemId)
        fake.commits["octo/r@$ref"] = commitDates.map { RepoCommit(it, "m", "me", it, null) }
        moveItem(itemId, "Done")
        return itemId
    }

    private fun assignMe(itemId: String) {
        val meId =
            json
                .readTree(
                    authed
                        .get()
                        .uri("/api/members")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(String::class.java)
                        .returnResult()
                        .responseBody!!,
                ).first { !it["virtual"].asBoolean() }["memberId"]
                .asText()
        authed
            .put()
            .uri("/api/items/$itemId/assignee")
            .body(mapOf("assigneeItemId" to meId))
            .exchange()
            .expectStatus()
            .isOk()
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

    private fun snapshotRows(itemId: String): List<Map<String, Any?>> = jdbc.queryForList("select captured_by, commit_count, via, ref from item_commit_snapshot where item_object_id = ?::uuid", itemId)

    @Test
    fun `Done 联动快照——删分支后建议仍出段`() {
        val itemId =
            createAndFinalize(
                listOf("2026-11-10T09:00:00Z", "2026-11-13T18:00:00Z"),
            )

        // 快照行落库,锚 = finalize
        val rows = snapshotRows(itemId)
        assertEquals(1, rows.size)
        assertEquals("finalize", rows[0]["captured_by"])
        assertEquals(2, rows[0]["commit_count"])

        // 模拟删分支(裸 git 删):live 拉空 → 快照兜底仍出段
        fake.commits.remove("octo/r@$ref")
        val res = body(getSuggestions(itemId))
        assertEquals(1, res.size())
        assertEquals("cwjiang/YPJ-8-x", res[0]["ref"].asText())
        assertEquals("2026-11-10T09:00:00Z", res[0]["startedAt"].asText())
        assertEquals("2026-11-13T18:00:00Z", res[0]["endedAt"].asText())
        assertTrue(res[0]["note"].asText().contains("2 commits"))
    }

    @Test
    fun `单 commit 分支 Done 后建议出段——commit 到完成时刻`() {
        val itemId = createAndFinalize(listOf("2026-11-13T09:00:00Z"))

        val res = body(getSuggestions(itemId))
        assertEquals(1, res.size())
        assertEquals("2026-11-13T09:00:00Z", res[0]["startedAt"].asText())
        assertTrue(res[0]["endedAt"].asText().startsWith("2026-")) // FINAL 锚时刻(真时钟)
        assertTrue(res[0]["note"].asText().contains("单提交"))
        // 回归防线(YPJ-184 实测缺陷):读时缓存不得冲掉 FINAL 锚
        assertEquals("finalize", snapshotRows(itemId)[0]["captured_by"])
    }

    @Test
    fun `读时缓存——不经 Done 删分支后多 commit 段仍出`() {
        val itemId = setup()
        seedBranchRef(itemId)
        fake.commits["octo/r@$ref"] =
            listOf(
                RepoCommit("c1", "首", "me", "2026-11-10T09:00:00Z", null),
                RepoCommit("c2", "末", "me", "2026-11-13T18:00:00Z", null),
            )
        assertEquals(1, body(getSuggestions(itemId)).size()) // 首读成功 → 快照 read 落库

        fake.commits.remove("octo/r@$ref") // 分支没了,且从未 Done(无 finalize 锚)
        val res = body(getSuggestions(itemId))
        assertEquals(1, res.size()) // 读时缓存兜底
        assertEquals("2026-11-13T18:00:00Z", res[0]["endedAt"].asText())
    }

    @Test
    fun `best-effort——无 PAT 时 Done 照常,快照无行`() {
        val itemId = setup(pat = false)
        seedBranchRef(itemId)
        assignMe(itemId)
        moveItem(itemId, "Done") // 不 500:快照早退,状态迁移成功

        assertEquals(0, snapshotRows(itemId).size)
    }

    @Test
    fun `幂等——重开再 Done 不产生重复行`() {
        val itemId =
            createAndFinalize(
                listOf("2026-11-10T09:00:00Z", "2026-11-13T18:00:00Z"),
            )
        moveItem(itemId, "In Progress") // 重开
        moveItem(itemId, "Done") // 再完成

        assertEquals(1, snapshotRows(itemId).size) // upsert 同行,不重复
        assertEquals(1, body(getSuggestions(itemId)).size()) // 建议不重复出段
    }
}
