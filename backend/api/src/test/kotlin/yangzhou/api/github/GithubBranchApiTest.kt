package yangzhou.api.github

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-112:V6-S3 建分支 API + item gitRefs 读 黑盒(fake gateway + Testcontainers 真 DB)。 */
@Import(FakeGatewayConfig::class)
class GithubBranchApiTest : AbstractApiTest() {

    @Autowired
    lateinit var fake: FakeGithubGateway

    @Autowired
    lateinit var syncService: GithubSyncService

    @org.junit.jupiter.api.BeforeEach
    fun resetFake() {
        fake.branches = mutableMapOf(); fake.prs = mutableMapOf()
        fake.failWith = null; fake.createError = null; fake.created.clear()
    }

    private fun setupProject(withToken: Boolean = true): Triple<RestTestClient, String, String> {
        val authed = bootstrapAndAuth()
        if (withToken) {
            authed.put().uri("/api/workspace/github-token")
                .body(mapOf("token" to "test-token"))
                .exchange().expectStatus().isOk()
        }
        createProject(authed, "CHE")
        val repoId = json.readTree(
            authed.post().uri("/api/projects/CHE/repos")
                .body(mapOf("repo" to "octo/r"))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )["repoId"].asText()
        val itemId = createItem(authed, "CHE", "x")["itemId"].asText() // CHE-1,To Do
        return Triple(authed, repoId, itemId)
    }

    private fun create(authed: Any, itemId: String, repoId: String, name: String = "CHE-1-feat", base: String = "main") =
        (authed as org.springframework.test.web.servlet.client.RestTestClient).post()
            .uri("/api/items/$itemId/branches")
            .body(mapOf("repoId" to repoId, "baseBranch" to base, "name" to name))
            .exchange()

    @Test
    fun `建分支成功——落链接留痕并走 branch_created 映射——详情带 gitRefs`() {
        val (authed, repoId, _) = setupProject()
        // 映射:branch_created → In Progress
        val statuses = json.readTree(
            authed.get().uri("/api/projects/CHE").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )["statuses"]
        authed.put().uri("/api/projects/CHE/workflow-rules")
            .body(mapOf("rules" to listOf(mapOf("eventType" to "branch_created", "statusId" to statuses[1]["statusId"].asText()))))
            .exchange().expectStatus().isOk()
        val itemId = createItem(authed, "CHE", "x")["itemId"].asText()

        val created = json.readTree(
            create(authed, itemId, repoId, "CHE-1-feat", "main")
                .expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("octo/r", created["repo"].asText())
        assertEquals("CHE-1-feat", created["branch"].asText())
        assertEquals("https://github.com/octo/r/tree/CHE-1-feat", created["url"].asText())
        assertEquals(listOf<Triple<String, String, String>>(Triple("octo/r", "CHE-1-feat", "main")), fake.created)

        // 状态动了(映射生效)+ 活动留痕两类
        val detail = json.readTree(
            authed.get().uri("/api/items/$itemId").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("In Progress", detail["status"].asText())
        val refs = detail["gitRefs"]
        assertEquals(1, refs.size())
        assertEquals("branch", refs[0]["kind"].asText())
        assertEquals("CHE-1-feat", refs[0]["ref"].asText())

        val acts = json.readTree(
            authed.get().uri("/api/items/$itemId/activity").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        val kinds = acts.map { it["kind"].asText() }
        assertTrue("github_branch_created" in kinds)
        assertTrue("github_status_changed" in kinds)
    }

    @Test
    fun `重复分支 422 映射 409——repo 不属于该项目 400——PAT 缺失 400——分支名非法 400`() {
        val (authed, repoId, itemId) = setupProject()

        fake.createError = VcsApiException(422, "Reference already exists")
        create(authed, itemId, repoId).expectStatus().isEqualTo(409)
        fake.createError = VcsApiException(404, "base not found")
        create(authed, itemId, repoId, base = "ghost").expectStatus().isBadRequest()

        // 其余 GitHub 错误(如 401 PAT 失效)映射可操作 400(原裸抛 500,YPJ-27)
        fake.createError = VcsApiException(401, "Bad credentials")
        create(authed, itemId, repoId).expectStatus().isBadRequest()

        // repo 不属于该项目
        createProject(authed, "SEC")
        val otherRepoId = json.readTree(
            authed.post().uri("/api/projects/SEC/repos")
                .body(mapOf("repo" to "octo/other")).exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )["repoId"].asText()
        create(authed, itemId, otherRepoId).expectStatus().isBadRequest()

        // 分支名非法
        create(authed, itemId, repoId, name = "bad branch!").expectStatus().isBadRequest()

        // YPJ-37:逗号(多票号预填)合法
        fake.createError = null
        create(authed, itemId, repoId, name = "task/CHE-1,CHE-2-fix").expectStatus().isCreated()

        // PAT 缺失
        authed.delete().uri("/api/workspace/github-token").exchange().expectStatus().isNoContent()
        create(authed, itemId, repoId).expectStatus().isBadRequest()
    }

    @Test
    fun `未知挂载 404`() {
        val (authed, _, itemId) = setupProject()
        create(authed, itemId, UUID.randomUUID().toString()).expectStatus().isNotFound()
    }

    @Test
    fun `YPJ-39 attach——另一票补挂同一分支,两票详情均带该分支`() {
        val (authed, repoId, itemId1) = setupProject()
        val itemId2 = createItem(authed, "CHE", "second")["itemId"].asText()
        create(authed, itemId1, repoId, "feat/CHE-1-x", "main").expectStatus().isCreated()

        authed.post().uri("/api/items/$itemId2/git-refs")
            .body(mapOf("repo" to "octo/r", "ref" to "feat/CHE-1-x"))
            .exchange().expectStatus().isOk()

        val refs = gitRefsOf(authed, itemId2)
        assertTrue(refs.any { it["kind"].asText() == "branch" && it["ref"].asText() == "feat/CHE-1-x" && it["repo"].asText() == "octo/r" })
        // 主票原关联仍在(多对多不挤掉既有行)
        assertTrue(gitRefsOf(authed, itemId1).any { it["ref"].asText() == "feat/CHE-1-x" })
    }

    @Test
    fun `YPJ-39 attach 负例——404 未入册、409 重复、409 PR 行`() {
        val (authed, repoId, itemId1) = setupProject()
        create(authed, itemId1, repoId, "feat/CHE-1-y", "main").expectStatus().isCreated()

        // 404:分支未入册
        authed.post().uri("/api/items/$itemId1/git-refs")
            .body(mapOf("repo" to "octo/r", "ref" to "nope"))
            .exchange().expectStatus().isNotFound()

        // 409:同票重复补挂
        val itemId2 = createItem(authed, "CHE", "second")["itemId"].asText()
        authed.post().uri("/api/items/$itemId2/git-refs")
            .body(mapOf("repo" to "octo/r", "ref" to "feat/CHE-1-y"))
            .exchange().expectStatus().isOk()
        authed.post().uri("/api/items/$itemId2/git-refs")
            .body(mapOf("repo" to "octo/r", "ref" to "feat/CHE-1-y"))
            .exchange().expectStatus().isEqualTo(409)

        // 409:ref 是 PR 号(PR 行经 sync 落库)
        fake.prs["octo/r"] = listOf(RepoPr(9, "feat/CHE-1-y", "open", null))
        syncService.syncAll()
        authed.post().uri("/api/items/$itemId2/git-refs")
            .body(mapOf("repo" to "octo/r", "ref" to "9"))
            .exchange().expectStatus().isEqualTo(409)
    }

    private fun gitRefsOf(authed: RestTestClient, itemId: String) =
        json.readTree(
            authed.get().uri("/api/items/$itemId").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )["gitRefs"]

}
