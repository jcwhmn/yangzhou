package yangzhou.api.business.item

import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-279(spec 0169):整列重排 apply 端点黑盒(宽容语义:去重后序覆盖/无效忽略/跨列合法/未列入接后/空 400)。 */
class BoardOrderApplyApiTest : AbstractApiTest() {
    private fun apply(
        authed: RestTestClient,
        key: String,
        itemIds: List<String>,
    ) = authed
        .post()
        .uri("/api/projects/$key/board-order/apply")
        .body(mapOf("itemIds" to itemIds))
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

    private fun bodyOf(exchange: RestTestClient.ResponseSpec) = json.readTree(
        exchange
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    private fun numbers(body: com.fasterxml.jackson.databind.JsonNode) = body["report"]["applied"].map { it.asText() }

    @Test
    fun `正例_全列重写_位次0起_落库一致_B1B6B7`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "BOA", seedSprint = false)
        val (a, b, c) = threeOnBoard(authed, "BOA")

        val body = bodyOf(apply(authed, "BOA", listOf(c, a, b)))
        // 列新序 c,a,b;位次 0..2
        assertEquals(
            listOf(c, a, b),
            body["ordered"].map { it["itemId"].asText() },
        )
        assertEquals(listOf(0, 1, 2), body["ordered"].map { it["boardOrder"].asInt() })
        assertEquals(listOf("BOA-3", "BOA-1", "BOA-2"), numbers(body))
        assertTrue(body["report"]["ignored"].isEmpty)
        // 回读持久化一致(move 端点视角同序)
        val db = bodyOf(
            authed
                .get()
                .uri("/api/projects/BOA/items")
                .exchange(),
        )
        val byId = db.associate { it["itemId"].asText() to (if (it["boardOrder"].isNull) -1 else it["boardOrder"].asInt()) }
        assertEquals(0, byId[c])
        assertEquals(1, byId[a])
        assertEquals(2, byId[b])
    }

    @Test
    fun `重复_后序覆盖前序_report带deduped_B3`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "BOB", seedSprint = false)
        val (a, b, c) = threeOnBoard(authed, "BOB")

        // a 出现两次,末次在后 → 生效序 b,a;早期 a 进 deduped
        val body = bodyOf(apply(authed, "BOB", listOf(a, b, a)))
        assertEquals(listOf("BOB-2", "BOB-1"), numbers(body))
        assertEquals(listOf("BOB-1"), body["report"]["deduped"].map { it.asText() })
        assertEquals(listOf(b, a, c), body["ordered"].map { it["itemId"].asText() }) // 去重后生效序 b,a;rest c 接后
    }

    @Test
    fun `无效项忽略_未上看板不参与_report带ignored_B4`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "BOC", seedSprint = false)
        val (a, _, _) = threeOnBoard(authed, "BOC")
        val ghost = createItem(authed, "BOC", "ghost")["itemId"].asText() // 未上板

        val body = bodyOf(apply(authed, "BOC", listOf(ghost, a, "00000000-0000-0000-0000-000000000000")))
        assertEquals(listOf("BOC-1"), numbers(body))
        val ignored = body["report"]["ignored"].map { it.asText() }
        assertEquals(2, ignored.size)
        assertTrue(ignored.contains("BOC-4"))
    }

    @Test
    fun `未列入成员_保持相对序接后_B1后半`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "BOD", seedSprint = false)
        val (a, b, c) = threeOnBoard(authed, "BOD")

        // 先 move 物化 b,a,c;再只重排 c 到最前 → c 在前,rest 按当前显示序 b,a
        authed
            .post()
            .uri("/api/projects/BOD/board-order/move")
            .body(mapOf("itemId" to b, "dir" to "up"))
            .exchange()
            .expectStatus()
            .isOk()
        val body = bodyOf(apply(authed, "BOD", listOf(c)))
        assertEquals(listOf(c, b, a), body["ordered"].map { it["itemId"].asText() })
        assertEquals(listOf("BOD-3"), numbers(body))
    }

    @Test
    fun `跨列合法_各列独立位次_B2`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "BOE", seedSprint = false)
        val (a, b, c) = threeOnBoard(authed, "BOE")
        // c 移到第二状态(换列):statuses 从项目详情取
        val project =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/BOE")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        val status2 = project["statuses"][1]["statusId"].asText()
        authed
            .put()
            .uri("/api/items/$c/assignee")
            .body(mapOf("assigneeItemId" to meMemberId(authed)))
            .exchange()
            .expectStatus()
            .isOk()
        authed
            .patch()
            .uri("/api/items/$c")
            .body(mapOf("statusItemId" to status2))
            .exchange()
            .expectStatus()
            .isOk()

        val body = bodyOf(apply(authed, "BOE", listOf(a, c, b)))
        assertEquals(listOf("BOE-1", "BOE-3", "BOE-2"), numbers(body))
        // ordered 汇总两列:第一列 a(0),b(1);第二列 c(0)
        val pos = body["ordered"].associate { it["itemId"].asText() to it["boardOrder"].asInt() }
        assertEquals(0, pos[a])
        assertEquals(1, pos[b])
        assertEquals(0, pos[c])
    }

    @Test
    fun `空列表400_全无效400_库无写入_B5`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "BOF", seedSprint = false)
        val (a, _, _) = threeOnBoard(authed, "BOF")

        apply(authed, "BOF", emptyList()).expectStatus().isBadRequest()
        apply(authed, "BOF", listOf("00000000-0000-0000-0000-000000000000")).expectStatus().isBadRequest()
        // 失败调用不产生任何写入
        val db =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/BOF/items")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        assertTrue(db.all { it["boardOrder"].isNull })
    }
}
