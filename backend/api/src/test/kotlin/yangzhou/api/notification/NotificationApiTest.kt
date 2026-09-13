package yangzhou.api.notification

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import yangzhou.api.AbstractApiTest
import yangzhou.api.github.FakeGatewayConfig
import yangzhou.api.github.FakeGithubGateway
import yangzhou.api.github.GithubPr
import yangzhou.api.github.GithubSyncService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-115:V7-S1 通知黑盒(收件人推导/排除操作者/下一列 action/已读)。 */
@Import(FakeGatewayConfig::class)
class NotificationApiTest : AbstractApiTest() {

    @Autowired
    lateinit var fake: FakeGithubGateway

    @Autowired
    lateinit var syncService: GithubSyncService

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `状态变更通知——人工操作排除操作者,收件人落库`() {
        val authed = bootstrapAndAuth()
        authed.post().uri("/api/members")
            .body(mapOf("displayName" to "小李"))
            .exchange().expectStatus().isCreated()
        val members = jdbc.queryForList("select id, object_id, display_name, username from member")
        val xiaoLi = members.first { it["display_name"] == "小李" }
        val me = members.first { it["username"] == "me" }

        createProject(authed, "CHE")
        val itemId = createItem(authed, "CHE", "x")["itemId"].asText() // createdBy = me
        authed.put().uri("/api/items/$itemId/assignee")
            .body(mapOf("assigneeItemId" to xiaoLi["object_id"].toString()))
            .exchange().expectStatus().isOk()

        // 我操作状态变更 → 收件人 = assignee(小李)+ createdBy(me),排除操作者(me)→ 只剩小李
        val inProgress = json.readTree(
            authed.get().uri("/api/projects/CHE").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )["statuses"][1]["statusId"].asText()
        authed.patch().uri("/api/items/$itemId")
            .body(mapOf("statusItemId" to inProgress))
            .exchange().expectStatus().isOk()

        // 我的未读 = 0(被排除);小李落一条 status_changed
        assertEquals(0, unreadCount(authed))
        val rows = jdbc.queryForList(
            "select n.kind, m.display_name from notification n join member m on m.id = n.recipient_member_id where n.kind = 'status_changed'",
        )
        assertEquals(1, rows.size)
        assertEquals("小李", rows[0]["display_name"])
        assertEquals("status_changed", rows[0]["kind"])
    }

    @Test
    fun `GitHub 事件通知我——action 为下一列——已读与全部已读`() {
        val authed = bootstrapAndAuth()
        authed.put().uri("/api/workspace/github-token")
            .body(mapOf("token" to "t")).exchange().expectStatus().isOk()
        createProject(authed, "CHE")
        authed.post().uri("/api/projects/CHE/repos")
            .body(mapOf("repo" to "octo/r")).exchange().expectStatus().isCreated()
        val statuses = json.readTree(
            authed.get().uri("/api/projects/CHE").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )["statuses"]
        authed.put().uri("/api/projects/CHE/workflow-rules")
            .body(mapOf("rules" to listOf(mapOf("eventType" to "pr_opened", "statusId" to statuses[2]["statusId"].asText()))))
            .exchange().expectStatus().isOk()
        createItem(authed, "CHE", "x")["itemId"].asText() // CHE-1,createdBy = me

        // GitHub 事件(actor null)→ 我(createdBy)收通知,不排除任何人
        fake.prs["octo/r"] = listOf(GithubPr(7, "CHE-1-x", "open", "https://github.com/octo/r/pull/7"))
        syncService.syncAll()

        val list = json.readTree(
            authed.get().uri("/api/notifications").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(1, list.size())
        val n = list[0]
        assertEquals("status_changed", n["kind"].asText())
        assertTrue(n["actor"].isNull)
        assertEquals("CHE-1", n["number"].asText())
        assertEquals("In Review", n["newValue"].asText())
        assertTrue(!n["read"].asBoolean())
        // action = 下一列:当前 In Review(position 2)→ QA(position 3)
        assertEquals("QA", n["action"]["statusName"].asText())
        assertEquals(1, unreadCount(authed))

        // 单条已读 → 未读 0
        authed.put().uri("/api/notifications/${n["notificationId"].asText()}/read")
            .exchange().expectStatus().isNoContent()
        assertEquals(0, unreadCount(authed))
    }

    @Test
    fun `read-all 清零`() {
        val authed = bootstrapAndAuth()
        authed.put().uri("/api/workspace/github-token")
            .body(mapOf("token" to "t")).exchange().expectStatus().isOk()
        createProject(authed, "CHE")
        authed.post().uri("/api/projects/CHE/repos")
            .body(mapOf("repo" to "octo/r")).exchange().expectStatus().isCreated()
        val statuses = json.readTree(
            authed.get().uri("/api/projects/CHE").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )["statuses"]
        authed.put().uri("/api/projects/CHE/workflow-rules")
            .body(mapOf("rules" to listOf(mapOf("eventType" to "branch_created", "statusId" to statuses[1]["statusId"].asText()))))
            .exchange().expectStatus().isOk()
        createItem(authed, "CHE", "x")["itemId"].asText()

        fake.branches["octo/r"] = listOf("CHE-1-a")
        syncService.syncAll()
        fake.branches["octo/r"] = listOf("CHE-1-a", "CHE-1-b")
        syncService.syncAll() // 第二条分支同 item,不重复留痕 → 仅 1 条通知
        assertEquals(1, unreadCount(authed))

        authed.post().uri("/api/notifications/read-all")
            .exchange().expectStatus().isNoContent()
        assertEquals(0, unreadCount(authed))
    }

    @Test
    fun `评论通知——作者即唯一相关人时无人收`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val itemId = createItem(authed, "CHE", "x")["itemId"].asText() // createdBy = me,无 assignee
        authed.post().uri("/api/items/$itemId/comments")
            .body(mapOf("body" to "看一眼"))
            .exchange().expectStatus().isCreated()
        // 作者 = 我 = createdBy,去重排除后无人可通知
        val rows = jdbc.queryForList("select count(*) as c from notification")
        assertEquals(0, (rows[0]["c"] as Number).toInt())
    }

    private fun unreadCount(authed: org.springframework.test.web.servlet.client.RestTestClient): Int =
        json.readTree(
            authed.get().uri("/api/notifications/unread-count").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )["count"].asInt()
}
