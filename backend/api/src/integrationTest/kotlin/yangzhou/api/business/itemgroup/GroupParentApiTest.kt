package yangzhou.api.business.itemgroup

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
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
        val s1Id = s1["groupId"].asText()
        assertEquals(epicId, s1["parentGroupId"].asText())
        post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S2"))

        val children = get(authed, "/api/projects/$key/groups?parentId=$epicId")
        assertEquals(1, children.size())
        assertEquals("S1", children[0]["name"].asText())
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
        // 无子 epic:独立 sprint 动作不波及(它本无父子链),epic 保持 planned
        val (_, s) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "SX"))
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
        // 子组挂 level-1 组为父 → 400
        val (_, sprint0) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S0"))
        val (code, _) =
            post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "SX", "parentGroupId" to sprint0["groupId"].asText()))
        assertEquals(400, code)
    }

    @Test
    fun `parentGroupId invalid reference is rejected`() {
        val (authed, key) = setup("DEC111Z")
        val (code, _) =
            post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "S1", "parentGroupId" to "00000000-0000-0000-0000-000000000000"))
        assertEquals(404, code)
        assertEquals(0, get(authed, "/api/projects/$key/groups").size())
    }
}
