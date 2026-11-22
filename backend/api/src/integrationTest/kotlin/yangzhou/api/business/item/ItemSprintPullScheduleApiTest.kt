package yangzhou.api.business.item

import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-216(spec 0164):sprint 拉入排期守卫——无预估 409 / 决策序(显式>存量>前置可推>今天)/ 前置全未排 409 / due 铺算与覆盖。 */
class ItemSprintPullScheduleApiTest : AbstractApiTest() {
    private fun setup(workMode: Boolean = false): RestTestClient {
        val authed = bootstrapAndAuth()
        createProject(authed, "SPG")
        if (workMode) {
            authed
                .patch()
                .uri("/api/projects/SPG/settings")
                .body(mapOf("workMode" to "work", "workdayStart" to "09:00", "workdayEnd" to "18:00"))
                .exchange()
                .expectStatus()
                .isOk()
        }
        return authed
    }

    private fun sprint(
        authed: RestTestClient,
        name: String = "S1",
    ): String = json
        .readTree(
            authed
                .post()
                .uri("/api/projects/SPG/groups")
                .body(mapOf("typeKey" to "sprint", "name" to name))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!,
        )["groupId"].asText()

    private fun item(
        authed: RestTestClient,
        title: String,
        estimate: Pair<String, String>? = null,
    ): String {
        val body = mutableMapOf<String, Any>("title" to title)
        if (estimate != null) {
            body["estimateValue"] = estimate.first
            body["estimateUnit"] = estimate.second
        }
        return json
            .readTree(
                authed
                    .post()
                    .uri("/api/projects/SPG/items")
                    .body(body)
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )["itemId"].asText()
    }

    private fun patch(
        authed: RestTestClient,
        itemId: String,
        body: Map<String, Any>,
    ): String = authed
        .patch()
        .uri("/api/items/$itemId")
        .body(body)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String::class.java)
        .returnResult()
        .responseBody!!

    /** 拉入并断言状态码(回填断言走 detail)。 */
    private fun pull(
        authed: RestTestClient,
        itemId: String,
        sprintId: String,
        expect: Int = 200,
    ) {
        authed
            .put()
            .uri("/api/items/$itemId/group-membership")
            .body(mapOf("groupId" to sprintId))
            .exchange()
            .expectStatus()
            .isEqualTo(expect)
    }

    private fun detail(
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
    fun `无预估拉入 409`() {
        val authed = setup()
        val sid = sprint(authed)
        val id = item(authed, "无预估票")
        pull(authed, id, sid, expect = 409)
        assertTrue(detail(authed, id)["startDate"].isNull) // 无预估不落任何日期
    }

    @Test
    fun `有预估无前置——start=现在钳工作时刻(不落过去),due=铺算回填`() {
        val authed = setup()
        val sid = sprint(authed)
        val id = item(authed, "今天票", "8" to "hour")
        pull(authed, id, sid)
        val d = detail(authed, id)
        // 新建项目默认工作制+全天窗:start=现在(周末顺延周一),due=start+8h;关键断言=due 在未来(不立刻超期)
        val start = java.time.LocalDateTime.parse(d["startDate"].asText())
        val due = java.time.LocalDateTime.parse(d["dueDate"].asText())
        assertTrue(!start.isBefore(java.time.LocalDateTime.now().minusMinutes(1)), "start 应为现在/未来:$start")
        assertTrue(due.isAfter(start), "due 应晚于 start")
        assertTrue(java.time.Duration.between(start, due).toMinutes() == 480L, "8h 铺算:${start}~${due}")
    }

    @Test
    fun `已有排期票拉入——保留不重排`() {
        val authed = setup()
        val sid = sprint(authed)
        val id = item(authed, "已排票", "5" to "hour")
        patch(authed, id, mapOf("startDate" to "2026-12-01", "dueDate" to "2026-12-02T12:00"))
        pull(authed, id, sid)
        val d = detail(authed, id)
        assertEquals("2026-12-01T00:00", d["startDate"].asText())
        assertEquals("2026-12-02T12:00", d["dueDate"].asText())
    }

    @Test
    fun `前置已排——start=最晚前置 due+1 顺延工作时刻(工作制跳周末)`() {
        val authed = setup(workMode = true)
        val sid = sprint(authed)
        // 前置 A:周五(2026-11-27)排期(date-only due → 窗终点 18:00)
        val a = item(authed, "前置A")
        patch(authed, a, mapOf("startDate" to "2026-11-27", "dueDate" to "2026-11-27"))
        val b = item(authed, "后继B", "8" to "hour")
        authed
            .post()
            .uri("/api/items/$b/dependencies")
            .body(mapOf("dependsOnItemId" to a))
            .exchange()
            .expectStatus()
            .isCreated()
        pull(authed, b, sid)
        val d = detail(authed, b)
        assertEquals("2026-11-30T09:00", d["startDate"].asText()) // Fri 18:00+1min → 下工作日周一窗起
        assertEquals("2026-11-30T17:00", d["dueDate"].asText()) // 9-18 窗 8h 铺算 = 当日 17:00
    }

    @Test
    fun `前置全未排——409`() {
        val authed = setup()
        val sid = sprint(authed)
        val a = item(authed, "未排前置")
        val b = item(authed, "后继", "4" to "hour")
        authed
            .post()
            .uri("/api/items/$b/dependencies")
            .body(mapOf("dependsOnItemId" to a))
            .exchange()
            .expectStatus()
            .isCreated()
        pull(authed, b, sid, expect = 409)
    }
}
