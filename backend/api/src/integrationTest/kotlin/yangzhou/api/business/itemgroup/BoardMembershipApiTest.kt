package yangzhou.api.business.itemgroup

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** spec 0099:看板拉入/退回(on_board 标记)——拉入=置标记(+挂最早 active sprint);退回=清标记+移出非 completed sprint 成员行。 */
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
    fun `无组——拉入出 backlog 入看板_退回复原`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GRP")
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
        // 拉入 = 同时挂最早 active sprint:成员行落库
        assertEquals(1, get(authed, "/api/projects/GRP/groups/${s1["groupId"].asText()}/items").size())
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
}
