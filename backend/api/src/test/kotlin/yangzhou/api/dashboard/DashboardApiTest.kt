package yangzhou.api.dashboard

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** YPJ-2 全局驾驶舱黑盒:跨项目聚合我的三桶(active sprint/in_progress milestone)。 */
class DashboardApiTest : AbstractApiTest() {

    @Test
    fun `跨项目聚合——三桶与在途组_无在途项目不出现`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "DASH")
        createProject(authed, "QUIET")

        // DASH:指给我的 item + active sprint + in_progress milestone
        val x = createItem(authed, "DASH", "驾驶舱任务")["itemId"].asText()
        authed.put().uri("/api/items/$x/assignee")
            .body(mapOf("assigneeItemId" to meId(authed)))
            .exchange().expectStatus().isOk()
        val sprint = post(authed, "/api/projects/DASH/sprints", mapOf("name" to "冲刺一", "startDate" to null, "endDate" to null))
        authed.patch().uri("/api/projects/DASH/sprints/${sprint["sprintId"].asText()}")
            .body(mapOf("status" to "active"))
            .exchange().expectStatus().isOk()
        val ms = post(authed, "/api/projects/DASH/milestones", mapOf("name" to "里程碑一"))
        authed.patch().uri("/api/projects/DASH/milestones/${ms["milestoneId"].asText()}")
            .body(mapOf("status" to "in_progress"))
            .exchange().expectStatus().isOk()

        val body = authed.get().uri("/api/dashboard").exchange()
            .expectStatus().isOk()
            .expectBody(String::class.java).returnResult().responseBody!!
        val root = json.readTree(body)

        // 三桶:今日名下含 DASH-1
        assertEquals("DASH-1", root["standup"]["today"][0]["number"].asText())
        // 在途组:仅 DASH(QUIET 无在途被过滤),sprint/milestone 各一
        assertEquals(1, root["live"].size())
        val live = root["live"][0]
        assertEquals("DASH", live["projectKey"].asText())
        assertEquals("冲刺一", live["sprints"][0]["name"].asText())
        assertEquals("里程碑一", live["milestones"][0]["name"].asText())
    }

    private fun post(authed: RestTestClient, uri: String, body: Map<String, Any?>): JsonNode =
        json.readTree(
            authed.post().uri(uri).body(body)
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )

    private fun meId(authed: RestTestClient): String =
        json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { !it["virtual"].asBoolean() }["memberId"].asText()
}
