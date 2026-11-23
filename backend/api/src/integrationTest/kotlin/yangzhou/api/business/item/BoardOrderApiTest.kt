package yangzhou.api.business.item

import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-238(spec 0166):看板列内手工排序黑盒(按列重排/400 校验/级联清)。 */
class BoardOrderApiTest : AbstractApiTest() {
    private fun put(
        authed: RestTestClient,
        key: String,
        status: String,
        ids: List<String>,
    ) = authed
        .put()
        .uri("/api/projects/$key/board-order")
        .body(mapOf("status" to status, "orderedItemIds" to ids))
        .exchange()

    private fun items(
        authed: RestTestClient,
        key: String,
    ) = json.readTree(
        authed
            .get()
            .uri("/api/projects/$key/items")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    @Test
    fun `重排生效_回读 boardOrder_按列全列重写`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "BOR")
        val a = createItem(authed, "BOR", "a")["itemId"].asText()
        val b = createItem(authed, "BOR", "b")["itemId"].asText()
        val c = createItem(authed, "BOR", "c")["itemId"].asText()

        // 未排过:boardOrder 全 null
        assertTrue(items(authed, "BOR").all { it["boardOrder"].isNull })

        // 列内重排:c, a, b
        put(authed, "BOR", "To Do", listOf(c, a, b)).expectStatus().isOk()
        val byId = items(authed, "BOR").associate { it["itemId"].asText() to it["boardOrder"].asInt() }
        assertEquals(0, byId[c])
        assertEquals(1, byId[a])
        assertEquals(2, byId[b])

        // 再排子集:只传 a → 该列未列入的既有行被删(不残留僵尸)
        put(authed, "BOR", "To Do", listOf(a)).expectStatus().isOk()
        val trimmed = items(authed, "BOR").associate { it["itemId"].asText() to it["boardOrder"] }
        assertEquals(0, trimmed[a]!!.asInt())
        assertTrue(trimmed[b]!!.isNull)
        assertTrue(trimmed[c]!!.isNull)
    }

    @Test
    fun `校验_非法 item 与非法状态 400`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "BO2")
        createItem(authed, "BO2", "x")

        put(authed, "BO2", "To Do", listOf("11111111-1111-1111-1111-111111111111"))
            .expectStatus()
            .isBadRequest()
        put(authed, "BO2", "不存在状态", listOf())
            .expectStatus()
            .isBadRequest()
    }

    @Test
    fun `item 删除级联清 order 行`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "BO3")
        val id = createItem(authed, "BO3", "将删")["itemId"].asText()
        put(authed, "BO3", "To Do", listOf(id)).expectStatus().isOk()

        jdbc.update("delete from item where object_id = ?::uuid", id)
        val cnt =
            jdbc
                .queryForMap("select count(*) as c from board_item_order where item_object_id = '$id'")
                .get("c")
        assertEquals(0L, cnt)
    }
}
