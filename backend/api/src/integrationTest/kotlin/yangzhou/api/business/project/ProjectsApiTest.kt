package yangzhou.api.business.project

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectsApiTest : AbstractApiTest() {
    @Test
    fun `建项目自带默认四态 workflow,Done 终态`() {
        val authed = bootstrapAndAuth()
        val project = createProject(authed, "CHE", "chess")

        assertEquals("CHE", project["key"].asText())
        val names = project["statuses"].map { it["name"].asText() }
        assertEquals(listOf("To Do", "In Progress", "In Review", "QA", "Done"), names)
        assertTrue(project["statuses"][4]["statusType"].asText() == "FINAL")
    }

    @Test
    fun `新项目默认种 workflow 规则 3 槽,closed_unmerged 留空(YPJ-36)`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")

        val rules =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/CHE/workflow-rules")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )

        assertEquals(
            listOf("branch_created", "pr_opened", "pr_merged", "pr_closed_unmerged"),
            rules.map { it["eventType"].asText() },
        )
        assertEquals(
            listOf("In Progress", "In Review", "Done", null),
            rules.map { if (it["statusName"].isNull) null else it["statusName"].asText() },
        )
    }

    @Test
    fun `重复 key 409,非法 key 400`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")

        authed
            .post()
            .uri("/api/projects")
            .body(mapOf("key" to "CHE", "name" to "again"))
            .exchange()
            .expectStatus()
            .isEqualTo(409)

        authed
            .post()
            .uri("/api/projects")
            .body(mapOf("key" to "che", "name" to "lower"))
            .exchange()
            .expectStatus()
            .isBadRequest()
    }

    @Test
    fun `工作量刻度 默认与 PATCH 往返 负例 400(YPJ-180)`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "EST")

        // 默认:五档占位 + isDefault
        val before =
            json.readTree(
                authed.get().uri("/api/projects/EST").exchange().expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
            )
        assertEquals(listOf("微", "小", "中", "大", "超大"), before["estimateScales"].map { it["label"].asText() })
        assertEquals(true, before["estimateScalesIsDefault"].asBoolean())
        assertEquals(false, before["estimateRequired"].asBoolean())

        // PATCH 整体替换 + required
        val scales = listOf(mapOf("key" to "h8", "label" to "标准日", "value" to 8.0, "unit" to "hour"))
        val after =
            json.readTree(
                authed
                    .patch()
                    .uri("/api/projects/EST/settings")
                    .body(mapOf("estimateScales" to scales, "estimateRequired" to true))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        assertEquals("标准日", after["estimateScales"][0]["label"].asText())
        assertEquals(8.0, after["estimateScales"][0]["value"].asDouble())
        assertEquals("hour", after["estimateScales"][0]["unit"].asText())
        assertEquals(true, after["estimateRequired"].asBoolean())
        assertEquals(false, after["estimateScalesIsDefault"].asBoolean())

        // 负例:key 重复 400;0 档 400;库内未被坏请求污染
        authed
            .patch()
            .uri("/api/projects/EST/settings")
            .body(
                mapOf(
                    "estimateScales" to
                        listOf(
                            mapOf("key" to "k", "label" to "一", "value" to 1.0, "unit" to "day"),
                            mapOf("key" to "k", "label" to "二", "value" to 2.0, "unit" to "day"),
                        ),
                ),
            )
            .exchange()
            .expectStatus()
            .isBadRequest()
        authed
            .patch()
            .uri("/api/projects/EST/settings")
            .body(mapOf("estimateScales" to emptyList<Map<String, Any>>()))
            .exchange()
            .expectStatus()
            .isBadRequest()
        authed
            .patch()
            .uri("/api/projects/EST/settings")
            .body(mapOf("estimateScales" to listOf(mapOf("key" to "k", "label" to "月", "value" to 1.0, "unit" to "month"))))
            .exchange()
            .expectStatus()
            .isBadRequest()
        val intact =
            json.readTree(
                authed.get().uri("/api/projects/EST").exchange().expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
            )
        assertEquals("标准日", intact["estimateScales"][0]["label"].asText())
    }

    /** YPJ-226(spec 0163):新建缺省 work;切 wall;工作制+全天窗 400。 */
    @Test
    fun `工作制轴制式——新建默认 work,可切 wall,work 拒全天窗`() {
        val authed = bootstrapAndAuth()
        val project = createProject(authed, "WMR", "workmode")
        assertEquals("work", project["workMode"].asText())

        val switched =
            json.readTree(
                authed
                    .patch()
                    .uri("/api/projects/WMR/settings")
                    .body(mapOf("workMode" to "wall"))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        assertEquals("wall", switched["workMode"].asText())

        authed
            .patch()
            .uri("/api/projects/WMR/settings")
            .body(mapOf("workMode" to "work"))
            .exchange()
            .expectStatus()
            .isOk() // 全天窗+工作制合法(窗口正交,spec 0163):只跳周末
        authed
            .patch()
            .uri("/api/projects/WMR/settings")
            .body(mapOf("workMode" to "work", "workdayStart" to "09:00", "workdayEnd" to "18:00"))
            .exchange()
            .expectStatus()
            .isOk()
        authed
            .patch()
            .uri("/api/projects/WMR/settings")
            .body(mapOf("workMode" to "bogus"))
            .exchange()
            .expectStatus()
            .isBadRequest()
        val final =
            json.readTree(
                authed.get().uri("/api/projects/WMR").exchange().expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
            )
        assertEquals("work", final["workMode"].asText())
        assertEquals("09:00", final["workdayStart"].asText())
    }
}
