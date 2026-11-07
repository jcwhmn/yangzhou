package yangzhou.api.business.itemgroup

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** spec 0114(YPJ-163):多层嵌套泛化(封顶 3 层)——类型封顶/邻层建组不变量/非叶动作与挂 item 409/改父子树随迁+状态逐层级联/中层类型删除 409。 */
class GroupMultilayerApiTest : AbstractApiTest() {
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
        println("POST $path -> " + r.status.value() + " " + r.responseBody)
        return r.status.value() to json.readTree(r.responseBody ?: "{}")
    }

    private fun patch(
        authed: RestTestClient,
        path: String,
        body: Map<String, Any?>,
    ): Pair<Int, JsonNode> {
        val r =
            authed
                .patch()
                .uri(path)
                .body(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return r.status.value() to json.readTree(r.responseBody ?: "{}")
    }

    private fun put(
        authed: RestTestClient,
        path: String,
        body: Map<String, Any?>,
    ): Pair<Int, JsonNode> {
        val r =
            authed
                .put()
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
        return r.status.value() to json.readTree(r.responseBody ?: "{}")
    }

    /** 建项目 + 季(level-2) + 年(level-3) 类型;builtin sprint 已占 level-1。返回 (authed, key)。 */
    private fun setup(key: String): Pair<RestTestClient, String> {
        val authed = bootstrapAndAuth()
        createProject(authed, key) // builtin sprint = level 1(叶层)
        val (_, q) = post(authed, "/api/projects/$key/group-types", mapOf("typeKey" to "quarter", "name" to "季"))
        assertEquals(2, q["level"].asInt())
        val (_, y) = post(authed, "/api/projects/$key/group-types", mapOf("typeKey" to "year", "name" to "年"))
        assertEquals(3, y["level"].asInt())
        return authed to key
    }

    @Test
    fun `三层建组链成功且第4类型封顶400`() {
        val (authed, key) = setup("ML114A")
        val (_, y) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "year", "name" to "2026"))
        val yearId = y["groupId"].asText()
        val (_, q) =
            post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "quarter", "name" to "Q1", "parentGroupId" to yearId))
        val quarterId = q["groupId"].asText()
        assertEquals(yearId, q["parentGroupId"].asText())
        val (_, m) =
            post(
                authed,
                "/api/projects/$key/groups",
                mapOf("typeKey" to "sprint", "name" to "1月", "parentGroupId" to quarterId),
            )
        assertEquals(quarterId, m["parentGroupId"].asText())

        // 封顶:第 4 个 leveled 类型 → 400
        val (code4, _) = post(authed, "/api/projects/$key/group-types", mapOf("typeKey" to "x4", "name" to "四"))
        assertEquals(400, code4)
    }

    @Test
    fun `建组漏父与越级挂父均400`() {
        val (authed, key) = setup("ML114B")
        val (_, y) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "year", "name" to "2026"))
        val yearId = y["groupId"].asText()
        // 上层(年)存在 → 季漏父 400
        val (c1, _) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "quarter", "name" to "Q1"))
        assertEquals(400, c1)
        // 越级:sprint 直挂年(须邻层=季)→ 400
        val (c2, _) =
            post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "sprint", "name" to "1月", "parentGroupId" to yearId))
        assertEquals(400, c2)
    }

    @Test
    fun `非叶层手动动作与挂item均409`() {
        val (authed, key) = setup("ML114C")
        val (_, y) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "year", "name" to "2026"))
        val yearId = y["groupId"].asText()
        val (_, q) =
            post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "quarter", "name" to "Q1", "parentGroupId" to yearId))
        val quarterId = q["groupId"].asText()
        // 顶层/中层手动动作 → 409(状态由子组联动)
        assertEquals(409, action(authed, key, yearId, "start").first)
        assertEquals(409, action(authed, key, quarterId, "start").first)
        // 非叶层挂 item → 409
        val itemTitle = "ml-item"
        createItem(authed, key, itemTitle)
        val items = get(authed, "/api/projects/$key/items")
        val itemId = items.first()["itemId"].asText()
        val (cm, _) =
            put(authed, "/api/items/$itemId/group-membership", mapOf("groupId" to quarterId))
        assertEquals(409, cm)
    }

    @Test
    fun `改父泛化_顶层不可改_子树随迁_状态逐层级联`() {
        val (authed, key) = setup("ML114D")
        val (_, y1) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "year", "name" to "2025"))
        val (_, y2) = post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "year", "name" to "2026"))
        val year1 = y1["groupId"].asText()
        val year2 = y2["groupId"].asText()
        val (_, q) =
            post(authed, "/api/projects/$key/groups", mapOf("typeKey" to "quarter", "name" to "Q1", "parentGroupId" to year1))
        val quarterId = q["groupId"].asText()
        val (_, m) =
            post(
                authed,
                "/api/projects/$key/groups",
                mapOf("typeKey" to "sprint", "name" to "1月", "parentGroupId" to quarterId),
            )
        val monthId = m["groupId"].asText()

        // 顶层(年)改挂 → 400
        val (ct, _) = patch(authed, "/api/projects/$key/groups/$year1", mapOf("parentGroupId" to year2))
        assertEquals(400, ct)

        // sprint 激活 → 季、年 逐层级联 active
        assertEquals(200, action(authed, key, monthId, "start").first)
        assertEquals("active", get(authed, "/api/projects/$key/groups/$quarterId")["status"].asText())
        assertEquals("active", get(authed, "/api/projects/$key/groups/$year1")["status"].asText())

        // 季从 2025 改挂 2026 → 200,子树随迁(月仍指向季),新旧年状态联动重算
        val (cr, _) = patch(authed, "/api/projects/$key/groups/$quarterId", mapOf("parentGroupId" to year2))
        assertEquals(200, cr)
        val quarterAfter = get(authed, "/api/projects/$key/groups/$quarterId")
        assertEquals(year2, quarterAfter["parentGroupId"].asText())
        assertEquals(quarterId, get(authed, "/api/projects/$key/groups/$monthId")["parentGroupId"].asText())
        // 旧年 2025 失去唯一 active 链 → 回起点;新年 2026 获得 → active
        assertEquals("planned", get(authed, "/api/projects/$key/groups/$year1")["status"].asText())
        assertEquals("active", get(authed, "/api/projects/$key/groups/$year2")["status"].asText())
    }

    @Test
    fun `删中层类型_上层存在时409`() {
        val (authed, key) = setup("ML114E")
        // 年(level-3)存在 → 删季(level-2)409
        val r =
            authed
                .delete()
                .uri("/api/projects/$key/group-types/quarter")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(409, r.status.value())
    }
}
