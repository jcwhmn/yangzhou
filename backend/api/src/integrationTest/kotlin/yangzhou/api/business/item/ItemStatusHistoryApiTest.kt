package yangzhou.api.business.item

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import yangzhou.api.business.github.FakeGatewayConfig
import yangzhou.api.business.github.FakeGithubGateway
import yangzhou.api.business.github.GithubSyncService
import yangzhou.api.business.github.RepoPr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** YPJ-44:item 级状态迁移审计——三条写入路径(create / manual / github)+ 守卫路径不写行(spec 0032)。 */
@Import(FakeGatewayConfig::class)
class ItemStatusHistoryApiTest : AbstractApiTest() {
    @Autowired
    lateinit var fake: FakeGithubGateway

    @Autowired
    lateinit var syncService: GithubSyncService

    private lateinit var authed: RestTestClient

    @org.junit.jupiter.api.BeforeEach
    fun resetFake() {
        fake.branches = mutableMapOf()
        fake.prs = mutableMapOf()
        fake.failWith = null
    }

    /** PAT + CHE 项目(默认 3 槽)+ 挂载 octo/r + item hist-x(初始 To Do)。 */
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
        return createItem(authed, "CHE", "hist-x")["itemId"].asText()
    }

    private fun statuses(): JsonNode = json.readTree(
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

    private fun statusId(name: String): String = statuses().first { it["name"].asText() == name }["statusId"].asText()

    private fun statusRowId(name: String): java.util.UUID = jdbc.queryForList(
        "select object_id from status where name = ? and project_object_id = (select object_id from project where key = 'CHE')",
        name,
    )[0]["object_id"] as java.util.UUID

    private fun history(title: String = "hist-x"): List<Map<String, Any?>> = jdbc.queryForList(
        "select h.from_status_object_id, h.to_status_object_id, h.source, h.actor_member_object_id " +
            "from item_status_history h where h.item_object_id = (select object_id from item where title = ?) order by h.id",
        title,
    )

    @Test
    fun `create 即留初始行——manual 迁移留行——同状态 no-op 不写`() {
        setup()
        val inReview = statusId("In Review")
        val done = statusId("Done")

        // 建在 In Review(显式初始状态)→ 1 行 created(from NULL)
        val mid =
            json
                .readTree(
                    authed
                        .post()
                        .uri("/api/projects/CHE/items")
                        .body(mapOf("title" to "hist-m", "statusItemId" to inReview))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(String::class.java)
                        .returnResult()
                        .responseBody!!,
                )["itemId"]
                .asText()
        val rows = history("hist-m")
        assertEquals(1, rows.size)
        assertEquals("created", rows[0]["source"])
        assertNull(rows[0]["from_status_object_id"])
        assertEquals(statusRowId("In Review"), rows[0]["to_status_object_id"])

        // In Review → Done(无规则行 = 自由迁移)→ +1 manual
        authed
            .patch()
            .uri("/api/items/$mid")
            .body(mapOf("statusItemId" to done))
            .exchange()
            .expectStatus()
            .isOk()
        val after = history("hist-m")
        assertEquals(2, after.size)
        assertEquals("manual", after[1]["source"])
        assertEquals(statusRowId("In Review"), after[1]["from_status_object_id"])
        assertEquals(statusRowId("Done"), after[1]["to_status_object_id"])

        // 同状态 no-op → 行数不变
        authed
            .patch()
            .uri("/api/items/$mid")
            .body(mapOf("statusItemId" to done))
            .exchange()
            .expectStatus()
            .isOk()
        assertEquals(2, history("hist-m").size)
    }

    @Test
    fun `github 管道迁移留行——final 封口后 sync 不再加行`() {
        setup()

        // 初始:1 行 created(To Do)
        val rows = history()
        assertEquals(1, rows.size)
        assertEquals("created", rows[0]["source"])
        assertEquals(statusRowId("To Do"), rows[0]["to_status_object_id"])

        // PR open → In Review(github 行,actor 为空 = 系统)
        fake.prs["octo/r"] = listOf(RepoPr(1, "CHE-1-x", "open", "https://github.com/octo/r/pull/1"))
        syncService.syncAll()
        val afterOpen = history()
        assertEquals(2, afterOpen.size)
        assertEquals("github", afterOpen[1]["source"])
        assertNull(afterOpen[1]["actor_member_object_id"])
        assertEquals(statusRowId("To Do"), afterOpen[1]["from_status_object_id"])
        assertEquals(statusRowId("In Review"), afterOpen[1]["to_status_object_id"])

        // merged → Done
        fake.prs["octo/r"] = listOf(RepoPr(1, "CHE-1-x", "merged", "https://github.com/octo/r/pull/1"))
        syncService.syncAll()
        assertEquals(3, history().size)

        // 已 final:再 sync 封口,零新增
        fake.prs["octo/r"] = listOf(RepoPr(1, "CHE-1-x", "closed", "https://github.com/octo/r/pull/1"))
        syncService.syncAll()
        assertEquals(3, history().size)
    }

    @Test
    fun `WIP 满跳过不写行`() {
        setup()
        jdbc.update(
            "update status set wip_limit = 1 where name = 'In Review' " +
                "and project_object_id = (select object_id from project where key = 'CHE')",
        )
        // 预置:另一 item 直接建在 In Review,占满 WIP(绕开"开工须有主"守卫)
        authed
            .post()
            .uri("/api/projects/CHE/items")
            .body(mapOf("title" to "hist-w", "statusItemId" to statusId("In Review")))
            .exchange()
            .expectStatus()
            .isCreated()

        fake.prs["octo/r"] = listOf(RepoPr(1, "CHE-1-x", "open", "https://github.com/octo/r/pull/1"))
        syncService.syncAll()

        // 迁移被 WIP 拦下:hist-x 仍只有 created 行
        assertEquals(1, history().size)
    }
}
