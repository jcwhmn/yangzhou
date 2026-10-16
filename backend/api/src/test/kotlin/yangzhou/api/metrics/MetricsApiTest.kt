package yangzhou.api.metrics

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * YPJ-45 Flow Metrics API 黑盒(spec 0038 ③)。
 * 现态走 API;历史时刻经 jdbc 固定(createdAt / item_status_history / item_group_member),
 * 断言数值全部可手算;「rename 不破数」覆盖 id 级口径。
 *
 * fixture 时刻表(2026,周桶锚:09-21/09-28/10-05/10-12/10-19 均为周一):
 *  item1 S1员  09-28T09 建 →09-29 离start →10-02T09 Done   (cycle 3,lead 4)
 *  item2 S1员  10-01T09 建 →10-03 In Progress →10-05 QA   (未完成)
 *  item3 S1员  10-05T09 建,停在 To Do
 *  item4 S1员  09-29T09 建 →09-30 离start →10-20T09 Done  (cycle 20,lead 21,跨 sprint 尾)
 *  item5 无组  10-06T09 建 →10-07 →10-08T09 Done          (cycle 1,lead 2)
 *  item6 S2员  10-15T09 建 →10-16 →10-17T09 Done          (S2 velocity 1)
 *  item7 无组  10-09T09 建,null→Done 10-10T09             (无离 start 行 → noCycle)
 *  item8 无组  10-09T09 建 →10-11 In Progress              (aging 第二行)
 */
class MetricsApiTest : AbstractApiTest() {


    private lateinit var authed: RestTestClient
    private lateinit var status: Map<String, String> // 状态名 → objectId
    private var meId: String = ""
    private var s1: String = ""
    private var s2: String = ""

    // ---------- fixture ----------

    private fun setupProject() {
        authed = bootstrapAndAuth()
        createProject(authed, "MTR")
        status = get("/api/projects/MTR")["statuses"].associate { it["name"].asText() to it["statusId"].asText() }
        meId = get("/api/members")[0]["memberId"].asText()
        s1 = post("/api/projects/MTR/sprints", mapOf("name" to "S1", "startDate" to "2026-10-01", "endDate" to "2026-10-14"))["sprintId"].asText()
        s2 = post("/api/projects/MTR/sprints", mapOf("name" to "S2", "startDate" to "2026-10-15", "endDate" to "2026-10-28"))["sprintId"].asText()
    }

    private fun newItem(
        title: String,
        createdAt: String,
        transitions: List<Triple<String?, String, String>> = emptyList(), // (from名, to名, 时刻)
        currentStatus: String? = null,
        sprintId: String? = null,
        joinedAt: String? = null,
        assignee: Boolean = false,
    ): String {
        val itemId = createItem(authed, "MTR", title)["itemId"].asText()
        if (assignee) put("/api/items/$itemId/assignee", mapOf("assigneeItemId" to meId))
        if (sprintId != null) put("/api/items/$itemId/sprint", mapOf("sprintId" to sprintId))
        val iid = jdbc.queryForObject("select id from item where object_id = ?::uuid", Long::class.java, itemId)
        jdbc.update("update item set created_at = ?::timestamptz where id = ?", createdAt, iid)
        jdbc.update("update item_status_history set created_at = ?::timestamptz where item_id = ? and source = 'created'", createdAt, iid)
        for ((fromName, toName, at) in transitions) {
            val fromId = fromName?.let { statusInternalId(it) }
            jdbc.update(
                "insert into item_status_history (object_id, item_id, from_status_id, to_status_id, source, created_at, updated_at) " +
                    "values (?, ?, ?, ?, 'manual', ?::timestamptz, ?::timestamptz)",
                UUID.randomUUID(), iid, fromId, statusInternalId(toName), at, at,
            )
        }
        if (currentStatus != null) {
            jdbc.update("update item set status_object_id = ?::uuid where id = ?", status[currentStatus]!!, iid)
        }
        if (sprintId != null && joinedAt != null) {
            val gid = jdbc.queryForObject("select id from item_group where object_id = ?::uuid", Long::class.java, sprintId)
            jdbc.update("update item_group_member set created_at = ?::timestamptz where item_id = ? and group_id = ?", joinedAt, iid, gid)
            jdbc.update("update item_group_member_history set added_at = ?::timestamptz where item_id = ? and group_id = ?", joinedAt, iid, gid)
        }
        return itemId
    }

    private fun statusInternalId(name: String): Long =
        jdbc.queryForObject("select id from status where object_id = ?::uuid", Long::class.java, status[name])

    private fun seedAll() {
        newItem("一", "2026-09-28T09:00:00Z", listOf(Triple("To Do", "In Progress", "2026-09-29T09:00:00Z"), Triple("In Progress", "Done", "2026-10-02T09:00:00Z")), "Done", s1, "2026-10-01T00:00:00Z", assignee = true)
        newItem("二", "2026-10-01T09:00:00Z", listOf(Triple("To Do", "In Progress", "2026-10-03T09:00:00Z"), Triple("In Progress", "QA", "2026-10-05T09:00:00Z")), "QA", s1, "2026-10-03T00:00:00Z", assignee = true)
        newItem("三", "2026-10-05T09:00:00Z", emptyList(), "To Do", s1, "2026-10-05T00:00:00Z")
        newItem("四", "2026-09-29T09:00:00Z", listOf(Triple("To Do", "In Progress", "2026-09-30T09:00:00Z"), Triple("In Progress", "Done", "2026-10-20T09:00:00Z")), "Done", s1, "2026-10-01T00:00:00Z")
        newItem("五", "2026-10-06T09:00:00Z", listOf(Triple("To Do", "In Progress", "2026-10-07T09:00:00Z"), Triple("In Progress", "Done", "2026-10-08T09:00:00Z")), "Done")
        newItem("六", "2026-10-15T09:00:00Z", listOf(Triple("To Do", "In Progress", "2026-10-16T09:00:00Z"), Triple("In Progress", "Done", "2026-10-17T09:00:00Z")), "Done", s2, "2026-10-15T00:00:00Z")
        newItem("七", "2026-10-09T09:00:00Z", listOf(Triple(null, "Done", "2026-10-10T09:00:00Z")), "Done")
        newItem("八", "2026-10-09T09:00:00Z", listOf(Triple("To Do", "In Progress", "2026-10-11T09:00:00Z")), "In Progress")
    }

    // ---------- helpers ----------

    private fun get(uri: String): JsonNode {
        val r = authed.get().uri(uri).exchange().expectBody(String::class.java).returnResult()
        if (r.status.value() >= 300) println("GET_FAIL $uri ${r.status.value()} ${r.responseBody}")
        assertEquals(200, r.status.value(), "GET $uri")
        return json.readTree(r.responseBody!!)
    }

    private fun post(uri: String, body: Any): JsonNode {
        val r = authed.post().uri(uri).body(body).exchange().expectBody(String::class.java).returnResult()
        if (r.status.value() >= 300) println("POST_FAIL $uri ${r.status.value()} ${r.responseBody}")
        assertTrue(r.status.is2xxSuccessful, "POST $uri")
        return json.readTree(r.responseBody!!)
    }

    private fun put(uri: String, body: Any) {
        val r = authed.put().uri(uri).body(body).exchange().expectBody(String::class.java).returnResult()
        if (r.status.value() >= 300) println("PUT_FAIL $uri ${r.status.value()} ${r.responseBody}")
        assertTrue(r.status.is2xxSuccessful, "PUT $uri")
    }

    private fun patch(uri: String, body: Any): JsonNode {
        val r = authed.patch().uri(uri).body(body).exchange().expectBody(String::class.java).returnResult()
        if (r.status.value() >= 300) println("PATCH_FAIL $uri ${r.status.value()} ${r.responseBody}")
        assertTrue(r.status.is2xxSuccessful, "PATCH $uri")
        return json.readTree(r.responseBody!!)
    }

    private fun counts(node: JsonNode): Map<String, Int> =
        node.fields().asSequence().associate { it.key to it.value.asInt() }

    // ---------- 用例 ----------

    @Test
    fun workload_三桶与拆分() {
        setupProject(); seedAll()
        val r = get("/api/projects/MTR/metrics/workload")
        assertEquals(1, r["backlogSize"].asInt())
        assertEquals(2, r["wip"].asInt())
        assertEquals(5, r["done"].asInt())
        val byStatus = r["byStatus"].associate { it["name"].asText() to it["count"].asInt() }
        assertEquals(mapOf("To Do" to 1, "In Progress" to 1, "In Review" to 0, "QA" to 1, "Done" to 5), byStatus)
        val byAssignee = r["byAssignee"].associate { (it["displayName"]?.takeUnless { n -> n.isNull }?.asText()) to it["count"].asInt() }
        assertEquals(2, byAssignee["me"]); assertEquals(6, byAssignee[null])
        assertEquals(8, r["byType"][0]["count"].asInt())
        assertTrue(r["definitions"]["workload"].asText().contains("WIP"))
    }

    @Test
    fun workload_byAssignee状态堆叠() {
        setupProject()
        val a = createItem(authed, "MTR", "甲")["itemId"].asText()
        put("/api/items/$a/assignee", mapOf("assigneeItemId" to meId))
        patch("/api/items/$a", mapOf("statusItemId" to status["In Progress"]))
        val b = createItem(authed, "MTR", "乙")["itemId"].asText()
        put("/api/items/$b/assignee", mapOf("assigneeItemId" to meId))
        val c = createItem(authed, "MTR", "丙")["itemId"].asText()
        put("/api/items/$c/assignee", mapOf("assigneeItemId" to meId))
        patch("/api/items/$c", mapOf("statusItemId" to status["QA"]))
        val r = get("/api/projects/MTR/metrics/workload")
        val me = r["byAssignee"].first { !it["displayName"].isNull }
        assertEquals(3, me["count"].asInt())
        assertEquals(mapOf("In Progress" to 1, "QA" to 1), counts(me["byStatus"]))
        // 未指派 item 停在 To Do(start)= 非 WIP:byStatus 为空
        createItem(authed, "MTR", "丁")
        val r2 = get("/api/projects/MTR/metrics/workload")
        val unassigned = r2["byAssignee"].first { it["displayName"].isNull }
        assertEquals(emptyMap<String, Int>(), counts(unassigned["byStatus"]))
    }

    @Test
    fun throughput_周桶与groupId过滤() {
        setupProject(); seedAll()
        val r = get("/api/projects/MTR/metrics/throughput?from=2026-09-25T00:00:00Z&to=2026-10-25T00:00:00Z&bucket=week")
        assertEquals(listOf(0, 1, 2, 1, 1), r["buckets"].map { it["completed"].asInt() }) // item6 10-17 落 10-12 周
        assertEquals(listOf("2026-09-21", "2026-09-28", "2026-10-05", "2026-10-12", "2026-10-19"), r["buckets"].map { it["start"].asText() })
        val s1Only = get("/api/projects/MTR/metrics/throughput?from=2026-09-25T00:00:00Z&to=2026-10-25T00:00:00Z&bucket=week&groupId=$s1")
        assertEquals(listOf(0, 1, 0, 0, 1), s1Only["buckets"].map { it["completed"].asInt() })
        val day = get("/api/projects/MTR/metrics/throughput?from=2026-10-01T00:00:00Z&to=2026-10-03T00:00:00Z&bucket=day")
        assertEquals(listOf(0, 1, 0), day["buckets"].map { it["completed"].asInt() })
    }

    @Test
    fun flowTimes_百分位与noCycle_未完成排除() {
        setupProject(); seedAll()
        val r = get("/api/projects/MTR/metrics/flow-times?from=2026-09-25T00:00:00Z&to=2026-10-25T00:00:00Z")
        assertEquals(5, r["items"].size())
        assertEquals(1, r["noCycleCount"].asInt())
        // cycles [1,1,3,20](item5/item6/item1/item4);leads [1,2,2,4,21](+item7 无 cycle)
        assertEquals(2.0, r["percentiles"]["cycleP50"].asDouble(), 1e-9)
        assertEquals(12.35, r["percentiles"]["cycleP85"].asDouble(), 1e-9)
        assertEquals(17.45, r["percentiles"]["cycleP95"].asDouble(), 1e-9)
        assertEquals(2.0, r["percentiles"]["leadP50"].asDouble(), 1e-9)
        assertEquals(10.8, r["percentiles"]["leadP85"].asDouble(), 1e-9)
        assertEquals(17.6, r["percentiles"]["leadP95"].asDouble(), 1e-9)
        // 未完成(item2 QA)item 不在散点
        assertTrue(r["items"].none { it["number"].asInt() == 2 })
        // item1 数值:id 级散点可断言
        val one = r["items"].first { it["title"].asText() == "一" }
        assertEquals(3.0, one["cycleDays"].asDouble(), 1e-9)
        assertEquals(4.0, one["leadDays"].asDouble(), 1e-9)
    }

    @Test
    fun cfd_按日重放() {
        setupProject(); seedAll()
        val r = get("/api/projects/MTR/metrics/cfd?from=2026-09-28T00:00:00Z&to=2026-10-04T00:00:00Z")
        val byDate = r["series"].associate { it["date"].asText() to counts(it["counts"]) }
        val names = r["statuses"].associate { it["name"].asText() to it["statusId"].asText() }
        assertEquals(mapOf(names["To Do"]!! to 1), byDate["2026-09-28"])
        assertEquals(mapOf(names["To Do"]!! to 1, names["In Progress"]!! to 1), byDate["2026-09-29"]) // item1 当天已离 start
        assertEquals(mapOf(names["To Do"]!! to 1, names["In Progress"]!! to 2), byDate["2026-10-01"])
        assertEquals(mapOf(names["To Do"]!! to 1, names["In Progress"]!! to 1, names["Done"]!! to 1), byDate["2026-10-02"])
        assertEquals(mapOf(names["In Progress"]!! to 2, names["Done"]!! to 1), byDate["2026-10-04"])
    }

    @Test
    fun aging_进入时刻与队列宽() {
        setupProject(); seedAll()
        val r = get("/api/projects/MTR/metrics/aging")
        val items = r["items"]
        assertEquals(2, items.size())
        assertEquals("二", items[0]["title"].asText()) // 停留更久在前
        assertEquals("2026-10-05T09:00:00Z", items[0]["enteredAt"].asText())
        assertEquals("QA", items[0]["statusName"].asText())
        assertEquals("2026-10-11T09:00:00Z", items[1]["enteredAt"].asText())
        assertTrue(items[0]["ageDays"].asDouble() > items[1]["ageDays"].asDouble())
        val qw = r["queueWidth"].associate { it["name"].asText() to it["count"].asInt() }
        assertEquals(mapOf("To Do" to 1, "In Progress" to 1, "In Review" to 0, "QA" to 1), qw) // final 不在队列;0 计数保留(图列稳定)
    }

    @Test
    fun burndown_理想与实际() {
        setupProject(); seedAll()
        val r = get("/api/projects/MTR/metrics/burndown?groupId=$s1")
        assertEquals(4, r["total"].asInt())
        assertEquals(14, r["ideal"].size()) // 10-01..10-14(today ≥ 10-15)
        assertEquals(4, r["ideal"][0]["remaining"].asInt())
        assertEquals(0, r["ideal"][13]["remaining"].asInt())
        assertEquals(4, r["actual"][0]["remaining"].asInt())
        assertEquals(3, r["actual"][1]["remaining"].asInt()) // item1 于 10-02 完成
        assertEquals(3, r["actual"][13]["remaining"].asInt()) // item4 10-20 在窗外
    }

    @Test
    fun burnup_完成与scope() {
        setupProject(); seedAll()
        val r = get("/api/projects/MTR/metrics/burnup?groupId=$s1")
        assertEquals(0, r["done"][0]["cumulative"].asInt())
        assertEquals(1, r["done"][1]["cumulative"].asInt()) // 10-02
        assertEquals(1, r["done"][13]["cumulative"].asInt())
        assertEquals(2, r["scope"][0]["total"].asInt()) // item1/item4 10-01 加入
        assertEquals(3, r["scope"][2]["total"].asInt()) // item2 10-03
        assertEquals(4, r["scope"][13]["total"].asInt())
    }

    @Test
    fun velocity_双sprint计数() {
        setupProject(); seedAll()
        val r = get("/api/projects/MTR/metrics/velocity")
        assertEquals(2, r["sprints"].size())
        assertEquals("S1", r["sprints"][0]["name"].asText())
        assertEquals(1, r["sprints"][0]["completed"].asInt()) // item4 10-20 跨尾不计
        assertEquals("S2", r["sprints"][1]["name"].asText())
        assertEquals(1, r["sprints"][1]["completed"].asInt()) // item6
    }

    @Test
    fun rename_后数值不变_id级() {
        setupProject(); seedAll()
        val before = get("/api/projects/MTR/metrics/flow-times?from=2026-09-25T00:00:00Z&to=2026-10-25T00:00:00Z")
        patch("/api/statuses/${status["Done"]}", mapOf("name" to "已完成"))
        val after = get("/api/projects/MTR/metrics/flow-times?from=2026-09-25T00:00:00Z&to=2026-10-25T00:00:00Z")
        assertEquals(before["items"].size(), after["items"].size())
        assertEquals(before["percentiles"]["cycleP50"].asDouble(), after["percentiles"]["cycleP50"].asDouble(), 1e-9)
        val wl = get("/api/projects/MTR/metrics/workload")
        assertTrue(wl["byStatus"].any { it["name"].asText() == "已完成" && it["count"].asInt() == 5 })
    }

    @Test
    fun 负例_未知项目_坏参数_未知组() {
        setupProject(); seedAll()
        authed.get().uri("/api/projects/NOPE/metrics/workload").exchange().expectStatus().isNotFound()
        authed.get().uri("/api/projects/MTR/metrics/throughput?from=2026-09-25T00:00:00Z&to=2026-10-25T00:00:00Z&bucket=month")
            .exchange().expectStatus().isBadRequest()
        authed.get().uri("/api/projects/MTR/metrics/throughput?from=2026-10-25T00:00:00Z&to=2026-09-25T00:00:00Z")
            .exchange().expectStatus().isBadRequest()
        authed.get().uri("/api/projects/MTR/metrics/flow-times?from=2024-01-01T00:00:00Z&to=2026-01-01T00:00:00Z")
            .exchange().expectStatus().isBadRequest() // 窗口上限 365 天
        authed.get().uri("/api/projects/MTR/metrics/burndown?groupId=${UUID.randomUUID()}")
            .exchange().expectStatus().isNotFound()
    }

    // ---------- YPJ-54:留痕与 scope/reopen ----------

    @Test
    fun assignItem_全程留痕() {
        setupProject()
        val id = createItem(authed, "MTR", "留痕")["itemId"].asText()
        val iid = jdbc.queryForObject("select id from item where object_id = ?::uuid", Long::class.java, id)
        fun rows(openOnly: Boolean): Int = jdbc.queryForObject(
            "select count(*) from item_group_member_history where item_id = ? ${if (openOnly) "and removed_at is null" else ""}",
            Int::class.java, iid,
        )
        put("/api/items/$id/sprint", mapOf("sprintId" to s1))
        assertEquals(1, rows(false))
        assertEquals(1, rows(true)) // 加入:S1 在册
        put("/api/items/$id/sprint", mapOf("sprintId" to s2))
        assertEquals(2, rows(false))
        assertEquals(1, rows(true)) // 换组:旧 closed,S2 在册
        put("/api/items/$id/sprint", mapOf("sprintId" to null))
        assertEquals(2, rows(false))
        assertEquals(0, rows(true)) // 移出:全 closed
        val rel = post("/api/projects/MTR/releases", mapOf("name" to "R1"))["releaseId"].asText()
        put("/api/items/$id/release", mapOf("releaseId" to rel))
        assertEquals(3, rows(false)) // release 型同表留痕
    }

    @Test
    fun create带sprintId_落episode() {
        setupProject()
        val r = post("/api/projects/MTR/items", mapOf("title" to "c1", "sprintId" to s1))
        val iid = jdbc.queryForObject("select id from item where object_id = ?::uuid", Long::class.java, r["itemId"].asText())
        assertEquals(1, jdbc.queryForObject("select count(*) from item_group_member_history where item_id = ?", Int::class.java, iid))
    }

    @Test
    fun scopeChange_起止在册与加减次数() {
        setupProject()
        newItem("sca", "2026-10-02T09:00:00Z", sprintId = s1, joinedAt = "2026-10-02T09:00:00Z")
        val b = newItem("scb", "2026-09-28T09:00:00Z", sprintId = s1, joinedAt = "2026-09-28T09:00:00Z")
        jdbc.update(
            "update item_group_member_history set removed_at = '2026-10-10T09:00:00Z'::timestamptz where item_id = (select id from item where object_id = ?::uuid)", b)
        jdbc.update("delete from item_group_member where item_id = (select id from item where object_id = ?::uuid)", b)
        val r = get("/api/projects/MTR/metrics/scope-change?groupId=$s1")
        assertEquals(1, r["original"].asInt()) // 10-01 00:00:b 在册
        assertEquals(1, r["final"].asInt()) // 10-15 00:00:a 在册(b 已离)
        assertEquals(1, r["adds"].asInt())
        assertEquals(1, r["removes"].asInt())
        assertEquals(0, r["net"].asInt())
        val s3 = post("/api/projects/MTR/sprints", mapOf("name" to "S3"))["sprintId"].asText()
        authed.get().uri("/api/projects/MTR/metrics/scope-change?groupId=$s3").exchange().expectStatus().isBadRequest()
    }

    @Test
    fun burnup_scope离组回落() {
        setupProject()
        newItem("bua", "2026-10-01T09:00:00Z", sprintId = s1, joinedAt = "2026-10-01T00:00:00Z")
        val b = newItem("bub", "2026-10-01T09:00:00Z", sprintId = s1, joinedAt = "2026-10-01T00:00:00Z")
        jdbc.update(
            "update item_group_member_history set removed_at = '2026-10-05T12:00:00Z'::timestamptz where item_id = (select id from item where object_id = ?::uuid)", b)
        jdbc.update("delete from item_group_member where item_id = (select id from item where object_id = ?::uuid)", b)
        val r = get("/api/projects/MTR/metrics/burnup?groupId=$s1")
        assertEquals(2, r["scope"][3]["total"].asInt()) // 10-04 末:b 仍在册
        assertEquals(1, r["scope"][4]["total"].asInt()) // 10-05 末:已离
    }

    @Test
    fun reopenRate_重开与窗口() {
        setupProject()
        newItem("rr一", "2026-10-01T09:00:00Z",
            listOf(Triple(null, "Done", "2026-10-02T09:00:00Z"), Triple("Done", "In Progress", "2026-10-03T09:00:00Z")),
            "In Progress")
        newItem("rr二", "2026-10-01T09:00:00Z",
            listOf(Triple(null, "Done", "2026-10-04T09:00:00Z")), "Done")
        val full = get("/api/projects/MTR/metrics/reopen-rate?from=2026-10-01T00:00:00Z&to=2026-10-31T00:00:00Z")
        assertEquals(2, full["completed"].asInt())
        assertEquals(1, full["reopened"].asInt())
        assertEquals(0.5, full["rate"].asDouble(), 0.0001)
        val narrow = get("/api/projects/MTR/metrics/reopen-rate?from=2026-10-10T00:00:00Z&to=2026-10-20T00:00:00Z")
        assertEquals(0, narrow["completed"].asInt())
        assertTrue(narrow["rate"].isNull) // 分母 0 → null
    }
}
