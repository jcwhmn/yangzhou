package yangzhou.api.business.project

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-121:V8-S2 甘特数据黑盒(树序/日期/超期)。 */
class GanttApiTest : AbstractApiTest() {
    @Test
    fun `甘特按树序返回——日期与超期正确——未知项目 404`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")

        // 父 item(带日期，超期，带 estimate)+ 子 item(无日期，被依赖未终态 → 父 blocked)
        val parent = createItem(authed, "CHE", "父任务")
        authed
            .patch()
            .uri("/api/items/${parent["itemId"].asText()}")
            .body(
                mapOf(
                    "startDate" to "2020-01-01",
                    "dueDate" to "2020-01-31",
                    "estimateValue" to 2,
                    "estimateUnit" to "hour",
                ),
            )
            .exchange()
            .expectStatus()
            .isOk()
        // 父依赖子(子未终态 → 父 blocked)
        val child = createItem(authed, "CHE", "子任务", parentItemId = parent["itemId"].asText())
        authed
            .post()
            .uri("/api/items/${parent["itemId"].asText()}/dependencies")
            .body(mapOf("dependsOnItemId" to child["itemId"].asText()))
            .exchange()
            .expectStatus()
            .isCreated()

        val rows =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/CHE/gantt?scope=all")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )["rows"]

        assertEquals(2, rows.size())
        // 树序:父在前,子在后
        assertEquals("父任务", rows[0]["title"].asText())
        assertEquals("子任务", rows[1]["title"].asText())
        assertEquals(rows[0]["itemId"].asText(), rows[1]["parentItemId"].asText())
        // 日期与超期(2020 due，非终态)+ estimate + blocked(依赖未终态子项)
        assertEquals("2020-01-01T00:00", rows[0]["startDate"].asText()) // date-only start → 当日零点(V67 全天窗)
        assertEquals("2020-01-31T23:59:59", rows[0]["dueDate"].asText()) // date-only due → 当日日末秒精度(9cd8f6f)
        assertTrue(rows[0]["overdue"].asBoolean())
        assertEquals(2.0, rows[0]["estimateValue"].asDouble())
        assertEquals("hour", rows[0]["estimateUnit"].asText())
        assertTrue(rows[0]["blocked"].asBoolean())
        assertTrue(!rows[1]["blocked"].asBoolean())
        assertEquals(2.0, rows[0]["estimateValue"].asDouble())
        assertEquals("hour", rows[0]["estimateUnit"].asText())
        // 子任务无日期 → 不超期
        assertTrue(!rows[1]["overdue"].asBoolean())
        assertEquals(1, rows[1]["depth"].asInt())
        // YPJ-218(spec 0139):statusType 随行下发(FINAL 置灰判定);父未终态 → 非 FINAL
        assertTrue(rows[0]["statusType"].asText() != "FINAL")

        authed
            .get()
            .uri("/api/projects/NOPE/gantt")
            .exchange()
            .expectStatus()
            .isNotFound()
    }

    @Test
    fun `YPJ-218 B1 孤儿兑底——父被清除后子按根显示`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "ORP")
        val parent = createItem(authed, "ORP", "将被清除的父")
        val child = createItem(authed, "ORP", "孤儿子", parentItemId = parent["itemId"].asText())
        createItem(authed, "ORP", "普通根")

        // 造孤儿:删父(子树软删)→ 只恢复子(其 parentItemId 指向软删中的父,gantt 全集过滤后即孤儿)
        authed.delete().uri("/api/items/${parent["itemId"].asText()}").exchange().expectStatus().isNoContent()
        authed
            .post()
            .uri("/api/recycle-bin/${child["itemId"].asText()}/restore")
            .exchange()
            .expectStatus()
            .isNoContent()

        val rows =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/ORP/gantt?scope=all")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )["rows"]

        // 父消失,孤儿子兑底为根:depth 0、仍保留原 parentItemId(数据不撒谎,展示层兜底)
        assertEquals(2, rows.size())
        assertEquals("孤儿子", rows[0]["title"].asText())
        assertEquals(0, rows[0]["depth"].asInt())
        assertEquals(parent["itemId"].asText(), rows[0]["parentItemId"].asText())
        assertEquals("普通根", rows[1]["title"].asText())
    }

    @Test
    fun `YPJ-220 spec 0141 范围过滤——缺省 board 祖先链不截断 group 多选并集 milestone 与负例`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "SCO")

        val parent = createItem(authed, "SCO", "父")
        val a = createItem(authed, "SCO", "A", parentItemId = parent["itemId"].asText())
        val b = createItem(authed, "SCO", "B", parentItemId = parent["itemId"].asText())
        createItem(authed, "SCO", "圈外O")

        fun gantt(q: String) = json.readTree(
            authed
                .get()
                .uri("/api/projects/SCO/gantt$q")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!,
        )["rows"].map { it["title"].asText() }

        fun group(name: String): String = json.readTree(
            authed
                .post()
                .uri("/api/projects/SCO/groups")
                .body(mapOf("typeKey" to "sprint", "name" to name))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!,
        )["groupId"].asText()

        val g1 = group("S1")
        val g2 = group("S2")
        authed
            .put()
            .uri("/api/items/${a["itemId"].asText()}/group-membership")
            .body(mapOf("groupId" to g1, "groupType" to "sprint"))
            .exchange()
            .expectStatus()
            .isOk()
        authed
            .put()
            .uri("/api/items/${b["itemId"].asText()}/group-membership")
            .body(mapOf("groupId" to g2, "groupType" to "sprint"))
            .exchange()
            .expectStatus()
            .isOk()

        // 无 active:缺省 board = 非 backlog 全量(onBoard/组籍者,与看板根页一致;圈外O 无组籍=backlog 被排除)
        assertEquals(listOf("父", "A", "B"), gantt(""))

        // g1 激活:缺省 board = active 成员 A + 祖先父(祖先链不截断);B/圈外O 不在
        authed
            .post()
            .uri("/api/projects/SCO/groups/$g1/actions/start")
            .exchange()
            .expectStatus()
            .isOk()
        assertEquals(listOf("父", "A"), gantt(""))

        // group 多选 g1,g2 → 并集 A,B + 祖先父;圈外O 仍不在
        assertEquals(listOf("父", "A", "B"), gantt("?scope=group&scopeIds=$g1,$g2"))

        // milestone 范围:挂 A → A + 祖先父
        val ms =
            json.readTree(
                authed
                    .post()
                    .uri("/api/projects/SCO/milestones")
                    .body(mapOf("name" to "M1"))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )["milestoneId"].asText()
        authed
            .put()
            .uri("/api/projects/SCO/milestones/$ms/items")
            .body(mapOf("itemIds" to listOf(a["itemId"].asText())))
            .exchange()
            .expectStatus()
            .isOk()
        assertEquals(listOf("父", "A"), gantt("?scope=milestone&scopeIds=$ms"))

        // all 兜底:4 行
        assertEquals(listOf("父", "A", "B", "圈外O"), gantt("?scope=all"))

        // 负例:坏 scope 400 / group 无 scopeIds 400 / 坏 scopeId 404 / group 好格式不存在 404
        authed.get().uri("/api/projects/SCO/gantt?scope=foo").exchange().expectStatus().isBadRequest()
        authed.get().uri("/api/projects/SCO/gantt?scope=group").exchange().expectStatus().isBadRequest()
        authed
            .get()
            .uri("/api/projects/SCO/gantt?scope=group&scopeIds=not-a-uuid")
            .exchange()
            .expectStatus()
            .isBadRequest()
        authed
            .get()
            .uri("/api/projects/SCO/gantt?scope=group&scopeIds=00000000-0000-0000-0000-000000000000")
            .exchange()
            .expectStatus()
            .isNotFound()
    }

    @Test
    fun `YPJ-222 spec 0147 实际起止推导——清单完成率与负责人名随行下发`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "ACT")

        val meId =
            json.readTree(
                authed
                    .get()
                    .uri("/api/members")
                    .exchange()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )[0]["memberId"].asText()
        val statusIds =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/ACT")
                    .exchange()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )["statuses"].associate { it["name"].asText() to it["statusId"].asText() }

        fun patchItem(id: String, body: Map<String, Any?>) {
            authed.patch().uri("/api/items/$id").body(body).exchange().expectStatus().isOk()
        }

        // 甲:start+due → 迁 In Progress → Done;指派给我;清单 2 条完成 1 条
        val a = createItem(authed, "ACT", "甲")["itemId"].asText()
        patchItem(a, mapOf("startDate" to "2026-01-01", "dueDate" to "2026-01-10"))
        authed.put().uri("/api/items/$a/assignee").body(mapOf("assigneeItemId" to meId)).exchange().expectStatus().isOk()
        val t1 =
            json.readTree(
                authed
                    .post()
                    .uri("/api/items/$a/checklist")
                    .body(mapOf("text" to "t1"))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )["checklistItemId"].asText()
        authed.post().uri("/api/items/$a/checklist").body(mapOf("text" to "t2")).exchange().expectStatus().isCreated()
        authed.patch().uri("/api/checklist/$t1").body(mapOf("done" to true)).exchange().expectStatus().isOk()
        patchItem(a, mapOf("statusItemId" to statusIds["In Progress"]))
        patchItem(a, mapOf("statusItemId" to statusIds["Done"]))

        // 乙:start+due,未动(仍 To Do,无清单无负责人)
        val b = createItem(authed, "ACT", "乙")["itemId"].asText()
        patchItem(b, mapOf("startDate" to "2026-02-01", "dueDate" to "2026-02-10"))

        val rows =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/ACT/gantt?scope=all")
                    .exchange()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )["rows"]
        val rowA = rows.first { it["title"].asText() == "甲" }
        val rowB = rows.first { it["title"].asText() == "乙" }

        // 甲:两迁入史齐 → 实际起止非空;负责人名与清单计数随行
        assertTrue(!rowA["actualStart"].isNull)
        assertTrue(!rowA["actualEnd"].isNull)
        assertEquals("me", rowA["assigneeName"].asText())
        assertEquals(1, rowA["checklistDone"].asInt())
        assertEquals(2, rowA["checklistTotal"].asInt())
        // 乙:无迁移史/无清单/无负责人 → 全 null
        assertTrue(rowB["actualStart"].isNull)
        assertTrue(rowB["actualEnd"].isNull)
        assertTrue(rowB["assigneeName"].isNull)
        assertTrue(rowB["checklistDone"].isNull)
        assertTrue(rowB["checklistTotal"].isNull)
    }

    @Test
    fun `YPJ-255 spec 0159 甘特行 totalMinutes 随行下发——补录计入无工时为零`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "TMN")
        val a = createItem(authed, "TMN", "有工时")["itemId"].asText()
        val b = createItem(authed, "TMN", "无工时")["itemId"].asText()
        authed
            .post()
            .uri("/api/items/$a/time-entries")
            .body(mapOf("minutes" to 90))
            .exchange()
            .expectStatus()
            .isCreated()
        val rows =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/TMN/gantt?scope=all")
                    .exchange()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )["rows"]
        assertEquals(90, rows.first { it["title"].asText() == "有工时" }["totalMinutes"].asLong())
        assertEquals(0, rows.first { it["title"].asText() == "无工时" }["totalMinutes"].asLong())
    }

    @Test
    fun `YPJ-233 行排序——同级 start 升序未排期靠后创建序兑底`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "ORD")

        // 创建序故意与 start 序错开;无日期 = 未排期
        val a = createItem(authed, "ORD", "A")
        val b = createItem(authed, "ORD", "B")
        val c = createItem(authed, "ORD", "C")
        val c2 = createItem(authed, "ORD", "c2", parentItemId = c["itemId"].asText()) // C 子,未排期,先建
        val c1 = createItem(authed, "ORD", "c1", parentItemId = c["itemId"].asText()) // C 子,后建
        val b1 = createItem(authed, "ORD", "b1", parentItemId = b["itemId"].asText())

        fun patch(id: String, date: String) {
            authed
                .patch()
                .uri("/api/items/$id")
                .body(mapOf("startDate" to date))
                .exchange()
                .expectStatus()
                .isOk()
        }
        patch(a["itemId"].asText(), "2026-12-03")
        patch(c["itemId"].asText(), "2026-12-01")
        patch(c1["itemId"].asText(), "2026-12-05")
        patch(b1["itemId"].asText(), "2026-12-04")

        val rows =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/ORD/gantt?scope=all")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )["rows"].map { it["title"].asText() }

        // YPJ-245(§8.2 借锚位):c2 借父链 C.start 12-01 → 不再未排期;C 组锚 = min(c1 12-05, c2 借 12-01) = 12-01 → C 组先;
        // C 组内 c2(借 12-01)先于 c1(12-05)——排序锚=渲染锚,与前端包络(groupStartOf 计入)同口径
        assertEquals(listOf("C", "c2", "c1", "A", "B", "b1"), rows)
    }

    @Test
    fun `甘特行 anchorDate——多前置取最晚 due,无前置 null(YPJ-243)`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "ANC")

        val predA = createItem(authed, "ANC", "前置A")
        authed
            .patch()
            .uri("/api/items/${predA["itemId"].asText()}")
            .body(mapOf("startDate" to "2026-09-01", "dueDate" to "2026-09-30T12:00"))
            .exchange()
            .expectStatus()
            .isOk()
        val predB = createItem(authed, "ANC", "前置B")
        authed
            .patch()
            .uri("/api/items/${predB["itemId"].asText()}")
            .body(mapOf("startDate" to "2026-09-05"))
            .exchange()
            .expectStatus()
            .isOk()
        val succ = createItem(authed, "ANC", "后继")

        // succ 依赖 A 与 B → anchor = max(A.due 09-30T12:00, B.start 09-05T00:00)
        authed
            .post()
            .uri("/api/items/${succ["itemId"].asText()}/dependencies")
            .body(mapOf("dependsOnItemId" to predA["itemId"].asText()))
            .exchange()
            .expectStatus()
            .isCreated()
        authed
            .post()
            .uri("/api/items/${succ["itemId"].asText()}/dependencies")
            .body(mapOf("dependsOnItemId" to predB["itemId"].asText()))
            .exchange()
            .expectStatus()
            .isCreated()

        val rows =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/ANC/gantt?scope=all")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )["rows"]

        val byTitle = rows.associateBy { it["title"].asText() }
        assertEquals("2026-09-30T12:00", byTitle["后继"]!!["anchorDate"].asText())
        assertTrue(byTitle["前置A"]!!["anchorDate"].isNull)
        assertTrue(byTitle["前置B"]!!["anchorDate"].isNull)
    }

    @Test
    fun `YPJ-245 过滤后父行排序按可见子孙——被过滤兄弟不影响包络锚(SRS 10-5)`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "VIS")

        // G1(父,不挂 milestone)+ 两子:L1(挂 milestone,12-20)/ C2(不挂,12-01 早锚);L2(根叶,12-10,挂 milestone)
        val g1 = createItem(authed, "VIS", "父G1")["itemId"].asText()
        val l1 = createItem(authed, "VIS", "圈上叶L1", parentItemId = g1)["itemId"].asText()
        authed.patch().uri("/api/items/$l1").body(mapOf("startDate" to "2026-12-20")).exchange().expectStatus().isOk()
        val c2 = createItem(authed, "VIS", "圈外叶C2", parentItemId = g1)["itemId"].asText()
        authed.patch().uri("/api/items/$c2").body(mapOf("startDate" to "2026-12-01")).exchange().expectStatus().isOk()
        val l2 = createItem(authed, "VIS", "根叶L2")["itemId"].asText()
        authed.patch().uri("/api/items/$l2").body(mapOf("startDate" to "2026-12-10")).exchange().expectStatus().isOk()

        val ms =
            json
                .readTree(
                    authed
                        .post()
                        .uri("/api/projects/VIS/milestones")
                        .body(mapOf("name" to "M1"))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(String::class.java)
                        .returnResult()
                        .responseBody!!,
                )["milestoneId"].asText()
        authed
            .put()
            .uri("/api/projects/VIS/milestones/$ms/items")
            .body(mapOf("itemIds" to listOf(l1, l2)))
            .exchange()
            .expectStatus()
            .isOk()

        val rows =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/VIS/gantt?scope=milestone&scopeIds=$ms")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )["rows"]

        // 可见集 = {L1, L2, G1(祖先链)};C2 被过滤,不得出现在行里
        val titles = rows.map { it["title"].asText() }
        assertTrue("圈外叶C2" !in titles)
        // 旧行为(bug):G1 锚含被过滤 C2 的 12-01 → G1 先;新行为:G1 锚 = 可见 L1 12-20 → L2(12-10) 先
        assertEquals(listOf("根叶L2", "父G1", "圈上叶L1"), titles)
    }

    @Test
    fun `YPJ-245 借锚行按依赖锚位排序,不再落未排期尾(SRS 8-2)`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "ANC2")

        // A(start 12-18,due 12-20)→ B(无日期,依赖 A,借锚 = A.due 12-20);C(12-15);D(12-22)
        val a = createItem(authed, "ANC2", "A")["itemId"].asText()
        authed.patch().uri("/api/items/$a").body(mapOf("startDate" to "2026-12-18", "dueDate" to "2026-12-20")).exchange().expectStatus().isOk()
        val b = createItem(authed, "ANC2", "B")["itemId"].asText()
        authed.post().uri("/api/items/$b/dependencies").body(mapOf("dependsOnItemId" to a)).exchange().expectStatus().isCreated()
        val c = createItem(authed, "ANC2", "C")["itemId"].asText()
        authed.patch().uri("/api/items/$c").body(mapOf("startDate" to "2026-12-15")).exchange().expectStatus().isOk()
        val d = createItem(authed, "ANC2", "D")["itemId"].asText()
        authed.patch().uri("/api/items/$d").body(mapOf("startDate" to "2026-12-22")).exchange().expectStatus().isOk()

        val rows =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/ANC2/gantt?scope=all")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )["rows"]

        // 旧行为(bug):B 无日期 → 未排期置尾;新行为:B 锚 = 借锚 12-20 → 排 A 后 D 前
        val titles = rows.map { it["title"].asText() }
        val bRow = rows.first { it["title"].asText() == "B" }
        assertTrue(bRow["anchorDate"].asText().startsWith("2026-12-20"))
        assertEquals(listOf("C", "A", "B", "D"), titles)
    }
}
