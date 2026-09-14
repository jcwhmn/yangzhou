package yangzhou.api.time

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-122:V8-S3 工时黑盒(计时器唯一自动停旧/补录/互斥 400/合计聚合/越权 404)。 */
class TimeEntryApiTest : AbstractApiTest() {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun setup(): Triple<org.springframework.test.web.servlet.client.RestTestClient, String, String> {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val i1 = createItem(authed, "CHE", "x")["itemId"].asText()
        val i2 = createItem(authed, "CHE", "y")["itemId"].asText()
        return Triple(authed, i1, i2)
    }

    private fun log(authed: org.springframework.test.web.servlet.client.RestTestClient, itemId: String) =
        json.readTree(
            authed.get().uri("/api/items/$itemId/time-entries").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )

    @Test
    fun `计时——开新自动停旧——明细折算分钟——stop 幂等保护`() {
        val (authed, i1, i2) = setup()

        // 开计时(item1)
        authed.post().uri("/api/items/$i1/time-entries")
            .body(mapOf<String, Any?>("minutes" to null))
            .exchange().expectStatus().isCreated()
        // 直接再开(item2)→ item1 自动停旧
        authed.post().uri("/api/items/$i2/time-entries")
            .body(mapOf<String, Any?>("minutes" to null))
            .exchange().expectStatus().isCreated()

        val log1 = log(authed, i1)
        assertEquals(1, log1["entries"].size())
        assertTrue(!log1["entries"][0]["endedAt"].isNull) // 被自动停旧

        // 停止 item2 计时
        val running = log(authed, i2)["entries"][0]["timeEntryId"].asText()
        authed.post().uri("/api/time-entries/$running/stop")
            .exchange().expectStatus().isOk()
        // 重复 stop → 409
        authed.post().uri("/api/time-entries/$running/stop")
            .exchange().expectStatus().isEqualTo(409)
    }

    @Test
    fun `补录 minutes——合计正确——互斥 400——非法分钟 400`() {
        val (authed, i1, i2) = setup()

        authed.post().uri("/api/items/$i1/time-entries")
            .body(mapOf("minutes" to 90, "note" to "补周一"))
            .exchange().expectStatus().isCreated()
        authed.post().uri("/api/items/$i1/time-entries")
            .body(mapOf("minutes" to 30))
            .exchange().expectStatus().isCreated()

        val log1 = log(authed, i1)
        assertEquals(120, log1["totalMinutes"].asLong())
        assertEquals(30, log1["entries"][0]["minutes"].asLong()) // 倒序:最近的在前
        assertEquals(90, log1["entries"][1]["minutes"].asLong())

        // 补录带 startedAt → 400(互斥)
        authed.post().uri("/api/items/$i1/time-entries")
            .body(mapOf("minutes" to 10, "startedAt" to "2026-09-13T00:00:00Z"))
            .exchange().expectStatus().isBadRequest()

        // minutes 非法 → 400
        authed.post().uri("/api/items/$i1/time-entries")
            .body(mapOf("minutes" to 0))
            .exchange().expectStatus().isBadRequest()

        // item2 无工时 → 合计 0
        assertEquals(0, log(authed, i2)["totalMinutes"].asLong())
    }

    @Test
    fun `按人聚合——summary 含我并排序`() {
        val (authed, i1, _) = setup()
        authed.post().uri("/api/items/$i1/time-entries")
            .body(mapOf("minutes" to 90))
            .exchange().expectStatus().isCreated()
        authed.post().uri("/api/items/$i1/time-entries")
            .body(mapOf("minutes" to 30))
            .exchange().expectStatus().isCreated()

        val summary = json.readTree(
            authed.get().uri("/api/projects/CHE/time-summary").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(1, summary.size())
        assertEquals("me", summary[0]["displayName"].asText())
        assertEquals(120, summary[0]["totalMinutes"].asLong())
        assertEquals(2, summary[0]["entryCount"].asInt())
    }

    @Test
    fun `越权 stop 404——未知项目 summary 404`() {
        val (authed, _, _) = setup()

        // 小李名下一条计时(jdbc 直插,模拟他人计时)
        authed.post().uri("/api/members")
            .body(mapOf("displayName" to "小李"))
            .exchange().expectStatus().isCreated()
        val xiaoLiId = jdbc.queryForList("select id from member where display_name = '小李'")[0]["id"]
        val itemId = jdbc.queryForList("select id from item where title = 'x'")[0]["id"]
        jdbc.update(
            "insert into time_entry (object_id, item_id, member_id, started_at) values (gen_random_uuid(), ?, ?, now())",
            itemId, xiaoLiId,
        )
        val entryId = jdbc.queryForList(
            "select object_id from time_entry where member_id = ?",
            xiaoLiId,
        )[0]["object_id"].toString()

        // 我不能停小李的计时
        authed.post().uri("/api/time-entries/$entryId/stop")
            .exchange().expectStatus().isNotFound()

        authed.get().uri("/api/projects/NOPE/time-summary")
            .exchange().expectStatus().isNotFound()
    }
}
