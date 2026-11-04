package yangzhou.api.business.itemgroup

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spec 0097 S2:统一组端点 /groups* + 动作白名单 + group-membership(按类型正交)。
 * 吸收原 SprintApiTest/SprintBoardApiTest/GroupTypeApiTest 语义;sprint 只是组名,开始/完成只是动作。
 */
class ItemGroupApiTest : AbstractApiTest() {
    private fun createGroup(
        authed: RestTestClient,
        key: String,
        body: Map<String, Any?>,
    ): JsonNode {
        val r =
            authed
                .post()
                .uri("/api/projects/$key/groups")
                .body(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(201, r.status.value(), r.responseBody)
        return json.readTree(r.responseBody!!)
    }

    private fun action(
        authed: RestTestClient,
        key: String,
        groupId: String,
        function: String,
    ) = authed
        .post()
        .uri("/api/projects/$key/groups/$groupId/actions/$function")
        .exchange()
        .expectBody(String::class.java)
        .returnResult()

    private fun assign(
        authed: RestTestClient,
        itemId: String,
        groupId: String?,
        groupType: String? = null,
    ) = authed
        .put()
        .uri("/api/items/$itemId/group-membership")
        .body(mapOf("groupId" to groupId, "groupType" to groupType))
        .exchange()
        .expectBody(String::class.java)
        .returnResult()

    private fun get(
        authed: RestTestClient,
        path: String,
    ): JsonNode = json.readTree(
        authed
            .get()
            .uri(path)
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    @Test
    fun `sprint 组 CRUD 与校验`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")

        val s =
            createGroup(
                authed,
                "GRP",
                mapOf("typeKey" to "sprint", "name" to "Sprint 1", "startDate" to "2026-10-01", "endDate" to "2026-10-15"),
            )
        assertEquals("Sprint 1", s["name"].asText())
        assertEquals("sprint", s["typeKey"].asText())
        assertEquals("planned", s["status"].asText()) // 初始态 = 类型 workflow 的 START
        assertEquals("2026-10-01", s["startDate"].asText())
        val sid = s["groupId"].asText()

        assertEquals(1, get(authed, "/api/projects/GRP/groups?type=sprint").size())
        assertEquals(1, get(authed, "/api/projects/GRP/groups").size())

        // PATCH 仅 name/日期;status 字段不再走 PATCH(动作端点专管)
        val patched =
            authed
                .patch()
                .uri("/api/projects/GRP/groups/$sid")
                .body(mapOf("name" to "Sprint 1 改", "startDate" to "2026-10-02", "endDate" to ""))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(200, patched.status.value())
        val p = json.readTree(patched.responseBody!!)
        assertEquals("Sprint 1 改", p["name"].asText())
        assertEquals("2026-10-02", p["startDate"].asText())
        assertTrue(p["endDate"].isNull)

        // 未知类型 404
        authed
            .post()
            .uri("/api/projects/GRP/groups")
            .body(mapOf("typeKey" to "ghost", "name" to "x"))
            .exchange()
            .expectStatus()
            .isNotFound()
    }

    @Test
    fun `动作白名单——start_complete_backward 与终态_非法动作 409`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val s = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "A"))
        val gid = s["groupId"].asText()

        // planned 无 backward(START 无回退出边)
        assertEquals(409, action(authed, "GRP", gid, "backward").status.value())
        assertEquals("active", json.readTree(action(authed, "GRP", gid, "start").responseBody!!)["status"].asText())
        assertEquals("completed", json.readTree(action(authed, "GRP", gid, "complete").responseBody!!)["status"].asText())
        // 终态无出边 → 任何动作 409(护栏)
        assertEquals(409, action(authed, "GRP", gid, "start").status.value())
        assertEquals(409, action(authed, "GRP", gid, "complete").status.value())
        // 未知动作函数 409(不在出边白名单)
        assertEquals(409, action(authed, "GRP", gid, "explode").status.value())

        // backward:active → planned
        val b = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "B"))
        action(authed, "GRP", b["groupId"].asText(), "start")
        assertEquals("planned", json.readTree(action(authed, "GRP", b["groupId"].asText(), "backward").responseBody!!)["status"].asText())
    }

    @Test
    fun `类型 workflow 只读展示——种子三态与出边`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val wf = get(authed, "/api/projects/GRP/group-types/sprint/workflow")
        assertEquals(3, wf.size())
        assertEquals("planned", wf[0]["name"].asText())
        assertEquals("START", wf[0]["statusType"].asText())
        assertEquals("start", wf[0]["transitions"][0]["function"].asText())
        assertEquals("active", wf[0]["transitions"][0]["target"].asText())
        assertEquals(2, wf[1]["transitions"].size()) // complete + backward
        assertEquals(0, wf[2]["transitions"].size()) // completed 终态护栏

        val rwf = get(authed, "/api/projects/GRP/group-types/release/workflow")
        assertEquals(2, rwf.size())
        assertEquals("released", rwf[1]["name"].asText())
    }

    @Test
    fun `归属替换——指派_移出_幂等_完成后拒`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val item = createItem(authed, "GRP", "x")
        val itemId = item["itemId"].asText()
        val s = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "S1"))
        val gid = s["groupId"].asText()

        // 指派 → ItemDto 回显 sprintId/sprintName
        val assigned = json.readTree(assign(authed, itemId, gid, "sprint").responseBody!!)
        assertEquals(gid, assigned["sprintId"].asText())
        assertEquals("S1", assigned["sprintName"].asText())

        // 重复指派同组幂等(200)
        assertEquals(200, assign(authed, itemId, gid, "sprint").status.value())

        // 移出(groupId=null + groupType)
        val removed = json.readTree(assign(authed, itemId, null, "sprint").responseBody!!)
        assertTrue(removed["sprintId"].isNull)

        // 不存在的组 404
        authed
            .put()
            .uri("/api/items/$itemId/group-membership")
            .body(mapOf("groupId" to "00000000-0000-0000-0000-000000000001", "groupType" to "sprint"))
            .exchange()
            .expectStatus()
            .isNotFound()

        // 完成后拒指派(历史不可变)
        action(authed, "GRP", gid, "start")
        action(authed, "GRP", gid, "complete")
        assertEquals(409, assign(authed, itemId, gid, "sprint").status.value())
    }

    @Test
    fun `跨类型归属正交——sprint 与 release 共存`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val item = createItem(authed, "GRP", "x")
        val itemId = item["itemId"].asText()
        val s = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "S1"))
        val r = createGroup(authed, "GRP", mapOf("typeKey" to "release", "name" to "R1"))

        assign(authed, itemId, s["groupId"].asText(), "sprint")
        val both = json.readTree(assign(authed, itemId, r["groupId"].asText(), "release").responseBody!!)
        assertEquals(s["groupId"].asText(), both["sprintId"].asText()) // release 挂入不丢 sprint
        assertEquals(r["groupId"].asText(), both["releaseId"].asText())

        // 换 sprint B → release 不丢
        val b = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "S2"))
        val swapped = json.readTree(assign(authed, itemId, b["groupId"].asText(), "sprint").responseBody!!)
        assertEquals(b["groupId"].asText(), swapped["sprintId"].asText())
        assertEquals(r["groupId"].asText(), swapped["releaseId"].asText())
    }

    @Test
    fun `backlog 谓词与完成后历史仍在`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val inSprint = createItem(authed, "GRP", "in-sprint")["itemId"].asText()
        val done = createItem(authed, "GRP", "done-item")["itemId"].asText()
        val s = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "S1"))
        val gid = s["groupId"].asText()

        assign(authed, inSprint, gid, "sprint")
        assertEquals(1, get(authed, "/api/projects/GRP/backlog").size()) // done-item 非 final 且无 sprint → 在册;in-sprint 已被排除

        // 完成 sprint:成员行保留(历史),item 移回 backlog(非终态 ∧ 不在任何 planned/active sprint)
        action(authed, "GRP", gid, "start")
        action(authed, "GRP", gid, "complete")
        assertEquals(1, get(authed, "/api/projects/GRP/groups/$gid/items").size()) // 历史仍在
        assertEquals(2, get(authed, "/api/projects/GRP/backlog").size()) // in-sprint + done-item(均非终态、无 active sprint)
    }

    @Test
    fun `board——active sprint 成员并集_非 sprint active 组不进`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        // 空项目无 active → 降级补集为空,activeGroups 空
        val board0 = get(authed, "/api/projects/GRP/board")
        assertEquals(0, board0["activeGroups"].size())
        assertEquals(0, board0["items"].size())

        val item = createItem(authed, "GRP", "x")["itemId"].asText()
        val s1 = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "S1"))
        assign(authed, item, s1["groupId"].asText(), "sprint")
        action(authed, "GRP", s1["groupId"].asText(), "start")

        val board = get(authed, "/api/projects/GRP/board")
        assertEquals(1, board["activeGroups"].size())
        assertEquals(item, board["items"][0]["itemId"].asText())

        // 非 sprint 的 active 组不进 board
        authed
            .post()
            .uri("/api/projects/GRP/group-types")
            .body(mapOf("typeKey" to "phase", "name" to "阶段"))
            .exchange()
            .expectStatus()
            .isCreated()
        val c = createGroup(authed, "GRP", mapOf("typeKey" to "phase", "name" to "P1"))
        action(authed, "GRP", c["groupId"].asText(), "switch")
        assertEquals(1, get(authed, "/api/projects/GRP/board")["activeGroups"].size())
    }

    @Test
    fun `board——无 active 降级为 backlog 补集_与 backlog 互斥`() {
        val authed = bootstrapAndAuth()
        val project = createProject(authed, "GRP")
        val done = project["statuses"].first { it["name"].asText() == "Done" }["statusId"].asText()
        val todo = createItem(authed, "GRP", "todo")
        val doneId = json
            .readTree(
                authed
                    .post()
                    .uri("/api/projects/GRP/items")
                    .body(mapOf("title" to "done", "type" to "task", "statusItemId" to done))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )["itemId"]
            .asText()

        // 无组:board = 终态,backlog = 非终态,交集空(spec 0098)
        val board = get(authed, "/api/projects/GRP/board")
        assertEquals(0, board["activeGroups"].size())
        assertEquals(listOf(doneId), board["items"].map { it["itemId"].asText() })
        val backlog = get(authed, "/api/projects/GRP/backlog")
        assertEquals(listOf(todo["itemId"].asText()), backlog.map { it["itemId"].asText() })
        val backlogIds = backlog.map { it["itemId"].asText() }.toSet()
        assertTrue(board["items"].none { it["itemId"].asText() in backlogIds })
    }

    @Test
    fun `board——无 active 但有 planned sprint_成员随补集进 board`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val item = createItem(authed, "GRP", "planned-member")["itemId"].asText()
        val s1 = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "S1")) // 初态 planned
        assign(authed, item, s1["groupId"].asText(), "sprint")
        // 不 start:组停在 planned;成员非终态但不在 backlog(planned sprint 成员被 currentSprint 排除)
        assertEquals(0, get(authed, "/api/projects/GRP/backlog").size())
        val board = get(authed, "/api/projects/GRP/board")
        assertEquals(0, board["activeGroups"].size())
        assertEquals(listOf(item), board["items"].map { it["itemId"].asText() })
    }

    @Test
    fun `自定义类型——种子三态 switch_建组_任意切换_级联删`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")

        val t =
            authed
                .post()
                .uri("/api/projects/GRP/group-types")
                .body(mapOf("typeKey" to "phase", "name" to "阶段"))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(201, t.status.value())
        assertEquals(3, get(authed, "/api/projects/GRP/group-types/phase/workflow").size())

        val g = createGroup(authed, "GRP", mapOf("typeKey" to "phase", "name" to "设计期"))
        assertEquals("planned", g["status"].asText())
        val gid = g["groupId"].asText()
        assertEquals("active", json.readTree(action(authed, "GRP", gid, "switch").responseBody!!)["status"].asText())
        assertEquals("completed", json.readTree(action(authed, "GRP", gid, "switch").responseBody!!)["status"].asText())
        assertEquals("planned", json.readTree(action(authed, "GRP", gid, "switch").responseBody!!)["status"].asText())

        // 非空自定义组删除 = 级联(membership + 留痕)
        val item = createItem(authed, "GRP", "y")["itemId"].asText()
        assign(authed, item, gid, "phase")
        authed
            .delete()
            .uri("/api/projects/GRP/groups/$gid")
            .exchange()
            .expectStatus()
            .isNoContent()
        val removed = json.readTree(assign(authed, item, null, "phase").responseBody!!)
        assertTrue(removed["sprintId"].isNull)

        // 类型下仍有组不可删 → 删组后可删类型;builtin 不可删
        authed
            .delete()
            .uri("/api/projects/GRP/group-types/sprint")
            .exchange()
            .expectStatus()
            .isEqualTo(409)
        authed
            .delete()
            .uri("/api/projects/GRP/group-types/phase")
            .exchange()
            .expectStatus()
            .isNoContent()
    }

    @Test
    fun `内建类型 sprint_release 不可创建冲突_自定义重名 409`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        authed
            .post()
            .uri("/api/projects/GRP/group-types")
            .body(mapOf("typeKey" to "sprint", "name" to "x"))
            .exchange()
            .expectStatus()
            .isBadRequest()
        authed
            .post()
            .uri("/api/projects/GRP/group-types")
            .body(mapOf("typeKey" to "phase", "name" to "x"))
            .exchange()
            .expectStatus()
            .isCreated()
        authed
            .post()
            .uri("/api/projects/GRP/group-types")
            .body(mapOf("typeKey" to "phase", "name" to "y"))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
    }
}
