package yangzhou.api.github

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ContextConfiguration
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-143:V10-S5 PR author 回填黑盒(github_username → 池内自动 assign)。 */
@org.springframework.test.context.ContextConfiguration(classes = [FakeGatewayConfig::class])
class AuthorBackfillApiTest : AbstractApiTest() {

    @Autowired
    lateinit var fake: FakeGithubGateway

    @Autowired
    lateinit var syncService: GithubSyncService

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `PR author 映射成员——池内自动 assign——未配置池跳过`() {
        val authed = bootstrapAndAuth()
        authed.put().uri("/api/workspace/github-token")
            .body(mapOf("token" to "t")).exchange().expectStatus().isOk()
        authed.post().uri("/api/members")
            .body(mapOf("displayName" to "小李"))
            .exchange().expectStatus().isCreated()
        // 小李绑 github_username
        val li = jdbc.queryForList("select object_id, id from member where display_name = '小李'")[0]
        authed.put().uri("/api/members/${li["object_id"].toString()}/github-username")
            .body(mapOf("githubUsername" to "xiaoli-gh"))
            .exchange().expectStatus().isOk()

        createProject(authed, "CHE")
        authed.post().uri("/api/projects/CHE/repos")
            .body(mapOf("repo" to "octo/r"))
            .exchange().expectStatus().isCreated()
        val itemId = createItem(authed, "CHE", "x")["itemId"].asText()

        // PR author = xiaoli-gh(小李)→ 自动 assign
        fake.prs["octo/r"] = listOf(GithubPr(1, "CHE-1-feat", "open", "https://github.com/octo/r/pull/1", "xiaoli-gh"))
        syncService.syncAll()

        val detail = json.readTree(
            authed.get().uri("/api/items/$itemId").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("小李", detail["assignee"].asText())
        assertTrue(detail["gitRefs"].size() >= 1)
    }

    @Test
    fun `author 无映射——不指派不报错`() {
        val authed = bootstrapAndAuth()
        authed.put().uri("/api/workspace/github-token")
            .body(mapOf("token" to "t")).exchange().expectStatus().isOk()
        createProject(authed, "CHE")
        authed.post().uri("/api/projects/CHE/repos")
            .body(mapOf("repo" to "octo/r")).exchange().expectStatus().isCreated()
        val itemId = createItem(authed, "CHE", "x")["itemId"].asText()

        fake.prs["octo/r"] = listOf(GithubPr(1, "CHE-1-x", "open", null, "unknown-ghost"))
        syncService.syncAll()

        val detail = json.readTree(
            authed.get().uri("/api/items/$itemId").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertTrue(detail["assignee"].isNull)
    }
}
