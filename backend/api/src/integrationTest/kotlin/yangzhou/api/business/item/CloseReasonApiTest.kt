package yangzhou.api.business.item

import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * YPJ-274(spec 0167):关闭理由关键路径黑盒——转终态缺省 done / reopen 清空 / 带理由+备注 / 只改理由 /
 * 状态开关 / 负例 / 回收站恢复补 done / 存量回填口径(终态大写匹配)。
 */
class CloseReasonApiTest : AbstractApiTest() {
    private fun patch(
        authed: RestTestClient,
        itemId: String,
        body: Map<String, Any?>,
    ): com.fasterxml.jackson.databind.JsonNode {
        val r = authed.patch().uri("/api/items/$itemId").body(body).exchange().expectStatus().isOk()
        return json.readTree(r.expectBody(String::class.java).returnResult().responseBody!!)
    }

    private fun patchRaw(
        authed: RestTestClient,
        itemId: String,
        body: Map<String, Any?>,
    ) = authed.patch().uri("/api/items/$itemId").body(body).exchange()

    private fun statusId(
        authed: RestTestClient,
        key: String,
        name: String,
    ): String {
        val body = authed.get().uri("/api/projects/$key").exchange().expectBody(String::class.java).returnResult().responseBody!!
        return json.readTree(body)["statuses"].first { it["name"].asText() == name }["statusId"].asText()
    }

    /** 已指派的票(离开起点态须有主)。 */
    private fun assignedItem(
        authed: RestTestClient,
        key: String,
    ): String {
        val id = createItem(authed, key, "cr")["itemId"].asText()
        authed.put().uri("/api/items/$id/assignee").body(mapOf("assigneeItemId" to meMemberId(authed))).exchange().expectStatus().isOk()
        return id
    }

    private fun closeReasonInDb(itemId: String): String? = jdbc.queryForObject("select close_reason from item where object_id = ?", String::class.java, java.util.UUID.fromString(itemId))

    private fun lastActivity(
        authed: RestTestClient,
        itemId: String,
        kind: String,
    ): String? {
        // 活动按 createdAt 倒序返回(最新在前)→ 取第一条匹配
        val list = json.readTree(authed.get().uri("/api/items/$itemId/activity").exchange().expectBody(String::class.java).returnResult().responseBody!!)
        return list.firstOrNull { it["kind"].asText() == kind }?.get("newValue")?.asText()
    }

    @Test
    fun `主链_缺省done_reopen清空_带理由备注_只改理由`() {
        val authed = bootstrapAndAuth()
        val key = "CRMAIN"
        createProject(authed, key, seedSprint = false)
        val id = assignedItem(authed, key)
        val done = statusId(authed, key, "Done")
        val prog = statusId(authed, key, "In Progress")

        // 转终态不带理由 → done;活动纯 "Done"
        assertEquals("done", patch(authed, id, mapOf("statusItemId" to done))["closeReason"].asText())
        assertEquals("done", closeReasonInDb(id))
        assertEquals("Done", lastActivity(authed, id, "status_changed"))

        // reopen → 清空
        assertTrue(patch(authed, id, mapOf("statusItemId" to prog))["closeReason"].isNull)
        assertNull(closeReasonInDb(id))

        // 带理由+备注
        assertEquals("被 274-b 取代", patch(authed, id, mapOf("statusItemId" to done, "closeReason" to "superseded", "closeNote" to "被 274-b 取代"))["closeNote"].asText())
        assertEquals("Done(被取代:被 274-b 取代)", lastActivity(authed, id, "status_changed"))

        // 只改理由 → close_reason_changed;改回 done 清 note
        assertTrue(patch(authed, id, mapOf("closeReason" to "done"))["closeNote"].isNull)
        assertEquals("完成", lastActivity(authed, id, "close_reason_changed"))
    }

    @Test
    fun `状态开关_设与省略保持_项目DTO可见`() {
        val authed = bootstrapAndAuth()
        val key = "CRFLAG"
        createProject(authed, key, seedSprint = false)
        val prog = statusId(authed, key, "In Progress")
        fun flag(body: Map<String, Any?>): Boolean = json.readTree(authed.patch().uri("/api/statuses/$prog").body(body).exchange().expectBody(String::class.java).returnResult().responseBody!!)["requireCloseReason"].asBoolean()
        assertTrue(flag(mapOf("requireCloseReason" to true)))
        assertTrue(flag(mapOf("icon" to "x"))) // 省略=保持
        val projectBody = authed.get().uri("/api/projects/$key").exchange().expectBody(String::class.java).returnResult().responseBody!!
        val inProjectDto = json.readTree(projectBody)["statuses"].first { it["name"].asText() == "In Progress" }["requireCloseReason"].asBoolean()
        assertTrue(inProjectDto) // 前端与 CLI 读这条路径
    }

    @Test
    fun `负例_非终态带理由_非法枚举_非终态改理由`() {
        val authed = bootstrapAndAuth()
        val key = "CRNEG"
        createProject(authed, key, seedSprint = false)
        val id = assignedItem(authed, key)
        val done = statusId(authed, key, "Done")
        val prog = statusId(authed, key, "In Progress")
        patchRaw(authed, id, mapOf("statusItemId" to prog, "closeReason" to "wontfix")).expectStatus().isBadRequest()
        patchRaw(authed, id, mapOf("statusItemId" to done, "closeReason" to "nope")).expectStatus().isBadRequest()
        patch(authed, id, mapOf("statusItemId" to prog))
        patchRaw(authed, id, mapOf("closeReason" to "wontfix")).expectStatus().isBadRequest()
    }

    @Test
    fun `回收站恢复_终态票无理由补done_已标不覆盖`() {
        val authed = bootstrapAndAuth()
        val key = "CRRESTORE"
        createProject(authed, key, seedSprint = false)
        val done = statusId(authed, key, "Done")
        val id = assignedItem(authed, key)
        patch(authed, id, mapOf("statusItemId" to done, "closeReason" to "wontfix"))

        // 模拟存量:理由置空 → 软删 → 恢复 → 应补 done(V71 未回填软删除票的口径)
        jdbc.update("update item set close_reason = null where object_id = ?", java.util.UUID.fromString(id))
        authed.delete().uri("/api/items/$id").exchange().expectStatus().isNoContent()
        authed.post().uri("/api/recycle-bin/$id/restore").exchange().expectStatus().isNoContent()
        assertEquals("done", closeReasonInDb(id))

        // 已标理由的终态票:删→恢复不覆盖
        patch(authed, id, mapOf("closeReason" to "expired"))
        authed.delete().uri("/api/items/$id").exchange().expectStatus().isNoContent()
        authed.post().uri("/api/recycle-bin/$id/restore").exchange().expectStatus().isNoContent()
        assertEquals("expired", closeReasonInDb(id))
    }
}
