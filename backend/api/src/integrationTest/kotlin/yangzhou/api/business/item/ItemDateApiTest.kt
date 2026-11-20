package yangzhou.api.business.item

import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-120:V8-S1 日期字段黑盒(设置/清除/校验/超期/不变语义/活动留痕)。 */
class ItemDateApiTest : AbstractApiTest() {
    private fun setup(): Pair<RestTestClient, String> {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val itemId = createItem(authed, "CHE", "x")["itemId"].asText()
        return authed to itemId
    }

    private fun detail(
        authed: RestTestClient,
        itemId: String,
    ) = json.readTree(
        authed
            .get()
            .uri("/api/items/$itemId")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    @Test
    fun `设置日期回显——未传不变——空串清除——start 大于 due 400`() {
        val (authed, itemId) = setup()

        // 设置
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("startDate" to "2026-09-01", "dueDate" to "2026-09-30"))
            .exchange()
            .expectStatus()
            .isOk()
        assertEquals("2026-09-01T09:00", detail(authed, itemId)["startDate"].asText())
        assertEquals("2026-09-30T18:00", detail(authed, itemId)["dueDate"].asText())

        // 不传日期字段 → 保持不变
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("title" to "改名了"))
            .exchange()
            .expectStatus()
            .isOk()
        assertEquals("2026-09-30T18:00", detail(authed, itemId)["dueDate"].asText())

        // start > due → 400
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("startDate" to "2026-10-01"))
            .exchange()
            .expectStatus()
            .isBadRequest()

        // YPJ-208(spec 0138):datetime 进原样,非法格式 400
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("startDate" to "2026-09-01T14:30", "dueDate" to "2026-09-30T12:00"))
            .exchange()
            .expectStatus()
            .isOk()
        assertEquals("2026-09-01T14:30", detail(authed, itemId)["startDate"].asText())
        assertEquals("2026-09-30T12:00", detail(authed, itemId)["dueDate"].asText())
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("startDate" to "not-a-date"))
            .exchange()
            .expectStatus()
            .isBadRequest()

        // 空串清除
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("startDate" to "", "dueDate" to ""))
            .exchange()
            .expectStatus()
            .isOk()
        assertTrue(detail(authed, itemId)["startDate"].isNull)

        // 活动留痕:dates_changed
        val kinds =
            json
                .readTree(
                    authed
                        .get()
                        .uri("/api/items/$itemId/activity")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(String::class.java)
                        .returnResult()
                        .responseBody!!,
                ).map { it["kind"].asText() }
        assertTrue("dates_changed" in kinds)
    }

    @Test
    fun `超期标识——due 过去且非终态 true,终态 false,无 due false`() {
        val (authed, itemId) = setup()
        val statuses =
            json.readTree(
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

        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("dueDate" to "2020-01-01")) // 明显过去
            .exchange()
            .expectStatus()
            .isOk()
        assertTrue(detail(authed, itemId)["overdue"].asBoolean())

        // 移到终态 → 不算超期(先指派:开工须有主)
        val meId =
            json
                .readTree(
                    authed
                        .get()
                        .uri("/api/members")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(String::class.java)
                        .returnResult()
                        .responseBody!!,
                ).first { !it["virtual"].asBoolean() }["memberId"]
                .asText()
        authed
            .put()
            .uri("/api/items/$itemId/assignee")
            .body(mapOf("assigneeItemId" to meId))
            .exchange()
            .expectStatus()
            .isOk()
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("statusItemId" to statuses[4]["statusId"].asText()))
            .exchange()
            .expectStatus()
            .isOk()
        assertTrue(!detail(authed, itemId)["overdue"].asBoolean())

        // 无 due → false
        val item2 = createItem(authed, "CHE", "y")
        assertTrue(!detail(authed, item2["itemId"].asText())["overdue"].asBoolean())
    }

    /** YPJ-208(spec 0138 切片 b):项目工作日历——date-only 填充跟日历走;非法/倒挂 400。 */
    @Test
    fun `工作日历改默认时刻——非法与倒挂 400`() {
        val (authed, itemId) = setup()

        // 改日历后 date-only 填充跟日历走
        authed
            .patch()
            .uri("/api/projects/CHE/settings")
            .body(mapOf("workdayStart" to "08:30", "workdayEnd" to "17:00"))
            .exchange()
            .expectStatus()
            .isOk()
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("startDate" to "2026-09-01", "dueDate" to "2026-09-30"))
            .exchange()
            .expectStatus()
            .isOk()
        assertEquals("2026-09-01T08:30", detail(authed, itemId)["startDate"].asText())
        assertEquals("2026-09-30T17:00", detail(authed, itemId)["dueDate"].asText())
        // 日历值落库验证:settings 无 GET 端点,直查库(item detail 不含项目设置;切片 b 误断 detail.workdayStart,A11)
        assertEquals(
            "08:30",
            jdbc
                .queryForObject("select workday_start from project where key = 'CHE'", String::class.java),
        )

        // 倒挂 → 400
        authed
            .patch()
            .uri("/api/projects/CHE/settings")
            .body(mapOf("workdayStart" to "18:00", "workdayEnd" to "09:00"))
            .exchange()
            .expectStatus()
            .isBadRequest()

        // 非法格式 → 400
        authed
            .patch()
            .uri("/api/projects/CHE/settings")
            .body(mapOf("workdayStart" to "9am"))
            .exchange()
            .expectStatus()
            .isBadRequest()
    }

    /** YPJ-235(spec 0146,拍板案①):due 缺省时系统按 estimate 工作日铺算;显式 due 覆盖;空串清除不回填。 */
    @Test
    fun `双向互算——铺算、反推、清除回填、警告(YPJ-243,推翻 0146 清除不回填)`() {
        val (authed, itemId) = setup()

        // 1) estimate 5h + start date-only(无 due)→ 系统算:09:00+5h → 当日 14:00(0146 既有果随因)
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("startDate" to "2026-09-01", "estimateValue" to "5", "estimateUnit" to "hour"))
            .exchange()
            .expectStatus()
            .isOk()
        assertEquals("2026-09-01T14:00", detail(authed, itemId)["dueDate"].asText())

        // 2) 显式 due(单独发,后写者为准)→ estimate 反推:存量 start 09-01T09:00 → 09-30T12:00 = 264 工作小时(9h/日,周六日照算)
        val dueWin =
            authed
                .patch()
                .uri("/api/items/$itemId")
                .body(mapOf("dueDate" to "2026-09-30T12:00"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val dueTree = json.readTree(dueWin)
        assertEquals(264.0, dueTree["estimateValue"].asDouble(), 0.01)
        assertEquals("hour", dueTree["estimateUnit"].asText())
        assertTrue(dueTree["warnings"].any { "预估已按起止重算" in it.asText() })
        assertEquals("2026-09-30T12:00", detail(authed, itemId)["dueDate"].asText())

        // 3) 再设 start 不带 due → 按新 estimate 重算覆盖:264h 从 09-02T09:00 铺到 10-01T12:00
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("startDate" to "2026-09-02"))
            .exchange()
            .expectStatus()
            .isOk()
        assertEquals("2026-10-01T12:00", detail(authed, itemId)["dueDate"].asText())

        // 4) 空串清除 → 预估有值,回填铺算(H2,推翻 0146「清除不回填」)
        val clearWin =
            authed
                .patch()
                .uri("/api/items/$itemId")
                .body(mapOf("dueDate" to ""))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertTrue(json.readTree(clearWin)["warnings"].any { "回填" in it.asText() })
        assertEquals("2026-10-01T12:00", detail(authed, itemId)["dueDate"].asText())

        // 5) 无 estimate 的 item:start 不带 due → due 保持 null
        val item2Id = createItem(authed, "CHE", "z")["itemId"].asText()
        authed
            .patch()
            .uri("/api/items/$item2Id")
            .body(mapOf("startDate" to "2026-09-01"))
            .exchange()
            .expectStatus()
            .isOk()
        assertTrue(detail(authed, item2Id)["dueDate"].isNull)
    }
}
