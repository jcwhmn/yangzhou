package yangzhou.api.business.github

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeGithubGateway : VcsGateway {
    var branches: MutableMap<String, List<String>> = mutableMapOf()
    var prs: MutableMap<String, List<RepoPr>> = mutableMapOf()
    var failWith: Exception? = null
    var createError: VcsApiException? = null
    val created = mutableListOf<Triple<String, String, String>>() // repo, branch, from

    override fun listBranches(
        repo: String,
        token: String,
    ): List<String> {
        failWith?.let { throw it }
        return branches[repo].orEmpty()
    }

    override fun listPullRequests(
        repo: String,
        token: String,
    ): List<RepoPr> {
        failWith?.let { throw it }
        return prs[repo].orEmpty()
    }

    override fun createBranch(
        repo: String,
        branch: String,
        fromBranch: String,
        token: String,
    ) {
        failWith?.let { throw it }
        createError?.let { throw it }
        created.add(Triple(repo, branch, fromBranch))
    }

    override fun listCommits(
        repo: String,
        sha: String,
        since: java.time.Instant?,
        token: String,
    ): List<RepoCommit> = emptyList()

    override fun listPullRequestCommits(
        repo: String,
        number: Int,
        token: String,
    ): List<RepoCommit> = emptyList()
}

@TestConfiguration(proxyBeanMethods = false)
class FakeGatewayConfig {
    @Bean
    @Primary
    fun fakeGithubGateway(): FakeGithubGateway = FakeGithubGateway()
}

/** JCW-111:V6-S2 轮询同步黑盒(fake gateway 出网 seam + Testcontainers 真 DB)。 */
@Import(FakeGatewayConfig::class)
class GithubSyncApiTest : AbstractApiTest() {
    @Autowired
    lateinit var fake: FakeGithubGateway

    @org.junit.jupiter.api.BeforeEach
    fun resetFake() {
        fake.branches = mutableMapOf()
        fake.prs = mutableMapOf()
        fake.failWith = null
    }

    @Autowired
    lateinit var syncService: GithubSyncService

    private lateinit var authed: RestTestClient

    /** workspace PAT + CHE 项目 + 挂载 octo/r + 四槽映射(branch→In Progress, pr_open→In Review, pr_merged→Done, closed_unmerged→In Progress)+ item CHE-1。 */
    private fun setup(): String {
        authed = bootstrapAndAuth()
        authed
            .put()
            .uri("/api/workspace/github-token")
            .body(mapOf("token" to "test-token"))
            .exchange()
            .expectStatus()
            .isOk()
        createProject(authed, "CHE")
        authed
            .post()
            .uri("/api/projects/CHE/repos")
            .body(mapOf("repo" to "octo/r"))
            .exchange()
            .expectStatus()
            .isCreated()
        val statuses = statuses0()
        authed
            .put()
            .uri("/api/projects/CHE/workflow-rules")
            .body(
                mapOf(
                    "rules" to
                        listOf(
                            mapOf("eventType" to "branch_created", "statusId" to statuses[1]["statusId"].asText()),
                            mapOf("eventType" to "pr_opened", "statusId" to statuses[2]["statusId"].asText()),
                            mapOf("eventType" to "pr_merged", "statusId" to statuses[4]["statusId"].asText()),
                            mapOf("eventType" to "pr_closed_unmerged", "statusId" to statuses[1]["statusId"].asText()),
                        ),
                ),
            ).exchange()
            .expectStatus()
            .isOk()
        return createItem(authed, "CHE", "x")["itemId"].asText() // CHE-1,To Do
    }

    private fun statuses0(): JsonNode = json.readTree(
        authed
            .get()
            .uri("/api/projects/CHE")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )["statuses"]

    private fun items(): JsonNode = json.readTree(
        authed
            .get()
            .uri("/api/projects/CHE/items")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    private fun statusOf(itemId: String): String = items().first { it["itemId"].asText() == itemId }["status"].asText()

    private fun activities(itemId: String): JsonNode = json.readTree(
        authed
            .get()
            .uri("/api/items/$itemId/activity")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    @Test
    fun `branch_created 命中映射——手工分支同样关联——重复轮询幂等`() {
        val itemId = setup()
        fake.branches["octo/r"] = listOf("CHE-1-feat-x", "cwjiang/CHE-1-hotfix", "unrelated/branch")

        syncService.syncAll()

        assertEquals("In Progress", statusOf(itemId))
        val acts = activities(itemId).filter { it["kind"].asText() == "github_status_changed" }
        assertEquals(1, acts.size) // 两分支同一 item:第二条到位时"只前进"已拦,不重复留痕
        assertTrue(acts[0]["actorMemberId"].isNull)

        // 幂等:再轮询零新增
        syncService.syncAll()
        assertEquals(1, activities(itemId).filter { it["kind"].asText() == "github_status_changed" }.size)
        assertEquals("In Progress", statusOf(itemId))
    }

    @Test
    fun `pr 生命周期 open→merged——final 封口——无关 head 忽略`() {
        val itemId = setup()

        fake.prs["octo/r"] = listOf(RepoPr(1, "CHE-1-x", "open", "https://github.com/octo/r/pull/1"))
        syncService.syncAll()
        assertEquals("In Review", statusOf(itemId))

        fake.prs["octo/r"] = listOf(RepoPr(1, "CHE-1-x", "merged", "https://github.com/octo/r/pull/1"))
        syncService.syncAll()
        assertEquals("Done", statusOf(itemId))

        // 已 final,后续事件封口
        fake.prs["octo/r"] = listOf(RepoPr(1, "CHE-1-x", "closed", "https://github.com/octo/r/pull/1"))
        syncService.syncAll()
        assertEquals("Done", statusOf(itemId))

        // 无关分支的 PR 不产生任何移动
        val before = activities(itemId).filter { it["kind"].asText() == "github_status_changed" }.size
        fake.prs["octo/r"] =
            listOf(
                RepoPr(2, "feature/whatever", "open"),
                RepoPr(1, "CHE-1-x", "merged", "https://github.com/octo/r/pull/1"),
            )
        syncService.syncAll()
        assertEquals(before, activities(itemId).filter { it["kind"].asText() == "github_status_changed" }.size)
    }

    @Test
    fun `默认规则即生效(YPJ-36)——开 PR 自动 In Review,merged 自动 Done,重复轮询幂等`() {
        authed = bootstrapAndAuth()
        authed
            .put()
            .uri("/api/workspace/github-token")
            .body(mapOf("token" to "test-token"))
            .exchange()
            .expectStatus()
            .isOk()
        createProject(authed, "CHE") // 不手配规则,吃默认 3 槽
        authed
            .post()
            .uri("/api/projects/CHE/repos")
            .body(mapOf("repo" to "octo/r"))
            .exchange()
            .expectStatus()
            .isCreated()
        val itemId = createItem(authed, "CHE", "x")["itemId"].asText()

        fake.prs["octo/r"] = listOf(RepoPr(1, "CHE-1-x", "open", "https://github.com/octo/r/pull/1"))
        syncService.syncAll()
        assertEquals("In Review", statusOf(itemId))

        fake.prs["octo/r"] = listOf(RepoPr(1, "CHE-1-x", "merged", "https://github.com/octo/r/pull/1"))
        syncService.syncAll()
        assertEquals("Done", statusOf(itemId))

        // 幂等:再轮询状态不动、不留新痕
        syncService.syncAll()
        assertEquals("Done", statusOf(itemId))
        assertEquals(2, activities(itemId).filter { it["kind"].asText() == "github_status_changed" }.size)
    }

    @Test
    fun `WIP 满跳过留痕——第二个 item 不动`() {
        setup()
        jdbc.update("update status set wip_limit = 1 where name = 'In Progress'")
        val item2 = createItem(authed, "CHE", "y") // CHE-2
        fake.branches["octo/r"] = listOf("CHE-1-a", "CHE-2-b")

        syncService.syncAll()

        val all = items()
        val byTitle = all.associate { it["title"].asText() to it["status"].asText() }
        assertEquals("In Progress", byTitle["x"])
        assertEquals("To Do", byTitle["y"])
        val acts =
            activities(item2["itemId"].asText())
                .filter { it["kind"].asText() == "github_status_changed" }
        assertTrue(acts[0]["newValue"].asText().contains("WIP 满跳过"))
    }

    @Test
    fun `网关失败不炸同步——下一轮继续`() {
        val itemId = setup()

        fake.failWith = VcsApiException(401, "unauthorized")
        syncService.syncAll() // 不抛
        assertEquals("To Do", statusOf(itemId))

        fake.failWith = RuntimeException("network down")
        syncService.syncAll() // 不抛
        assertEquals("To Do", statusOf(itemId))

        fake.failWith = null
        fake.branches["octo/r"] = listOf("CHE-1-recover")
        syncService.syncAll()
        assertEquals("In Progress", statusOf(itemId))
    }

    @Test
    fun `YPJ-39 PR 扇出——head 分支挂多票各落一行,状态更新扇出全部行`() {
        val itemId1 = setup()
        val itemId2 = createItem(authed, "CHE", "second")["itemId"].asText()
        fake.branches["octo/r"] = listOf("CHE-1-feat-x")
        syncService.syncAll()
        authed
            .post()
            .uri("/api/items/$itemId2/git-refs")
            .body(mapOf("repo" to "octo/r", "ref" to "CHE-1-feat-x"))
            .exchange()
            .expectStatus()
            .isOk()

        fake.prs["octo/r"] = listOf(RepoPr(7, "CHE-1-feat-x", "open", "https://github.com/octo/r/pull/7"))
        syncService.syncAll()
        assertEquals(2, prRows().size)
        assertTrue(prRows().all { it["state"] == "open" })

        fake.prs["octo/r"] = listOf(RepoPr(7, "CHE-1-feat-x", "merged", "https://github.com/octo/r/pull/7"))
        syncService.syncAll()
        assertEquals(2, prRows().size)
        assertTrue(prRows().all { it["state"] == "merged" })
        assertEquals(2, prRows().map { it["oid"] }.toSet().size)
    }

    private fun prRows(): List<Map<String, Any>> = jdbc.queryForList(
        "select ir.item_object_id, i.object_id as oid, ir.state from item_git_ref ir " +
            "join item i on i.object_id = ir.item_object_id where ir.kind = 'pr'",
    )
}
