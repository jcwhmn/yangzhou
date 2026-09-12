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
        // 映射:branch_created → Development
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
        assertEquals("Development", detail["status"].asText())
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

        fake.createError = GithubApiException(422, "Reference already exists")
        create(authed, itemId, repoId).expectStatus().isEqualTo(409)
        fake.createError = GithubApiException(404, "base not found")
        create(authed, itemId, repoId, base = "ghost").expectStatus().isBadRequest()

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

        // PAT 缺失
        authed.delete().uri("/api/workspace/github-token").exchange().expectStatus().isNoContent()
        create(authed, itemId, repoId).expectStatus().isBadRequest()
    }

    @Test
    fun `未知挂载 404`() {
        val (authed, _, itemId) = setupProject()
        create(authed, itemId, UUID.randomUUID().toString()).expectStatus().isNotFound()
    }
}
