package yangzhou.api.business.itemgroup

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** spec 0099 起步,0117 收窄:拉入=S0 置标记 / 有组挂 sprint(最早 active → planned 并激活);退回=清标记+移出非 completed sprint 成员行。 */
class BoardMembershipApiTest : AbstractApiTest() {
    private fun setBoard(
        authed: RestTestClient,
        itemId: String,
        on: Boolean,
    ): String = authed
        .put()
        .uri("/api/items/$itemId/board-membership")
        .body(mapOf("onBoard" to on))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String::class.java)
        .returnResult()
        .responseBody!!

    private fun createGroup(
        authed: RestTestClient,
        key: String,
        body: Map<String, Any?>,
    ): JsonNode = json.readTree(
        authed
            .post()
            .uri("/api/projects/$key/groups")
            .body(body)
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    private fun assign(
        authed: RestTestClient,
        itemId: String,
        groupId: String,
    ) {
        authed
            .put()
            .uri("/api/items/$itemId/group-membership")
            .body(mapOf("groupId" to groupId, "groupType" to "sprint"))
            .exchange()
            .expectStatus()
            .isOk()
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

    @Test
    fun `无组S0——拉入置标记入看板_退回清标记`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP", seedSprint = false) // 真 S0:仅 builtin release,level=NULL 不参与
        val item = createItem(authed, "GRP", "板 item")["itemId"].asText()
        setBoard(authed, item, true)
        assertEquals(0, get(authed, "/api/projects/GRP/backlog").size())
        assertEquals(item, get(authed, "/api/projects/GRP/board")["items"][0]["itemId"].asText())
        assertTrue(get(authed, "/api/projects/GRP/board")["items"][0]["onBoard"].asBoolean())
        setBoard(authed, item, false)
        assertEquals(1, get(authed, "/api/projects/GRP/backlog").size())
        assertEquals(0, get(authed, "/api/projects/GRP/board")["items"].size()) // To Do 退回后既非终态也非标记
    }

    @Test
    fun `有 active sprint——拉入同时挂 sprint_退回清成员行`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val item = createItem(authed, "GRP", "冲刺 item")["itemId"].asText()
        val s1 = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "S1"))
        authed
            .post()
            .uri("/api/projects/GRP/groups/${s1["groupId"].asText()}/actions/start")
            .exchange()
            .expectStatus()
            .isOk()
        setBoard(authed, item, true)
        // 拉入 = 同时挂最早 active sprint:成员行落库;0117 起有组不置标记
        assertEquals(1, get(authed, "/api/projects/GRP/groups/${s1["groupId"].asText()}/items").size())
        assertTrue(!get(authed, "/api/projects/GRP/board")["items"][0]["onBoard"].asBoolean())
        setBoard(authed, item, false)
        // 退回 = 成员行清(非 completed)+ 回 backlog
        assertEquals(0, get(authed, "/api/projects/GRP/groups/${s1["groupId"].asText()}/items").size())
        assertEquals(1, get(authed, "/api/projects/GRP/backlog").size())
    }

    @Test
    fun `planned 成员退回——成员行清`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val item = createItem(authed, "GRP", "规划 item")["itemId"].asText()
        val s1 = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "S1"))
        assign(authed, item, s1["groupId"].asText())
        assertEquals(0, get(authed, "/api/projects/GRP/backlog").size()) // planned 成员不在 backlog
        setBoard(authed, item, false)
        assertEquals(0, get(authed, "/api/projects/GRP/groups/${s1["groupId"].asText()}/items").size())
        assertEquals(1, get(authed, "/api/projects/GRP/backlog").size())
    }

    @Test
    fun `幂等——重复拉入与退回`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val item = createItem(authed, "GRP", "幂等 item")["itemId"].asText()
        val s1 = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "S1"))
        authed
            .post()
            .uri("/api/projects/GRP/groups/${s1["groupId"].asText()}/actions/start")
            .exchange()
            .expectStatus()
            .isOk()
        setBoard(authed, item, true)
        setBoard(authed, item, true)
        assertEquals(1, get(authed, "/api/projects/GRP/groups/${s1["groupId"].asText()}/items").size()) // unique 不重
        setBoard(authed, item, false)
        setBoard(authed, item, false)
        assertEquals(1, get(authed, "/api/projects/GRP/backlog").size())
    }

    @Test
    fun `有组无active有planned——拉入挂planned并激活sprint`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
        val item = createItem(authed, "GRP", "空窗 item")["itemId"].asText()
        val s1 = createGroup(authed, "GRP", mapOf("typeKey" to "sprint", "name" to "S1"))
        setBoard(authed, item, true)
        // spec 0117 拍板:无 active 时挂最早 planned 并同事务激活(planned→active),空窗态不再存在
        assertEquals(1, get(authed, "/api/projects/GRP/groups/${s1["groupId"].asText()}/items").size())
        assertEquals("active", get(authed, "/api/projects/GRP/groups/${s1["groupId"].asText()}")["status"].asText())
        val board = get(authed, "/api/projects/GRP/board")
        assertEquals(item, board["items"][0]["itemId"].asText())
        assertTrue(!board["items"][0]["onBoard"].asBoolean())
        assertEquals(0, get(authed, "/api/projects/GRP/backlog").size())
    }

    @Test
    fun `有组零sprint——拉入409`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP") // 种了 sprint 类型但零组
        val item = createItem(authed, "GRP", "孤 item")["itemId"].asText()
        authed
            .put()
            .uri("/api/items/$item/board-membership")
            .body(mapOf("onBoard" to true))
            .exchange()
            .expectStatus()
            .isConflict()
        assertEquals(1, get(authed, "/api/projects/GRP/backlog").size())
    }

    @Test
    fun `S0建首个组类型——转换清标记票落backlog`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP", seedSprint = false)
        val item = createItem(authed, "GRP", "转换 item")["itemId"].asText()
        setBoard(authed, item, true)
        assertEquals(item, get(authed, "/api/projects/GRP/board")["items"][0]["itemId"].asText())
        // 转换点:创建首个组类型(必得 level-1)→ 清全项目标记
        authed
            .post()
            .uri("/api/projects/GRP/group-types")
            .body(mapOf("typeKey" to "sprint", "name" to "Sprint", "timed" to true))
            .exchange()
            .expectStatus()
            .isCreated()
        // 落 backlog = 标记已清(backlog 谓词排除标记);看板归零(无 sprint 成员无标记)
        assertEquals(1, get(authed, "/api/projects/GRP/backlog").size())
        assertEquals(0, get(authed, "/api/projects/GRP/board")["items"].size())
    }
}
