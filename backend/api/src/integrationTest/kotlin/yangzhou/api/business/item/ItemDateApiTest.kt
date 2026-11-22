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
        // YPJ-226(spec 0163):新建项目默认工作制;本文件测墙钟制既有语义,显式回 wall
        authed
            .patch()
            .uri("/api/projects/CHE/settings")
            .body(mapOf("workMode" to "wall"))
            .exchange()
            .expectStatus()
            .isOk()
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
        assertEquals("2026-09-01T00:00", detail(authed, itemId)["startDate"].asText()) // date-only → 窗起点 00:00(V67 24h 缺省)
        assertEquals("2026-09-30T23:59:59", detail(authed, itemId)["dueDate"].asText()) // date-only due → 当日日末(秒精度,防 timestamp(6) 进位成次日零点)

        // 不传日期字段 → 保持不变
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("title" to "改名了"))
            .exchange()
            .expectStatus()
            .isOk()
        assertEquals("2026-09-30T23:59:59", detail(authed, itemId)["dueDate"].asText())

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
            .body(mapOf("startDate" to "2019-12-01", "dueDate" to "2020-01-01")) // 明显过去(§12.5:due 须带 start)
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

        // 1) estimate 5h + start date-only(无 due)→ 系统算:00:00+5h → 当日 05:00(24h 全天窗,V67 缺省;0146 既有果随因)
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("startDate" to "2026-09-01", "estimateValue" to "5", "estimateUnit" to "hour"))
            .exchange()
            .expectStatus()
            .isOk()
        assertEquals("2026-09-01T05:00", detail(authed, itemId)["dueDate"].asText())

        // 2) 显式 due(单独发,后写者为准)→ estimate 反推:存量 start 09-01T00:00 → 09-30T12:00 = 708h(24h 全天窗=纯钟表跨度)
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
        assertEquals(708.0, dueTree["estimateValue"].asDouble(), 0.01)
        assertEquals("hour", dueTree["estimateUnit"].asText())
        assertTrue(dueTree["warnings"].any { "预估已按起止重算" in it.asText() })
        assertEquals("2026-09-30T12:00", detail(authed, itemId)["dueDate"].asText())

        // 3) 再设 start 不带 due → 按新 estimate 重算覆盖:708h 从 09-02T00:00 铺到 10-01T12:00
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

    @Test
    fun `due-only 契约拒绝——due 有而 start 无 400(SRS 12-5,YPJ-245)`() {
        val (authed, itemId) = setup()

        // 1) 显式设 due、无 start → 400,存量未写
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("dueDate" to "2026-12-01"))
            .exchange()
            .expectStatus()
            .isBadRequest()
        assertTrue(detail(authed, itemId)["dueDate"].isNull)

        // 2) start+due 同发 → 合法(排期窗口)
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("startDate" to "2026-11-23", "dueDate" to "2026-12-01"))
            .exchange()
            .expectStatus()
            .isOk()

        // 2b) 只带 due 的 PATCH → 200 且存量 start 保持(探针回归:旧码把 start 抹成 null)
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("dueDate" to "2026-12-02"))
            .exchange()
            .expectStatus()
            .isOk()
        val d2 = detail(authed, itemId)
        assertEquals("2026-11-23T00:00", d2["startDate"].asText())
        assertEquals("2026-12-02T23:59:59", d2["dueDate"].asText())

        // 3) 清 start 残留 due → 400(结果态判定)
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("startDate" to ""))
            .exchange()
            .expectStatus()
            .isBadRequest()
        // 400 回滚:dates 未落库(start 仍在、due 未动)
        val d = detail(authed, itemId)
        assertEquals("2026-11-23T00:00", d["startDate"].asText())
        assertEquals("2026-12-02T23:59:59", d["dueDate"].asText())
    }

    /** YPJ-226(spec 0163):工作制项目 schedule 铺算跳周末——周五起 10h 窗 9-18 → due 落周一 10:00。 */
    @Test
    fun `工作制项目 start 加 estimate 铺算跳周末`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "WMD", "workmode")
        authed
            .patch()
            .uri("/api/projects/WMD/settings")
            .body(mapOf("workMode" to "work", "workdayStart" to "09:00", "workdayEnd" to "18:00"))
            .exchange()
            .expectStatus()
            .isOk()
        val itemId = createItem(authed, "WMD", "x")["itemId"].asText()

        // 2026-11-20=周五:09:00 起 9h 当日铺满(收工 18:00),余 1h 跳周末 → 周一 11-23 10:00
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("startDate" to "2026-11-20", "estimateValue" to "10", "estimateUnit" to "hour"))
            .exchange()
            .expectStatus()
            .isOk()
        val d = detail(authed, itemId)
        assertEquals("2026-11-20T09:00", d["startDate"].asText())
        assertEquals("2026-11-23T10:00", d["dueDate"].asText())
    }
}
