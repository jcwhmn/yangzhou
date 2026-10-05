package yangzhou.api.milestone

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V14 Milestone(spec docs/spec/0011-v14-itemgroup-phase2.md)。
 * 黑盒走 REST:CRUD、状态机合法/非法迁移、0..1 in_progress、终态不可变、跨项目 404、排序。
 */
class MilestoneApiTest : AbstractApiTest() {

    private fun createMilestone(authed: RestTestClient, key: String, body: Map<String, Any?>): JsonNode {
        val r = authed.post().uri("/api/projects/$key/milestones").body(body).exchange()
            .expectBody(String::class.java).returnResult()
        assertEquals(201, r.status.value(), r.responseBody)
        return json.readTree(r.responseBody!!)
    }

    private fun patch(authed: RestTestClient, key: String, id: String, body: Map<String, Any?>) =
        authed.patch().uri("/api/projects/$key/milestones/$id").body(body).exchange()
            .expectBody(String::class.java).returnResult()

    private fun list(authed: RestTestClient, key: String): JsonNode =
        json.readTree(authed.get().uri("/api/projects/$key/milestones").exchange()
            .expectBody(String::class.java).returnResult().responseBody!!)

    @Test
    fun `CRUD 与字段校验`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "MIL")

        val m = createMilestone(authed, "MIL", mapOf("name" to "MVP", "targetDate" to "2026-12-01"))
        assertEquals("MVP", m["name"].asText())
        assertEquals("planned", m["status"].asText())
        assertEquals("2026-12-01", m["targetDate"].asText())
        val mid = m["milestoneId"].asText()

        // 改名 + 清日期(空串 = null)
        val patched = json.readTree(patch(authed, "MIL", mid, mapOf("name" to "MVP 改", "targetDate" to "")).responseBody!!)
        assertEquals("MVP 改", patched["name"].asText())
        assertTrue(patched["targetDate"].isNull)

        // name 必填 / 日期格式
        authed.post().uri("/api/projects/MIL/milestones").body(mapOf("name" to " "))
            .exchange().expectStatus().isBadRequest()
        authed.post().uri("/api/projects/MIL/milestones").body(mapOf("name" to "X", "targetDate" to "12/01"))
            .exchange().expectStatus().isBadRequest()

        // 删除 → 204 + 列表消失
        authed.delete().uri("/api/projects/MIL/milestones/$mid").exchange().expectStatus().isNoContent()
        assertEquals(0, list(authed, "MIL").size())
        authed.delete().uri("/api/projects/MIL/milestones/$mid").exchange().expectStatus().isNotFound()
    }

    @Test
    fun `状态机 合法迁移与非法迁移`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "LIFE")
        val m = createMilestone(authed, "LIFE", mapOf("name" to "Beta"))["milestoneId"].asText()

        // 非法:planned → completed(跳级);非法值 400
        assertEquals(409, patch(authed, "LIFE", m, mapOf("status" to "completed")).status.value())
        authed.patch().uri("/api/projects/LIFE/milestones/$m").body(mapOf("status" to "doing"))
            .exchange().expectStatus().isBadRequest()

        // 合法全链:planned → in_progress → completed
        assertEquals("in_progress", json.readTree(patch(authed, "LIFE", m, mapOf("status" to "in_progress")).responseBody!!)["status"].asText())
        assertEquals("completed", json.readTree(patch(authed, "LIFE", m, mapOf("status" to "completed")).responseBody!!)["status"].asText())

        // 终态:再迁移 409、改名 409、历史仍可读
        assertEquals(409, patch(authed, "LIFE", m, mapOf("status" to "in_progress")).status.value())
        assertEquals(409, patch(authed, "LIFE", m, mapOf("name" to "Beta 改")).status.value())
        assertEquals("Beta", list(authed, "LIFE").first { it["milestoneId"].asText() == m }["name"].asText())

        // cancelled 从 planned 可达,同样终态
        val c = createMilestone(authed, "LIFE", mapOf("name" to "砍掉"))["milestoneId"].asText()
        assertEquals("cancelled", json.readTree(patch(authed, "LIFE", c, mapOf("status" to "cancelled")).responseBody!!)["status"].asText())
        assertEquals(409, patch(authed, "LIFE", c, mapOf("status" to "in_progress")).status.value())
    }

    @Test
    fun `同项目至多一个 in_progress 跨项目不受限`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CUR")
        createProject(authed, "CUR2")
        val a = createMilestone(authed, "CUR", mapOf("name" to "A"))["milestoneId"].asText()
        val b = createMilestone(authed, "CUR", mapOf("name" to "B"))["milestoneId"].asText()
        val other = createMilestone(authed, "CUR2", mapOf("name" to "其它项目"))["milestoneId"].asText()

        assertEquals(200, patch(authed, "CUR", a, mapOf("status" to "in_progress")).status.value())
        // 第二个 → 409(服务层预检;DB 部分唯一索引兜底)
        val r = patch(authed, "CUR", b, mapOf("status" to "in_progress"))
        assertEquals(409, r.status.value(), r.responseBody)
        // 其它项目不受影响
        assertEquals(200, patch(authed, "CUR2", other, mapOf("status" to "in_progress")).status.value())
        // 完成后可开下一个
        assertEquals(200, patch(authed, "CUR", a, mapOf("status" to "completed")).status.value())
        assertEquals(200, patch(authed, "CUR", b, mapOf("status" to "in_progress")).status.value())
    }

    @Test
    fun `排序 in_progress 优先 终态末尾 target_date 升序`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "SORT")
        val done = createMilestone(authed, "SORT", mapOf("name" to "已完", "targetDate" to "2026-01-01"))["milestoneId"].asText()
        val futureFar = createMilestone(authed, "SORT", mapOf("name" to "远期", "targetDate" to "2027-06-01"))["milestoneId"].asText()
        val futureNear = createMilestone(authed, "SORT", mapOf("name" to "近期", "targetDate" to "2026-06-01"))["milestoneId"].asText()
        val noDate = createMilestone(authed, "SORT", mapOf("name" to "无日期"))["milestoneId"].asText()

        patch(authed, "SORT", done, mapOf("status" to "in_progress")).status.value()
        assertEquals(200, patch(authed, "SORT", done, mapOf("status" to "completed")).status.value())
        val started = createMilestone(authed, "SORT", mapOf("name" to "进行中"))["milestoneId"].asText()
        assertEquals(200, patch(authed, "SORT", started, mapOf("status" to "in_progress")).status.value())

        val names = list(authed, "SORT").map { it["name"].asText() }
        assertEquals(listOf("进行中", "近期", "远期", "无日期", "已完"), names)
        assertTrue(json.readTree(authed.get().uri("/api/projects/SORT/milestones").exchange()
            .expectBody(String::class.java).returnResult().responseBody!!)[0]["status"].asText() == "in_progress")
    }

    @Test
    fun `跨项目 milestoneId 404`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "X1")
        createProject(authed, "X2")
        val m = createMilestone(authed, "X1", mapOf("name" to "私有"))["milestoneId"].asText()

        authed.get().uri("/api/projects/X2/milestones").exchange().expectStatus().isOk()
        assertEquals(404, patch(authed, "X2", m, mapOf("name" to "偷改")).status.value())
        authed.delete().uri("/api/projects/X2/milestones/$m").exchange().expectStatus().isNotFound()
    }
}
