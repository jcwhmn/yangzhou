package yangzhou.api.business.itemgroup

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** spec 0109(YPJ-158):归属替换清理语义——member 表=当前归属真相;FINAL 组旧行随迁移删除;Done 成员行保留。 */
class GroupMembershipCleanupApiTest : AbstractApiTest() {
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

    /** 建 item 并置为 FINAL:先指派 me(开工须有主守卫),再 PATCH 到 Done(照 ItemsApiTest 惯例) */
    private fun finalize(
        authed: RestTestClient,
        key: String,
        itemId: String,
    ) {
        authed
            .put()
            .uri("/api/items/$itemId/assignee")
            .body(mapOf("assigneeItemId" to meMemberId(authed)))
            .exchange()
            .expectStatus()
            .isOk
        val proj = get(authed, "/api/projects/$key")
        val finalId = proj["statuses"].first { it["name"].asText() == "Done" }["statusId"].asText()
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("statusItemId" to finalId))
            .exchange()
            .expectStatus()
            .is2xxSuccessful
            .expectBody(String::class.java)
            .returnResult()
    }

    private fun memberTitles(
        authed: RestTestClient,
        key: String,
        groupId: String,
    ): Set<String> = get(authed, "/api/projects/$key/groups/$groupId/items").map { it["title"].asText() }.toSet()

    @Test
    fun `转出清理——处置后完成组仅剩 Done 成员_被转 item 仅目标组行`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CLN")
        val doneItem = createItem(authed, "CLN", "完成成员")
        val movedItem = createItem(authed, "CLN", "转出成员")
        val a = createGroup(authed, "CLN", mapOf("typeKey" to "sprint", "name" to "A"))
        val aid = a["groupId"].asText()
        assign(authed, doneItem["itemId"].asText(), aid)
        assign(authed, movedItem["itemId"].asText(), aid)
        action(authed, "CLN", aid, "start") // planned 无 complete 出边,先激活
        finalize(authed, "CLN", doneItem["itemId"].asText())
        val b = createGroup(authed, "CLN", mapOf("typeKey" to "sprint", "name" to "B"))
        val bid = b["groupId"].asText()
        action(authed, "CLN", bid, "start") // 目标组 active

        // 组 A 完成(组先行),转出「转出成员」(Done 成员不动)
        assertEquals(200, action(authed, "CLN", aid, "complete").status.value())
        assertEquals(200, assign(authed, movedItem["itemId"].asText(), bid).status.value())

        // 完成组页只剩 Done;被转 item 只在 B(A 行已清)
        assertEquals(setOf("完成成员"), memberTitles(authed, "CLN", aid))
        assertEquals(setOf("转出成员"), memberTitles(authed, "CLN", bid))

        // member 表真相:双重行不存在(0109 事故回归断言)
        val staleRows =
            jdbc
                .queryForList(
                    "select count(*) as c from item_group_member m join item_group g on g.object_id = m.group_object_id " +
                        "where g.name = 'A' and m.item_object_id = (select object_id from item where title = '转出成员')",
                )[0]["c"] as Long
        assertEquals(0L, staleRows)
    }

    @Test
    fun `回池清理——groupId null 删同类型全行_含 completed 组`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CLN")
        val item = createItem(authed, "CLN", "回池成员")
        val a = createGroup(authed, "CLN", mapOf("typeKey" to "sprint", "name" to "A"))
        val aid = a["groupId"].asText()
        assign(authed, item["itemId"].asText(), aid)
        action(authed, "CLN", aid, "start")
        action(authed, "CLN", aid, "complete")

        // completed 期间回池(取消处置的新语义)
        val r = assign(authed, item["itemId"].asText(), null, groupType = "sprint")
        assertEquals(200, r.status.value(), r.responseBody)
        assertEquals(0, memberTitles(authed, "CLN", aid).size)

        // history 区间关闭(member 行删 + removed_at 非空)
        val openHist =
            jdbc
                .queryForList(
                    "select count(*) as c from item_group_member_history h join item i on i.object_id = h.item_object_id " +
                        "where i.title = '回池成员' and h.removed_at is null",
                )[0]["c"] as Long
        assertEquals(0L, openHist)
    }

    @Test
    fun `幂等加stale——item 已在目标组但残留 completed 行_再 PUT 清残行不炸唯一约束`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CLN")
        val item = createItem(authed, "CLN", "双行成员")
        val a = createGroup(authed, "CLN", mapOf("typeKey" to "sprint", "name" to "A"))
        val aid = a["groupId"].asText()
        val b = createGroup(authed, "CLN", mapOf("typeKey" to "sprint", "name" to "B"))
        val bid = b["groupId"].asText()
        assign(authed, item["itemId"].asText(), aid)
        action(authed, "CLN", aid, "start")
        action(authed, "CLN", aid, "complete")
        // 人为制造双行:直接 API 挂 B(A 行 stale)
        assertEquals(200, assign(authed, item["itemId"].asText(), bid).status.value())
        // 再 PUT 同目标(处置重试形态):清 A 残行,不炸 uk
        val r = assign(authed, item["itemId"].asText(), bid)
        assertEquals(200, r.status.value(), r.responseBody)
        assertEquals(setOf("双行成员"), memberTitles(authed, "CLN", bid))
        val aRows =
            jdbc
                .queryForList(
                    "select count(*) as c from item_group_member m join item_group g on g.object_id = m.group_object_id " +
                        "where g.name = 'A' and m.item_object_id = (select object_id from item where title = '双行成员')",
                )[0]["c"] as Long
        assertEquals(0L, aRows)
    }

    @Test
    fun `显式移出 Done item——完成组行删_历史由 history 承载`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CLN")
        val item = createItem(authed, "CLN", "Done 成员")
        val a = createGroup(authed, "CLN", mapOf("typeKey" to "sprint", "name" to "A"))
        val aid = a["groupId"].asText()
        assign(authed, item["itemId"].asText(), aid)
        action(authed, "CLN", aid, "start")
        action(authed, "CLN", aid, "complete")
        finalize(authed, "CLN", item["itemId"].asText())

        val b = createGroup(authed, "CLN", mapOf("typeKey" to "sprint", "name" to "B"))
        val bid = b["groupId"].asText()
        action(authed, "CLN", bid, "start")
        assertEquals(200, assign(authed, item["itemId"].asText(), bid).status.value())

        assertEquals(0, memberTitles(authed, "CLN", aid).size)
        assertTrue(memberTitles(authed, "CLN", bid).contains("Done 成员"))
    }
}
