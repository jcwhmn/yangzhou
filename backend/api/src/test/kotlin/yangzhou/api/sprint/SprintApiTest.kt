package yangzhou.api.sprint

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V12-S1 Sprint 平面(spec docs/spec/0010-v12-itemgroup.md)。
 * 黑盒走 REST:CRUD、非空删除 409、指派/移出/替换、completed 拒指派、backlog 谓词、完成后历史仍在。
 */
class SprintApiTest : AbstractApiTest() {

    private fun createSprint(authed: RestTestClient, key: String, name: String, body: Map<String, Any?> = mapOf("name" to name)): JsonNode {
        val r = authed.post().uri("/api/projects/$key/sprints").body(body).exchange()
            .expectBody(String::class.java).returnResult()
        assertEquals(201, r.status.value(), r.responseBody)
        return json.readTree(r.responseBody!!)
    }

    private fun assignSprint(authed: RestTestClient, itemId: String, sprintId: String?) =
        authed.put().uri("/api/items/$itemId/sprint").body(mapOf("sprintId" to sprintId)).exchange()
            .expectBody(String::class.java).returnResult()

    private fun getSprints(authed: RestTestClient, key: String, path: String): JsonNode =
        json.readTree(authed.get().uri("/api/projects/$key/$path").exchange()
            .expectBody(String::class.java).returnResult().responseBody!!)

    @Test
    fun `sprint CRUD 与校验`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "SPR")

        val s = createSprint(authed, "SPR", "Sprint 1", mapOf("name" to "Sprint 1", "startDate" to "2026-10-01", "endDate" to "2026-10-15"))
        assertEquals("Sprint 1", s["name"].asText())
        assertEquals("planned", s["status"].asText())
        assertEquals("2026-10-01", s["startDate"].asText())
        val sid = s["sprintId"].asText()

        assertEquals(1, getSprints(authed, "SPR", "sprints").size())

        val patched = json.readTree(authed.patch().uri("/api/projects/SPR/sprints/$sid")
            .body(mapOf("name" to "Sprint 1 改", "startDate" to "", "status" to "active"))
            .exchange().expectBody(String::class.java).returnResult().responseBody!!)
        assertEquals("Sprint 1 改", patched["name"].asText())
        assertEquals("active", patched["status"].asText())
        assertTrue(patched["startDate"].isNull)

        authed.patch().uri("/api/projects/SPR/sprints/$sid").body(mapOf("status" to "doing"))
            .exchange().expectStatus().isBadRequest()
        authed.post().uri("/api/projects/SPR/sprints").body(mapOf("name" to "  "))
            .exchange().expectStatus().isBadRequest()
    }

    @Test
    fun `指派 移出 替换 与 backlog 谓词`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "ASN")
        val s1 = createSprint(authed, "ASN", "S42")["sprintId"].asText()
        val s2 = createSprint(authed, "ASN", "S43")["sprintId"].asText()
        val item = createItem(authed, "ASN", "指派测试 item")["itemId"].asText()

        // 指派 → sprintName 回显 + sprint items 含之 + backlog 不含
        val assigned = json.readTree(assignSprint(authed, item, s1).responseBody!!)
        assertEquals("S42", assigned["sprintName"].asText())
        assertEquals(1, getSprints(authed, "ASN", "sprints/$s1/items").size())
        assertTrue(getSprints(authed, "ASN", "backlog").none { it["itemId"].asText() == item })

        // 替换:旧行删(planned/active 唯一),新行加
        assignSprint(authed, item, s2)
        assertEquals(0, getSprints(authed, "ASN", "sprints/$s1/items").size())
        assertEquals(1, getSprints(authed, "ASN", "sprints/$s2/items").size())

        // 移出(null)→ 回 backlog
        assignSprint(authed, item, null)
        assertTrue(getSprints(authed, "ASN", "backlog").any { it["itemId"].asText() == item })
    }

    @Test
    fun `completed sprint 拒绝指派 且历史保留`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "HIS")
        val s = createSprint(authed, "HIS", "S42")["sprintId"].asText()
        val item = createItem(authed, "HIS", "历史 item")["itemId"].asText()

        assignSprint(authed, item, s)
        authed.patch().uri("/api/projects/HIS/sprints/$s").body(mapOf("status" to "completed"))
            .exchange().expectStatus().isOk()

        // 完成后:历史 items 仍在;item 当前 sprint 视为空 → 回 backlog;指回该 sprint → 409
        assertEquals(1, getSprints(authed, "HIS", "sprints/$s/items").size())
        assertTrue(getSprints(authed, "HIS", "backlog").any { it["itemId"].asText() == item })
        val again = authed.put().uri("/api/items/$item/sprint").body(mapOf("sprintId" to s)).exchange()
        again.expectStatus().isEqualTo(409)
        val other = createSprint(authed, "HIS", "S43")["sprintId"].asText()
        assertEquals(200, assignSprint(authed, item, other).status.value())
    }

    @Test
    fun `非空删除 409 空删除 204 跨项目 404`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "DEL")
        createProject(authed, "OTH")
        val s = createSprint(authed, "DEL", "S1")["sprintId"].asText()
        val item = createItem(authed, "DEL", "占位 item")["itemId"].asText()

        // 跨项目指派 → 404(sprint 不在 item 所属项目)
        val otherSprint = createSprint(authed, "OTH", "别的项目 sprint")["sprintId"].asText()
        assertEquals(404, assignSprint(authed, item, otherSprint).status.value())

        // 非空删除 → 409;清空后 → 204 且列表为空
        assignSprint(authed, item, s)
        authed.delete().uri("/api/projects/DEL/sprints/$s").exchange().expectStatus().isEqualTo(409)
        assignSprint(authed, item, null)
        authed.delete().uri("/api/projects/DEL/sprints/$s").exchange().expectStatus().isNoContent()
        assertEquals(0, getSprints(authed, "DEL", "sprints").size())
    }
}
