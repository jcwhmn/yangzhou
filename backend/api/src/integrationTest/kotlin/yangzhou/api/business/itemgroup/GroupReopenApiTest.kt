package yangzhou.api.business.itemgroup

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** spec 0108(YPJ-157):组重开——timed 模板 completed→active reopen;纯状态迁移成员不动;release 不可重开。 */
class GroupReopenApiTest : AbstractApiTest() {
    private fun createGroup(
        authed: RestTestClient,
        key: String,
        body: Map<String, Any?>,
    ): JsonNode {
        val r =
            authed
                .post()
                .uri("/api/projects/$key/groups")
                .body(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(201, r.status.value(), r.responseBody)
        return json.readTree(r.responseBody!!)
    }

    private fun action(
        authed: RestTestClient,
        key: String,
        groupId: String,
        function: String,
    ) = authed
        .post()
        .uri("/api/projects/$key/groups/$groupId/actions/$function")
        .exchange()
        .expectBody(String::class.java)
        .returnResult()

    private fun assign(
        authed: RestTestClient,
        itemId: String,
        groupId: String?,
        groupType: String? = null,
    ) = authed
        .put()
        .uri("/api/items/$itemId/group-membership")
        .body(mapOf("groupId" to groupId, "groupType" to groupType))
        .exchange()
        .expectBody(String::class.java)
        .returnResult()

    private fun get(
        authed: RestTestClient,
        path: String,
    ): JsonNode = json.readTree(
        authed
            .get()
            .uri(path)
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    @Test
    fun `重开主链——complete 后 reopen 回 active_成员历史行不动`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "RPN")
        val item = createItem(authed, "RPN", "重开成员")
        val s = createGroup(authed, "RPN", mapOf("typeKey" to "sprint", "name" to "A"))
        val gid = s["groupId"].asText()
        assign(authed, item["itemId"].asText(), gid)
        assertEquals(200, action(authed, "RPN", gid, "start").status.value())
        assertEquals(200, action(authed, "RPN", gid, "complete").status.value())

        // 重开:纯状态迁移
        val r = action(authed, "RPN", gid, "reopen")
        assertEquals(200, r.status.value(), r.responseBody)
        assertEquals("active", json.readTree(r.responseBody!!)["status"].asText())

        // 成员行未动(仍 1 行,同 item;无悬置)
        val members = get(authed, "/api/projects/RPN/groups/$gid/items")
        assertEquals(1, members.size())
        assertEquals(item["itemId"].asText(), members[0]["itemId"].asText())
    }

    @Test
    fun `release 终态无 reopen——409(对外承诺不可重开)`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "RPN")
        val rel = createGroup(authed, "RPN", mapOf("typeKey" to "release", "name" to "R1"))
        val rid = rel["groupId"].asText()
        assertEquals(200, action(authed, "RPN", rid, "complete").status.value())
        assertEquals(409, action(authed, "RPN", rid, "reopen").status.value())
    }

    @Test
    fun `重开恢复归属——completed 期间拒挂_重开后续挂成功`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "RPN")
        val s = createGroup(authed, "RPN", mapOf("typeKey" to "sprint", "name" to "A"))
        val gid = s["groupId"].asText()
        action(authed, "RPN", gid, "start")
        action(authed, "RPN", gid, "complete")
        val item = createItem(authed, "RPN", "后挂成员")

        // completed 期间挂入被既有守卫拒
        assertEquals(409, assign(authed, item["itemId"].asText(), gid).status.value())

        // 重开 → 挂入恢复
        assertEquals(200, action(authed, "RPN", gid, "reopen").status.value())
        val r = assign(authed, item["itemId"].asText(), gid)
        assertTrue(r.status.value() in 200..299, "重开后应可挂入:${r.responseBody}")
        val members = get(authed, "/api/projects/RPN/groups/$gid/items")
        assertEquals(1, members.size())
    }
}
