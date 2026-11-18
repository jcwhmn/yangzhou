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
        assertEquals("2020-01-01T09:00", rows[0]["startDate"].asText())
        assertEquals("2020-01-31T18:00", rows[0]["dueDate"].asText())
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
}
