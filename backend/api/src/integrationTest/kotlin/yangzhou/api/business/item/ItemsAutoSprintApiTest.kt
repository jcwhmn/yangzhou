package yangzhou.api.business.item

import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-120(spec 0089):开始动作自动入当前 sprint——服务端黑盒;status isInProgress 契约。 */
class ItemsAutoSprintApiTest : AbstractApiTest() {

    private fun setup(): Pair<RestTestClient, String> {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val itemId = createItem(authed, "CHE", "x")["itemId"].asText()
        return authed to itemId
    }

    private fun createSprint(authed: RestTestClient, name: String): String =
        authed.post().uri("/api/projects/CHE/sprints")
            .body(mapOf("name" to name))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(String::class.java).returnResult().responseBody!!
            .let { json.readTree(it) }["sprintId"].asText()

    /** POST 建出的 sprint 默认 planned;激活是用户动作(sprints move active)。 */
    private fun activateSprint(authed: RestTestClient, sprintId: String) {
        authed.patch().uri("/api/projects/CHE/sprints/$sprintId")
            .body(mapOf("status" to "active"))
            .exchange().expectStatus().isOk()
    }

    /** 默认 5 列 workflow(To Do/In Progress/In Review/QA/Done);按名取状态 id。 */
    private fun statusId(authed: RestTestClient, name: String): String {
        val statuses = projectStatuses(authed)
        return statuses.first { it["name"].asText() == name }["statusId"].asText()
    }

    private fun projectStatuses(authed: RestTestClient) = json.readTree(
        authed.get().uri("/api/projects/CHE").exchange()
            .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
    )["statuses"]

    /** 开工须有主(V4-S3):离开 start 态前必须指派。 */
    private fun assignToMe(authed: RestTestClient, itemId: String) =
        authed.put().uri("/api/items/$itemId/assignee")
            .body(mapOf("assigneeItemId" to meMemberId(authed)))
            .exchange().expectStatus().isOk()

    private fun patchStatus(authed: RestTestClient, itemId: String, toStatusId: String) =
        json.readTree(
            authed.patch().uri("/api/items/$itemId")
                .body(mapOf("statusItemId" to toStatusId))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        ).also { println("PATCH_RESP=$it") } // YPJ-120 调试,验证后删

    @Test
    fun `开始无 sprint 的 item 自动挂最早创建的 active sprint`() {
        val (authed, itemId) = setup()
        val a = createSprint(authed, "A")
        val b = createSprint(authed, "B") // 第二个 active;最早创建的 A 应胜出
        activateSprint(authed, a)
        activateSprint(authed, b)
        assignToMe(authed, itemId)

        val resp = patchStatus(authed, itemId, statusId(authed, "In Progress"))
        assertEquals("A", resp["sprintName"].asText())
        assertEquals("A", resp["autoAssignedSprintName"].asText())

        // board 归属断言
        val board = json.readTree(
            authed.get().uri("/api/projects/CHE/board").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(a, board["items"].first { it["itemId"].asText() == itemId }["sprintId"].asText())
    }

    @Test
    fun `已有 sprint 的 item 开始不覆盖`() {
        val (authed, itemId) = setup()
        val keep = createSprint(authed, "keep")
        assignToMe(authed, itemId)
        authed.put().uri("/api/items/$itemId/sprint")
            .body(mapOf("sprintId" to keep))
            .exchange().expectStatus().isOk()

        val resp = patchStatus(authed, itemId, statusId(authed, "In Progress"))
        assertEquals("keep", resp["sprintName"].asText())
        assertTrue(resp["autoAssignedSprintName"].isNull, "不覆盖时 autoAssignedSprintName 应为 null")
    }

    @Test
    fun `无 active sprint 时开始静默跳过`() {
        val (authed, itemId) = setup()
        assignToMe(authed, itemId)

        val resp = patchStatus(authed, itemId, statusId(authed, "In Progress"))
        assertTrue(resp["sprintId"].isNull)
        assertTrue(resp["autoAssignedSprintName"].isNull)
    }

    @Test
    fun `直跳 Done 不触发挂载`() {
        val (authed, itemId) = setup()
        createSprint(authed, "A")
        assignToMe(authed, itemId)

        val resp = patchStatus(authed, itemId, statusId(authed, "Done"))
        assertTrue(resp["autoAssignedSprintName"].isNull)
        assertTrue(resp["sprintId"].isNull)
    }

    @Test
    fun `workflow 契约回显 isInProgress 且预置回填`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val byName = projectStatuses(authed).associate { it["name"].asText() to it }
        assertEquals(true, byName.getValue("In Progress")["isInProgress"].asBoolean(), "预置 In Progress 应回填 true")
        assertEquals(false, byName.getValue("To Do")["isInProgress"].asBoolean())
        assertEquals(false, byName.getValue("Done")["isInProgress"].asBoolean())

        // CRUD:自定义状态创建/更新回显
        val created = json.readTree(
            authed.post().uri("/api/projects/CHE/statuses")
                .body(mapOf("name" to "自定义中", "isInProgress" to true))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(true, created["isInProgress"].asBoolean())
        val updated = json.readTree(
            authed.patch().uri("/api/statuses/${created["statusId"].asText()}")
                .body(mapOf("isInProgress" to false))
                .exchange().expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(false, updated["isInProgress"].asBoolean())
    }
}
