package yangzhou.api.business.item

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-181(spec 0129):item 进 FINAL 自动停该 item 进行中工时;非 FINAL 不停;其他 item 不受影响;重开再 Done 幂等。 */
class ItemFinalizeStopTimesheetApiTest : AbstractApiTest() {
    private fun log(
        authed: org.springframework.test.web.servlet.client.RestTestClient,
        itemId: String,
    ) = json.readTree(
        authed
            .get()
            .uri("/api/items/$itemId/time-entries")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    private fun statusId(
        authed: org.springframework.test.web.servlet.client.RestTestClient,
        name: String,
    ): String {
        val statuses = json.readTree(
            authed
                .get()
                .uri("/api/projects/CHE")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!,
        )["statuses"]
        return statuses.first { it["name"].asText() == name }["statusId"].asText()
    }

    private fun patchStatus(
        authed: org.springframework.test.web.servlet.client.RestTestClient,
        itemId: String,
        statusId: String,
    ) {
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("statusItemId" to statusId))
            .exchange()
            .expectStatus()
            .isOk()
    }

    private fun startTimer(
        authed: org.springframework.test.web.servlet.client.RestTestClient,
        itemId: String,
    ) {
        authed
            .post()
            .uri("/api/items/$itemId/time-entries")
            .body(mapOf<String, Any?>("minutes" to null))
            .exchange()
            .expectStatus()
            .isCreated()
    }

    @Test
    fun `进 FINAL 停表——非 FINAL 不停——其他 item 不受影响——重开再 FINAL 幂等`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val a = createItem(authed, "CHE", "a")["itemId"].asText()
        val b = createItem(authed, "CHE", "b")["itemId"].asText()
        val toDo = statusId(authed, "To Do")
        val inProgress = statusId(authed, "In Progress")
        val done = statusId(authed, "Done")

        // 开工须有主:A/B 均指派给我(否则离开 To Do 被 409)
        val meId = meMemberId(authed)
        listOf(a, b).forEach { id ->
            authed
                .put()
                .uri("/api/items/$id/assignee")
                .body(mapOf("assigneeItemId" to meId))
                .exchange()
                .expectStatus()
                .isOk()
        }

        // A:我开计时;B:小李开计时(jdbc 直插,模拟他人计时,同 TimeEntryApiTest 先例)
        startTimer(authed, a)
        authed
            .post()
            .uri("/api/members")
            .body(mapOf("displayName" to "小李"))
            .exchange()
            .expectStatus()
            .isCreated()
        val xiaoLiId = jdbc.queryForList("select object_id from member where display_name = '小李'")[0]["object_id"]
        val bObjectId = jdbc.queryForList("select object_id from item where title = 'b'")[0]["object_id"]
        jdbc.update(
            "insert into time_entry (object_id, item_object_id, member_object_id, started_at) values (gen_random_uuid(), ?, ?, now())",
            bObjectId,
            xiaoLiId,
        )

        // 非 FINAL 迁移:不停
        patchStatus(authed, a, inProgress)
        assertTrue(log(authed, a)["entries"][0]["endedAt"].isNull)

        // 进 FINAL:只停 A,小李在 B 的计时不动
        patchStatus(authed, a, done)
        assertTrue(!log(authed, a)["entries"][0]["endedAt"].isNull)
        assertTrue(log(authed, b)["entries"][0]["endedAt"].isNull)

        // 重开再 FINAL:幂等,B 仍不受影响
        patchStatus(authed, a, toDo)
        patchStatus(authed, a, inProgress)
        patchStatus(authed, a, done)
        val entries = log(authed, a)["entries"]
        assertEquals(1, entries.size())
        assertTrue(!entries[0]["endedAt"].isNull)
        assertTrue(log(authed, b)["entries"][0]["endedAt"].isNull)
    }
}
