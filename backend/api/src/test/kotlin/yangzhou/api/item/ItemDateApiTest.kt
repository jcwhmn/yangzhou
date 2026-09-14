package yangzhou.api.item

import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-120:V8-S1 日期字段黑盒(设置/清除/校验/超期/不变语义/活动留痕)。 */
class ItemDateApiTest : AbstractApiTest() {

    private fun setup(): Pair<RestTestClient, String> {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val itemId = createItem(authed, "CHE", "x")["itemId"].asText()
        return authed to itemId
    }

    private fun detail(authed: RestTestClient, itemId: String) = json.readTree(
        authed.get().uri("/api/items/$itemId").exchange()
            .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
    )

    @Test
    fun `设置日期回显——未传不变——空串清除——start 大于 due 400`() {
        val (authed, itemId) = setup()

        // 设置
        authed.patch().uri("/api/items/$itemId")
            .body(mapOf("startDate" to "2026-09-01", "dueDate" to "2026-09-30"))
            .exchange().expectStatus().isOk()
        assertEquals("2026-09-01", detail(authed, itemId)["startDate"].asText())
        assertEquals("2026-09-30", detail(authed, itemId)["dueDate"].asText())

        // 不传日期字段 → 保持不变
        authed.patch().uri("/api/items/$itemId")
            .body(mapOf("title" to "改名了"))
            .exchange().expectStatus().isOk()
        assertEquals("2026-09-30", detail(authed, itemId)["dueDate"].asText())

        // start > due → 400
        authed.patch().uri("/api/items/$itemId")
            .body(mapOf("startDate" to "2026-10-01"))
            .exchange().expectStatus().isBadRequest()

        // 空串清除
        authed.patch().uri("/api/items/$itemId")
            .body(mapOf("startDate" to "", "dueDate" to ""))
            .exchange().expectStatus().isOk()
        assertTrue(detail(authed, itemId)["startDate"].isNull)

        // 活动留痕:dates_changed
        val kinds = json.readTree(
            authed.get().uri("/api/items/$itemId/activity").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).map { it["kind"].asText() }
        assertTrue("dates_changed" in kinds)
    }

    @Test
    fun `超期标识——due 过去且非终态 true,终态 false,无 due false`() {
        val (authed, itemId) = setup()
        val statuses = json.readTree(
            authed.get().uri("/api/projects/CHE").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )["statuses"]

        authed.patch().uri("/api/items/$itemId")
            .body(mapOf("dueDate" to "2020-01-01")) // 明显过去
            .exchange().expectStatus().isOk()
        assertTrue(detail(authed, itemId)["overdue"].asBoolean())

        // 移到终态 → 不算超期(先指派:开工须有主)
        val meId = json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { !it["virtual"].asBoolean() }["memberId"].asText()
        authed.put().uri("/api/items/$itemId/assignee")
            .body(mapOf("assigneeItemId" to meId))
            .exchange().expectStatus().isOk()
        authed.patch().uri("/api/items/$itemId")
            .body(mapOf("statusItemId" to statuses[4]["statusId"].asText()))
            .exchange().expectStatus().isOk()
        assertTrue(!detail(authed, itemId)["overdue"].asBoolean())

        // 无 due → false
        val item2 = createItem(authed, "CHE", "y")
        assertTrue(!detail(authed, item2["itemId"].asText())["overdue"].asBoolean())
    }
}
