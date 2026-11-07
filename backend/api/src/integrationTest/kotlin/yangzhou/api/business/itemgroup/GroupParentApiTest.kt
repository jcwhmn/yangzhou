package yangzhou.api.business.itemgroup

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** spec 0111(YPJ-111):固定两层组嵌套——parent_object_id、树查询过滤、子组动作/删除联动父组状态、level-2 组禁手动动作/禁挂 item。 */
class GroupParentApiTest : AbstractApiTest() {
    private fun post(
        authed: RestTestClient,
        path: String,
        body: Map<String, Any?>,
    ): Pair<Int, JsonNode> {
        val r =
            authed
                .post()
                .uri(path)
                .body(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return r.status.value() to json.readTree(r.responseBody ?: "{}")
    }

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

    private fun action(
        authed: RestTestClient,
        key: String,
        groupId: String,
        function: String,
    ): Pair<Int, JsonNode> {
        val r =
            authed
                .post()
                .uri("/api/projects/$key/groups/$groupId/actions/$function")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        println("ACTION $function -> " + r.status.value() + " " + r.responseBody)
        return r.status.value() to json.readTree(r.responseBody ?: "{}")
    }

    /** 项目 + sprint(level-1) + epic(level-2)类型;返回 (authed, key)。 */
    private fun setup(key: String): Pair<RestTestClient, String> {
        val authed = bootstrapAndAuth()
        createProject(authed, key) // 已种 sprint level-1
        val (code, body) =
            post(authed, "/api/projects/$key/group-types", mapOf("typeKey" to "epic", "name" to "Epic"))
        assertEquals(201, code, body.toString())
        return authed to key
    }

    @Test
    fun `child with parent is listed with parentGroupId and parentId filter works`() {
        val (authed, key) = setup("DEC111Z")
        val (_, epic) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "Epic 1"))
        val epicId = epic["groupId"].asText()
        val (_, s1) =
            post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S1", "parentGroupId" to epicId))
        assertEquals(epicId, s1["parentGroupId"].asText())
        // spec 0112 不变量:两级项目新建叶层组必挂父 → S2 挂同父
        post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S2", "parentGroupId" to epicId))

        val children = get(authed, "/api/projects/$key/groups?parentId=$epicId")
        assertEquals(2, children.size())
        // 无父过滤 = 全量 3 组
        assertEquals(3, get(authed, "/api/projects/$key/groups").size())
    }

    @Test
    fun `child actions propagate parent status`() {
        val (authed, key) = setup("DEC111Z")
        val (_, epic) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "E1"))
        val epicId = epic["groupId"].asText()
        val (_, s1) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S1", "parentGroupId" to epicId))
        val s1Id = s1["groupId"].asText()
        val (_, s2) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S2", "parentGroupId" to epicId))
        val s2Id = s2["groupId"].asText()

        fun epicStatus(): String = get(authed, "/api/projects/$key/groups/$epicId")["status"].asText()

        assertEquals("planned", epicStatus()) // 初始:子全 planned(有子、无 active、未全 final)→ START
        assertEquals(200, action(authed, key, s1Id, "start").first)
        assertEquals("active", epicStatus()) // 任一子 ACTIVE → 父 ACTIVE
        assertEquals(200, action(authed, key, s2Id, "start").first)
        assertEquals(200, action(authed, key, s1Id, "complete").first)
        assertEquals("active", epicStatus()) // 还有子 active
        assertEquals(200, action(authed, key, s2Id, "complete").first)
        assertEquals("completed", epicStatus()) // 全子 FINAL → 父 FINAL
        assertEquals(200, action(authed, key, s1Id, "reopen").first)
        assertEquals("active", epicStatus()) // 重开回落
    }

    @Test
    fun `parent without children is not touched by propagation`() {
        val (authed, key) = setup("DEC111Z")
        val (_, epic) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "E0"))
        val epicId = epic["groupId"].asText()
        // 无子 epic:E1 下的子组动作不波及 E0(epic 保持 planned)
        val (_, e1) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "E1"))
        val (_, s) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "SX", "parentGroupId" to e1["groupId"].asText()))
        assertEquals(200, action(authed, key, s["groupId"].asText(), "start").first)
        assertEquals("planned", get(authed, "/api/projects/$key/groups/$epicId")["status"].asText())
    }

    @Test
    fun `deleting child recomputes parent`() {
        val (authed, key) = setup("DEC111Z")
        val (_, epic) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "E1"))
        val epicId = epic["groupId"].asText()
        val (_, s1) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S1", "parentGroupId" to epicId))
        val s1Id = s1["groupId"].asText()
        action(authed, key, s1Id, "start")
        assertEquals("active", get(authed, "/api/projects/$key/groups/$epicId")["status"].asText())
        authed.delete().uri("/api/projects/$key/groups/$s1Id").exchange().expectStatus().isNoContent()
        assertEquals("active", get(authed, "/api/projects/$key/groups/$epicId")["status"].asText()) // 删到无子 → 规则「无子不动」,父保持
    }

    @Test
    fun `delete parent with children is rejected`() {
        val (authed, key) = setup("DEC111Z")
        val (_, epic) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "E1"))
        val epicId = epic["groupId"].asText()
        post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S1", "parentGroupId" to epicId))
        val r = authed.delete().uri("/api/projects/$key/groups/$epicId").exchange().expectBody(String::class.java).returnResult()
        assertEquals(409, r.status.value())
    }

    @Test
    fun `level2 group rejects manual action membership and nesting`() {
        val (authed, key) = setup("DEC111Z")
        val (_, epic) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "E1"))
        val epicId = epic["groupId"].asText()

        // 手动动作 409
        assertEquals(409, action(authed, key, epicId, "start").first)
        // level-2 组挂 item 409
        val (_, item) = post(authed, "/api/projects/$key/items", mapOf("title" to "t1"))
        val itemId = item["itemId"].asText()
        val mr =
            authed
                .put()
                .uri("/api/items/$itemId/group-membership")
                .body(mapOf("groupId" to epicId))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(409, mr.status.value())
        // level-2 组建组带 parentGroupId → 400(仅叶层可挂父)
        val (e2code, _) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "E2", "parentGroupId" to epicId))
        assertEquals(400, e2code)
        // 子组挂 level-1 组为父 → 400(S0 先合法挂 E0,不变量下叶层组必有父)
        val (_, e0) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "E0"))
        val (_, sprint0) =
            post(
                authed,
                "/api/projects/$key/groups",
                mapOf("typeKey" to "sprint", "name" to "S0", "parentGroupId" to e0["groupId"].asText()),
            )
        val (code, _) =
            post(
                authed,
                "/api/projects/$key/groups",
                mapOf("typeKey" to "sprint", "name" to "SX", "parentGroupId" to sprint0["groupId"].asText()),
            )
        assertEquals(400, code)
    }

    @Test
    fun `two-level project requires parent on new leaf group and single level stays flat`() {
        val (authed, key) = setup("DEC111H")
        val (code, _) =
            post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "SX"))
        assertEquals(400, code)
        // 纯 level-1 项目(未配 epic):无父照旧
        val key2 = key + "F"
        createProject(authed, key2)
        val (code2, _) =
            post(authed, "/api/projects/$key2/groups", mapOf("typeKey" to "sprint", "name" to "S1"))
        assertEquals(201, code2)
    }

    @Test
    fun `parentGroupId invalid reference is rejected`() {
        val (authed, key) = setup("DEC111Z")
        val (code, _) =
            post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S1", "parentGroupId" to "00000000-0000-0000-0000-000000000000"))
        assertEquals(404, code)
        assertEquals(0, get(authed, "/api/projects/$key/groups").size())
    }

    // ---------- spec 0113(YPJ-160):组改父 ----------

    private fun patch(
        authed: RestTestClient,
        key: String,
        groupId: String,
        body: Map<String, Any?>,
    ): Pair<Int, JsonNode> {
        val r =
            authed
                .patch()
                .uri("/api/projects/$key/groups/$groupId")
                .body(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return r.status.value() to json.readTree(r.responseBody ?: "{}")
    }

    @Test
    fun `reparent moves leaf group and recomputes both parents`() {
        val (authed, key) = setup("DEC160A")
        val (_, e1) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "E1"))
        val e1Id = e1["groupId"].asText()
        val (_, e2) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "E2"))
        val e2Id = e2["groupId"].asText()
        val (_, s1) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S1", "parentGroupId" to e1Id))
        val s1Id = s1["groupId"].asText()
        post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S2", "parentGroupId" to e1Id))
        fun status(id: String): String = get(authed, "/api/projects/$key/groups/$id")["status"].asText()
        assertEquals(200, action(authed, key, s1Id, "start").first)
        assertEquals("active", status(e1Id))
        assertEquals("planned", status(e2Id))
        // S1(active)从 E1 改挂 E2 → 旧父剩 planned 子回 START,新父收 active 子
        val (code, body) = patch(authed, key, s1Id, mapOf("parentGroupId" to e2Id))
        assertEquals(200, code, body.toString())
        assertEquals(e2Id, body["parentGroupId"].asText())
        assertEquals("planned", status(e1Id))
        assertEquals("active", status(e2Id))
    }

    @Test
    fun `reparent rejects invalid targets`() {
        val (authed, key) = setup("DEC160B")
        val (_, e1) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "E1"))
        val e1Id = e1["groupId"].asText()
        val (_, s1) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S1", "parentGroupId" to e1Id))
        val s1Id = s1["groupId"].asText()
        val (_, s2) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S2", "parentGroupId" to e1Id))
        val s2Id = s2["groupId"].asText()
        // 目标为 level-1 组 → 400
        assertEquals(400, patch(authed, key, s1Id, mapOf("parentGroupId" to s2Id)).first)
        // 目标不存在 → 404
        assertEquals(404, patch(authed, key, s1Id, mapOf("parentGroupId" to "00000000-0000-0000-0000-000000000000")).first)
        // 自身为父层组 → 400
        assertEquals(400, patch(authed, key, e1Id, mapOf("parentGroupId" to s1Id)).first)
        // 非法串(含置空语义)→ 400
        assertEquals(400, patch(authed, key, s1Id, mapOf("parentGroupId" to " ")).first)
    }

    @Test
    fun `reparent rejects target that has a parent`() {
        val (authed, key) = setup("DEC160C")
        val (_, e1) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "E1"))
        val e1Id = e1["groupId"].asText()
        val (_, e2) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "E2"))
        val e2Id = e2["groupId"].asText()
        val (_, s1) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S1", "parentGroupId" to e1Id))
        val s1Id = s1["groupId"].asText()
        // 存量 grandfather 模拟:jdbc 令 E2 挂 E1(正常创建拒绝该形态)
        jdbc.update("update item_group set parent_object_id = ? where object_id = ?", UUID.fromString(e1Id), UUID.fromString(e2Id))
        assertEquals(400, patch(authed, key, s1Id, mapOf("parentGroupId" to e2Id)).first)
    }

    @Test
    fun `reparent null keeps parent and same value is noop`() {
        val (authed, key) = setup("DEC160D")
        val (_, e1) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "epic", "name" to "E1"))
        val e1Id = e1["groupId"].asText()
        val (_, s1) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S1", "parentGroupId" to e1Id))
        val s1Id = s1["groupId"].asText()
        assertEquals(200, action(authed, key, s1Id, "start").first)
        // 无 parentGroupId 字段 → 父不动
        val (c1, b1) = patch(authed, key, s1Id, mapOf("name" to "S1-renamed"))
        assertEquals(200, c1, b1.toString())
        assertEquals("S1-renamed", b1["name"].asText())
        assertEquals(e1Id, b1["parentGroupId"].asText())
        assertEquals("active", get(authed, "/api/projects/$key/groups/$e1Id")["status"].asText())
        // 同值 → 200 no-op,不触发联动
        val (c2, b2) = patch(authed, key, s1Id, mapOf("parentGroupId" to e1Id))
        assertEquals(200, c2, b2.toString())
        assertEquals(e1Id, b2["parentGroupId"].asText())
        assertEquals("active", get(authed, "/api/projects/$key/groups/$e1Id")["status"].asText())
    }
}
