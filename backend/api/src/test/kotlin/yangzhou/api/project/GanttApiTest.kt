package yangzhou.api.project

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-121:V8-S2 甘特数据黑盒(树序/日期/超期)。 */
class GanttApiTest : AbstractApiTest() {

    @Test
    fun `甘特按树序返回——日期与超期正确——未知项目 404`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")

        // 父 item(带日期,超期)+ 子 item(不带日期)
        val parent = createItem(authed, "CHE", "父任务")
        authed.patch().uri("/api/items/${parent["itemId"].asText()}")
            .body(mapOf("startDate" to "2020-01-01", "dueDate" to "2020-01-31"))
            .exchange().expectStatus().isOk()
        createItem(authed, "CHE", "子任务", parentItemId = parent["itemId"].asText())

        val rows = json.readTree(
            authed.get().uri("/api/projects/CHE/gantt").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )["rows"]

        assertEquals(2, rows.size())
        // 树序:父在前,子在后
        assertEquals("父任务", rows[0]["title"].asText())
        assertEquals("子任务", rows[1]["title"].asText())
        assertEquals(rows[0]["itemId"].asText(), rows[1]["parentItemId"].asText())
        // 日期与超期(2020 due,非终态)
        assertEquals("2020-01-01", rows[0]["startDate"].asText())
        assertEquals("2020-01-31", rows[0]["dueDate"].asText())
        assertTrue(rows[0]["overdue"].asBoolean())
        // 子任务无日期 → 不超期
        assertTrue(!rows[1]["overdue"].asBoolean())
        assertEquals(1, rows[1]["depth"].asInt())

        authed.get().uri("/api/projects/NOPE/gantt")
            .exchange().expectStatus().isNotFound()
    }
}
