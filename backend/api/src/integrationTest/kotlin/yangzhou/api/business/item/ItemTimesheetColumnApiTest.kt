package yangzhou.api.business.item

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** YPJ-182(spec 0130):items 列表带时间合计——补录计入+进行中折算到 now;无工时=0。 */
class ItemTimesheetColumnApiTest : AbstractApiTest() {
    @Test
    fun `列表 totalMinutes——补录计入——进行中折算——无工时为零`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val withLog = createItem(authed, "CHE", "有工时")["itemId"].asText()
        val noLog = createItem(authed, "CHE", "无工时")["itemId"].asText()

        // 补录 60 分钟(成对起止走服务端;minutes 模式最简)
        authed
            .post()
            .uri("/api/items/$withLog/time-entries")
            .body(mapOf("minutes" to 60))
            .exchange()
            .expectStatus()
            .isCreated()
        // 开一条进行中计时(折算到 now,>=0)
        authed
            .post()
            .uri("/api/items/$withLog/time-entries")
            .body(mapOf<String, Any?>("minutes" to null))
            .exchange()
            .expectStatus()
            .isCreated()

        val rows = json.readTree(
            authed
                .get()
                .uri("/api/projects/CHE/items")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!,
        )
        val byTitle = rows.associateBy { it["title"].asText() }
        val a = byTitle["有工时"]!!["totalMinutes"].asLong()
        val b = byTitle["无工时"]!!["totalMinutes"].asLong()
        assertEquals(0, b)
        assertEquals(true, a >= 60, "补录 60 + 进行中折算应 >= 60,实得 $a")
    }
}
