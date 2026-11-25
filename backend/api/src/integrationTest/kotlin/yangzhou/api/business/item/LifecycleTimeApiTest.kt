package yangzhou.api.business.item

import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** YPJ-132(spec 0171):生命周期时刻纯派生——startedAt=首次迁出 START,completedAt=最近迁入 FINAL(甘特 0150 同口径);DTO 与甘特行同值。 */
class LifecycleTimeApiTest : AbstractApiTest() {
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

    /** 建一张已上板、已指派我的票(S0 项目),返回 itemId 与状态表 [startId, midId, finalId]。 */
    private fun seeded(
        authed: RestTestClient,
        key: String,
    ): Triple<String, String, Pair<String, String>> {
        val id = createItem(authed, key, "t-$key")["itemId"].asText()
        authed
            .put()
            .uri("/api/items/$id/assignee")
            .body(mapOf("assigneeItemId" to meMemberId(authed)))
            .exchange()
            .expectStatus()
            .isOk()
        val project =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/$key")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        return Triple(id, project["statuses"][0]["statusId"].asText(), project["statuses"][1]["statusId"].asText() to project["statuses"][4]["statusId"].asText())
    }

    private fun dto(
        authed: RestTestClient,
        itemId: String,
    ) = json.readTree(
        authed
            .get()
            .uri("/api/items/$itemId")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    @Test
    fun `B1B2_首次迁出START记startedAt_完成前completedAt为null`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "LC1", seedSprint = false)
        val (id, startId, pair) = seeded(authed, "LC1")

        // B6 前置:仍在 START 时两值均 null
        assertTrue(dto(authed, id)["startedAt"].isNull)
        assertTrue(dto(authed, id)["completedAt"].isNull)

        assertEquals(200, patchStatus(authed, id, pair.first))
        val d1 = dto(authed, id)
        assertNotNull(d1["startedAt"].takeIf { !it.isNull }?.asText())
        assertTrue(d1["completedAt"].isNull)
        // START 自身再迁 START 类不动(B2 的同列变体:再迁另一 ACTIVE)
        assertEquals(200, patchStatus(authed, id, pair.second))
        val d2 = dto(authed, id)
        assertEquals(d1["startedAt"].asText(), d2["startedAt"].asText()) // 首次口径
    }

    @Test
    fun `B3B5_迁入FINAL记completedAt_重开保留_返工覆盖`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "LC2", seedSprint = false)
        val (id, _, pair) = seeded(authed, "LC2")
        val (midId, finalId) = pair

        patchStatus(authed, id, midId)
        patchStatus(authed, id, finalId)
        val done1 = dto(authed, id)["completedAt"].asText()

        // 重开:completedAt 保留末次完成(B3)
        patchStatus(authed, id, midId)
        val d = dto(authed, id)
        assertEquals(done1, d["completedAt"].asText())

        // 返工再完成:覆盖为最近(B5)
        patchStatus(authed, id, finalId)
        val done2 = dto(authed, id)["completedAt"].asText()
        assertTrue(done2 >= done1)
    }

    @Test
    fun `B4_打回待办_startedAt保留首次`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "LC3", seedSprint = false)
        val (id, startId, pair) = seeded(authed, "LC3")

        patchStatus(authed, id, pair.first)
        val first = dto(authed, id)["startedAt"].asText()
        patchStatus(authed, id, startId) // 打回待办
        assertEquals(first, dto(authed, id)["startedAt"].asText())
    }

    @Test
    fun `B6_始终START_两值均null`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "LC4", seedSprint = false)
        val (id, _, _) = seeded(authed, "LC4")
        val d = dto(authed, id)
        assertTrue(d["startedAt"].isNull)
        assertTrue(d["completedAt"].isNull)
    }

    @Test
    fun `甘特行与DTO同值_同数据两出口一致`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "LC5", seedSprint = false)
        val (id, _, pair) = seeded(authed, "LC5")
        patchStatus(authed, id, pair.first)
        patchStatus(authed, id, pair.second)

        val d = dto(authed, id)
        val gantt =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/LC5/gantt")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        val row = gantt["rows"].firstOrNull { it["itemId"].asText() == id }!!
        assertEquals(d["startedAt"].asText(), row["actualStart"].asText())
        assertEquals(d["completedAt"].asText(), row["actualEnd"].asText())
    }
}
