package yangzhou.api.business.itemgroup

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import java.time.Instant
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
        assertEquals(1, wf[2]["transitions"].size()) // 0108:completed 加 reopen→active(重开=撤销内部收尾判断)
        assertEquals("reopen", wf[2]["transitions"][0]["function"].asText())
        assertEquals("active", wf[2]["transitions"][0]["target"].asText())
    }

    @Test
    fun `归属替换——指派_移出_幂等_完成后拒`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val item = createItem(authed, "GRP", "x", estimate = true)
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
    fun `backlog 谓词与完成后历史仍在`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val inSprint = createItem(authed, "GRP", "in-sprint", estimate = true)["itemId"].asText()
        val done = createItem(authed, "GRP", "done-item", estimate = true)["itemId"].asText()
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
    fun `组时间线——DESC_全动作_名称解析`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val s = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "S1"))
        val gid = s["groupId"].asText()
        action(authed, "GRP", gid, "start")
        action(authed, "GRP", gid, "complete")
        action(authed, "GRP", gid, "reopen")

        val rows = get(authed, "/api/projects/GRP/groups/$gid/timeline")
        assertEquals(4, rows.size())
        // DESC:后进先见——reopen/complete/start/创建 依次向下
        assertEquals("status", rows[0]["kind"].asText())
        assertEquals("active", rows[0]["to"].asText()) // 0108:reopen=completed→active(重开撤销内部收尾判断)
        assertEquals("completed", rows[1]["to"].asText())
        assertEquals("planned", rows[2]["from"].asText())
        assertEquals("created", rows[3]["source"].asText())
        assertEquals("planned", rows[3]["to"].asText())
        // DESC:后发生的在上
        assertTrue(rows[0]["createdAt"].asText() >= rows[3]["createdAt"].asText())
    }

    @Test
    fun `组时间线——成员进出_join_leave_带名`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val s = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "S1"))
        val gid = s["groupId"].asText()
        val it1 = createItem(authed, "GRP", "i1", estimate = true)["itemId"].asText()
        assign(authed, it1, gid, "sprint")
        assign(authed, it1, null, "sprint") // groupId=null+type=摘除

        val rows = get(authed, "/api/projects/GRP/groups/$gid/timeline")
        assertEquals(3, rows.size()) // leave, join, created(DESC)
        assertEquals("leave", rows[0]["action"].asText())
        assertEquals("join", rows[1]["action"].asText())
        assertEquals("i1", rows[1]["itemTitle"].asText())
        assertTrue(rows[1]["itemNumber"].asInt() > 0)
        assertEquals("created", rows[2]["source"].asText())
    }

    @Test
    fun `组时间线——无史存量组空数组_未知组404`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val s = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "S1"))
        val gid = s["groupId"].asText()
        // 模拟存量组:两张史表清空(端点空态,不报错)
        jdbc.update("delete from group_status_history where group_object_id = ?", java.util.UUID.fromString(gid))
        jdbc.update("delete from item_group_member_history where group_object_id = ?", java.util.UUID.fromString(gid))
        // 纯空组:无史行也合成「创建」行(item_group.created_at,不造 from→to)
        val synth = get(authed, "/api/projects/GRP/groups/$gid/timeline")
        assertEquals(1, synth.size())
        assertEquals("created", synth[0]["source"].asText())
        assertTrue(synth[0]["to"] == null || synth[0]["to"].isNull)

        val r =
            authed
                .get()
                .uri("/api/projects/GRP/groups/${java.util.UUID.randomUUID()}/timeline")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(404, r.status.value())
    }

    @Test
    fun `board——active sprint 成员并集_非 sprint active 组不进`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        // 空项目无 active → 降级补集为空,activeGroups 空
        val board0 = get(authed, "/api/projects/GRP/board")
        assertEquals(0, board0["activeGroups"].size())
        assertEquals(0, board0["items"].size())

        val item = createItem(authed, "GRP", "x", estimate = true)["itemId"].asText()
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
        val todo = createItem(authed, "GRP", "todo", estimate = true)
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
        val item = createItem(authed, "GRP", "planned-member", estimate = true)["itemId"].asText()
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
        // spec 0111:phase 自底向上拿 level=2 = 父层——禁手动动作、禁挂 item(0056 平面自定义组语义在两层世界里收窄为父层语义)
        assertEquals(409, action(authed, "GRP", gid, "switch").status.value())
        val item = createItem(authed, "GRP", "y", estimate = true)["itemId"].asText()
        assertEquals(409, assign(authed, item, gid, "phase").status.value())
        // 空父层组可删
        authed
            .delete()
            .uri("/api/projects/GRP/groups/$gid")
            .exchange()
            .expectStatus()
            .isNoContent()

        // YPJ-165:release 类型已移除,自定义类型均可删(无内建保留)
        authed
            .delete()
            .uri("/api/projects/GRP/group-types/sprint")
            .exchange()
            .expectStatus()
            .isNoContent()
        authed
            .delete()
            .uri("/api/projects/GRP/group-types/phase")
            .exchange()
            .expectStatus()
            .isNoContent()
    }

    @Test
    fun `新项目不种任何组类型——全由用户自配(YPJ-165)`() {
        val authed = bootstrapAndAuth()
        authed
            .post()
            .uri("/api/projects")
            .body(mapOf("key" to "NEW", "name" to "新项目"))
            .exchange()
            .expectStatus()
            .isCreated()
        val types = get(authed, "/api/projects/NEW/group-types")
        assertEquals(0, types.size())
    }

    fun `类型重名冲突_自定义重名 409`() {
        // spec 0100 切片B:保留字废除,"sprint" 冲突由同项目 typeKey 唯一约束兜底(createProject 测试 helper 已种子)
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        authed
            .post()
            .uri("/api/projects/GRP/group-types")
            .body(mapOf("typeKey" to "sprint", "name" to "x"))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
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

    @Test
    fun `spec 0100A 类型携带 level 与 timed,sprint 去内置回填`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val types = get(authed, "/api/projects/GRP/group-types")
        // V48 回填:sprint = level 1 / timed true / builtin false(语义保留,身份去内置)
        val sprint = types.first { it["typeKey"].asText() == "sprint" }
        assertEquals(1, sprint["level"].asInt())
        assertEquals(true, sprint["timed"].asBoolean())
        assertEquals(false, sprint["builtin"].asBoolean())
        // YPJ-165:release 类型已移除,无内建类型
        // 新类型自底向上:已有 level-1 → level 2;timed 透传
        val r =
            authed
                .post()
                .uri("/api/projects/GRP/group-types")
                .body(mapOf("typeKey" to "iteration", "name" to "迭代", "timed" to true))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(201, r.status.value(), r.responseBody)
        val created = json.readTree(r.responseBody!!)
        assertEquals(2, created["level"].asInt())
        assertEquals(true, created["timed"].asBoolean())
    }

    // ---------- YPJ-269(spec 0174):生命周期派生 ----------

    private fun patchStatus(
        authed: RestTestClient,
        itemId: String,
        statusId: String,
    ): Int = authed
        .patch()
        .uri("/api/items/$itemId")
        .body(mapOf("statusItemId" to statusId))
        .exchange()
        .expectBody(String::class.java)
        .returnResult()
        .status
        .value()

    @Test
    fun `生命周期派生——建组仅created_start_complete派生_reopen取最近_start保留首次`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "LCG")
        val s = createGroup(authed, "LCG", mapOf("typeKey" to "sprint", "name" to "LC"))
        val gid = s["groupId"].asText()

        // 建组:仅 created 史行,四派生值全 null
        val d0 = get(authed, "/api/projects/LCG/groups/$gid")
        assertTrue(d0["startedAt"].isNull)
        assertTrue(d0["completedAt"].isNull)
        assertTrue(d0["firstItemStartedAt"].isNull)
        assertTrue(d0["lastItemCompletedAt"].isNull)

        // start → startedAt 出现、completedAt 仍 null;complete → completedAt 出现
        assertEquals(200, action(authed, "LCG", gid, "start").status.value())
        val d1 = get(authed, "/api/projects/LCG/groups/$gid")
        assertTrue(!d1["startedAt"].isNull)
        assertTrue(d1["completedAt"].isNull)
        assertEquals(200, action(authed, "LCG", gid, "complete").status.value())
        val firstCompleted = get(authed, "/api/projects/LCG/groups/$gid")["completedAt"].asText()

        // reopen→complete:completedAt 取最近(第二次);startedAt 保留首次迁出时刻(不覆写)
        assertEquals(200, action(authed, "LCG", gid, "reopen").status.value())
        assertEquals(200, action(authed, "LCG", gid, "complete").status.value())
        val d2 = get(authed, "/api/projects/LCG/groups/$gid")
        assertTrue(Instant.parse(d2["completedAt"].asText()).isAfter(Instant.parse(firstCompleted)))
        assertEquals(d1["startedAt"].asText(), d2["startedAt"].asText())

        // backward→start(组 B):startedAt 保留首次迁出 planned 时刻
        val b = createGroup(authed, "LCG", mapOf("typeKey" to "sprint", "name" to "B"))
        val bid = b["groupId"].asText()
        action(authed, "LCG", bid, "start")
        val b1 = get(authed, "/api/projects/LCG/groups/$bid")
        assertEquals(200, action(authed, "LCG", bid, "backward").status.value())
        assertEquals(200, action(authed, "LCG", bid, "start").status.value())
        assertEquals(b1["startedAt"].asText(), get(authed, "/api/projects/LCG/groups/$bid")["startedAt"].asText())
    }

    @Test
    fun `生命周期派生——首末item取直挂成员min_max_空成员恒null`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "LCM")
        val s = createGroup(authed, "LCM", mapOf("typeKey" to "sprint", "name" to "M"))
        val gid = s["groupId"].asText()

        // 空成员:组走完生命周期后首末 item 仍 null
        assertEquals(200, action(authed, "LCM", gid, "start").status.value())
        assertEquals(200, action(authed, "LCM", gid, "complete").status.value())
        val dEmpty = get(authed, "/api/projects/LCM/groups/$gid")
        assertTrue(dEmpty["firstItemStartedAt"].isNull)
        assertTrue(dEmpty["lastItemCompletedAt"].isNull)

        // 新组 + 直挂成员,推进 item 生命周期:迁出 START → 首 item 开始;迁入 FINAL → 末 item 完成
        val m = createGroup(authed, "LCM", mapOf("typeKey" to "sprint", "name" to "M2"))
        val mid = m["groupId"].asText()
        action(authed, "LCM", mid, "start")
        val id = createItem(authed, "LCM", "i1")["itemId"].asText()
        authed
            .put()
            .uri("/api/items/$id/group-membership")
            .body(mapOf("groupId" to mid))
            .exchange()
            .expectStatus()
            .isOk()
        // YPJ-280:转非起始态缺负责人 409——先指派再迁移(同 LifecycleTimeApiTest.seedd)
        authed
            .put()
            .uri("/api/items/$id/assignee")
            .body(mapOf("assigneeItemId" to meMemberId(authed)))
            .exchange()
            .expectStatus()
            .isOk()
        val proj = get(authed, "/api/projects/LCM")
        val exitStartId = proj["statuses"][1]["statusId"].asText()
        val finalId = proj["statuses"][4]["statusId"].asText()

        assertEquals(200, patchStatus(authed, id, exitStartId))
        val itemDto = get(authed, "/api/items/$id")
        val dHalf = get(authed, "/api/projects/LCM/groups/$mid")
        assertEquals(itemDto["startedAt"].asText(), dHalf["firstItemStartedAt"].asText())
        assertTrue(dHalf["lastItemCompletedAt"].isNull)

        assertEquals(200, patchStatus(authed, id, finalId))
        val itemDone = get(authed, "/api/items/$id")
        val dFull = get(authed, "/api/projects/LCM/groups/$mid")
        assertEquals(itemDone["completedAt"].asText(), dFull["lastItemCompletedAt"].asText())
    }
}
