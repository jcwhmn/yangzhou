package yangzhou.api.business.itemgroup

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** spec 0110(YPJ-159):处置事务化——完成+成员处置同一事务;失败(400)整体回滚,组状态不变。 */
class GroupDispositionTransactionApiTest : AbstractApiTest() {
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

    /** 0110:complete 可携处置计划(dispositions);无 body 兼容走 GroupReopenApiTest 的 action。 */
    private fun completeWith(
        authed: RestTestClient,
        key: String,
        groupId: String,
        dispositions: List<Map<String, String?>>,
    ) = authed
        .post()
        .uri("/api/projects/$key/groups/$groupId/actions/complete")
        .body(mapOf("dispositions" to dispositions))
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

    private fun start(
        authed: RestTestClient,
        key: String,
        groupId: String,
    ): Int {
        val r =
            authed
                .post()
                .uri("/api/projects/$key/groups/$groupId/actions/start")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return r.status.value()
    }

    private fun memberTitles(
        authed: RestTestClient,
        key: String,
        groupId: String,
    ): Set<String> = get(authed, "/api/projects/$key/groups/$groupId/items").map { it["title"].asText() }.toSet()

    @Test
    fun `完成+处置同事务——一次请求后组完成_非终态入目标组_完成组仅剩Done`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "DTX")
        val done = createItem(authed, "DTX", "已完成成员")
        val moved = createItem(authed, "DTX", "被转成员")
        val s = createGroup(authed, "DTX", mapOf("typeKey" to "sprint", "name" to "A"))
        val gid = s["groupId"].asText()
        val t = createGroup(authed, "DTX", mapOf("typeKey" to "sprint", "name" to "B"))
        val tid = t["groupId"].asText()
        assign(authed, done["itemId"].asText(), gid)
        assign(authed, moved["itemId"].asText(), gid)
        finalize(authed, "DTX", done["itemId"].asText())
        assertEquals(200, start(authed, "DTX", gid))

        val r =
            completeWith(
                authed,
                "DTX",
                gid,
                listOf(mapOf("itemId" to moved["itemId"].asText(), "targetGroupId" to tid)),
            )
        assertEquals(200, r.status.value(), r.responseBody)
        assertEquals("completed", json.readTree(r.responseBody!!)["status"].asText())

        // 一次请求后:完成组仅 Done;被转 item 在目标组且仅此一行
        assertEquals(setOf("已完成成员"), memberTitles(authed, "DTX", gid))
        assertEquals(setOf("被转成员"), memberTitles(authed, "DTX", tid))
        val backlog = get(authed, "/api/projects/DTX/backlog").map { it["title"].asText() }.toSet()
        assertTrue("被转成员" !in backlog, "被转成员不应回池:$backlog")
    }

    @Test
    fun `回池处置——targetGroupId null 入 Backlog_完成组旧行清`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "DTY")
        val moved = createItem(authed, "DTY", "回池成员")
        val s = createGroup(authed, "DTY", mapOf("typeKey" to "sprint", "name" to "A"))
        val gid = s["groupId"].asText()
        assign(authed, moved["itemId"].asText(), gid)
        assertEquals(200, start(authed, "DTY", gid))

        val r =
            completeWith(
                authed,
                "DTY",
                gid,
                listOf(mapOf("itemId" to moved["itemId"].asText(), "targetGroupId" to null)),
            )
        assertEquals(200, r.status.value(), r.responseBody)
        val backlog = get(authed, "/api/projects/DTY/backlog").map { it["title"].asText() }.toSet()
        assertTrue("回池成员" in backlog, "回池成员应在 Backlog:$backlog")
        assertEquals(emptySet(), memberTitles(authed, "DTY", gid))
    }

    @Test
    fun `处置行非法——400 且组状态不变(原子性探针)`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "DTZ")
        val moved = createItem(authed, "DTZ", "应被回滚的成员")
        val s = createGroup(authed, "DTZ", mapOf("typeKey" to "sprint", "name" to "A"))
        val gid = s["groupId"].asText()
        assign(authed, moved["itemId"].asText(), gid)
        assertEquals(200, start(authed, "DTZ", gid))

        val r =
            completeWith(
                authed,
                "DTZ",
                gid,
                listOf(
                    mapOf("itemId" to moved["itemId"].asText(), "targetGroupId" to null),
                    mapOf("itemId" to "00000000-0000-0000-0000-000000000000", "targetGroupId" to null),
                ),
            )
        assertEquals(400, r.status.value(), r.responseBody)
        // 原子性:状态迁移随处置回滚,组仍 active 且成员行未动
        val g = get(authed, "/api/projects/DTZ/groups/$gid")
        assertEquals("active", g["status"].asText())
        assertEquals(setOf("应被回滚的成员"), memberTitles(authed, "DTZ", gid))
    }

    @Test
    fun `重复处置行——400`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "DTR")
        val moved = createItem(authed, "DTR", "重复行成员")
        val s = createGroup(authed, "DTR", mapOf("typeKey" to "sprint", "name" to "A"))
        val gid = s["groupId"].asText()
        assign(authed, moved["itemId"].asText(), gid)
        assertEquals(200, start(authed, "DTR", gid))
        val line = mapOf("itemId" to moved["itemId"].asText(), "targetGroupId" to null)
        assertEquals(400, completeWith(authed, "DTR", gid, listOf(line, line)).status.value())
    }
}
