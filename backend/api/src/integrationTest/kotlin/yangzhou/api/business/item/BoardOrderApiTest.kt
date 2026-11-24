package yangzhou.api.business.item

import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-238(spec 0166 §3.1):箭头排序 move 端点黑盒(交换位次/沉底物化/边界无操作/负例/级联清)。 */
class BoardOrderApiTest : AbstractApiTest() {
    private fun move(
        authed: RestTestClient,
        key: String,
        itemId: String,
        dir: String,
    ) = authed
        .post()
        .uri("/api/projects/$key/board-order/move")
        .body(mapOf("itemId" to itemId, "dir" to dir))
        .exchange()

    /** 建三张已上板票(S0 项目:拉入=置标记),返回 [a,b,c] 的 itemId。 */
    private fun threeOnBoard(
        authed: RestTestClient,
        key: String,
    ): List<String> = listOf("a", "b", "c").map { t ->
        val id = createItem(authed, key, t)["itemId"].asText()
        authed
            .put()
            .uri("/api/items/$id/board-membership")
            .body(mapOf("onBoard" to true))
            .exchange()
            .expectStatus()
            .isOk()
        id
    }

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

    private fun orderedOf(
        authed: RestTestClient,
        key: String,
        itemId: String,
        dir: String,
    ): List<Pair<String, Int?>> {
        val body =
            json.readTree(
                move(authed, key, itemId, dir)
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        return body["ordered"].map { it["itemId"].asText() to (if (it["boardOrder"].isNull) null else it["boardOrder"].asInt()) }
    }

    @Test
    fun `双null物化_再双有行纯swap_往返还原`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "BOR", seedSprint = false)
        val (a, b, c) = threeOnBoard(authed, "BOR")

        // 未排过:boardOrder 全 null
        assertTrue(items(authed, "BOR").all { it["boardOrder"].isNull })

        // b 上移:首触物化全列(c 也得位次 2),再 a,b 位次互换 → 序 b,a,c
        val r1 = orderedOf(authed, "BOR", b, "up")
        assertEquals(listOf(b to 0, a to 1, c to 2), r1)

        // c 上移:c(null)升位继承 a 位次 1,a 降位 max+1=2 → 序 b,c,a
        val r2 = orderedOf(authed, "BOR", c, "up")
        assertEquals(listOf(b to 0, c to 1, a to 2), r2)

        // a 上移(双有行纯 swap):a(2) 与前项 c(1) 位次互换 → 序 b,a,c
        val r3 = orderedOf(authed, "BOR", a, "up")
        assertEquals(listOf(b to 0, a to 1, c to 2), r3)

        // b 下移:b(0) 与后项 a(1) 互换 → 序 a,b,c(往返还原)
        val r4 = orderedOf(authed, "BOR", b, "down")
        assertEquals(listOf(a to 0, b to 1, c to 2), r4)

        // 回读持久化与响应一致
        val db = items(authed, "BOR").associate { it["itemId"].asText() to it["boardOrder"].asInt() }
        assertEquals(0, db[a])
        assertEquals(1, db[b])
        assertEquals(2, db[c])
    }

    @Test
    fun `边界_列首up与列尾down无操作_响应仍带全列`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "BO4", seedSprint = false)
        val (a, b, c) = threeOnBoard(authed, "BO4")

        val r1 = orderedOf(authed, "BO4", a, "up")
        assertEquals(listOf(a, b, c), r1.map { it.first }) // 序不变
        val r2 = orderedOf(authed, "BO4", c, "down")
        assertEquals(listOf(a, b, c), r2.map { it.first }) // 序不变
        // 无操作不建行
        assertTrue(items(authed, "BO4").all { it["boardOrder"].isNull })
    }

    @Test
    fun `负例_不存在item404_dir非法400`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "BO5", seedSprint = false)
        val id = createItem(authed, "BO5", "x")["itemId"].asText()

        move(authed, "BO5", "11111111-1111-1111-1111-111111111111", "up")
            .expectStatus().isNotFound()
        move(authed, "BO5", id, "sideways")
            .expectStatus().isBadRequest()
    }

    @Test
    fun `item 删除级联清 order 行`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "BO3", seedSprint = false)
        val (a, b, _) = threeOnBoard(authed, "BO3")
        orderedOf(authed, "BO3", b, "up") // b,a 双 null 物化 → 两行都有

        jdbc.update("delete from item where object_id = ?::uuid", a)
        val cnt =
            jdbc
                .queryForMap("select count(*) as c from board_item_order where item_object_id = '$a'")
                .get("c")
        assertEquals(0L, cnt)
    }
}
