package yangzhou.api.workflow

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowApiTest : AbstractApiTest() {

    @Test
    fun `增删改 status——新建带图标、改名、置终态、删除未引用的`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")

        val created = json.readTree(
            authed.post().uri("/api/projects/CHE/statuses")
                .body(mapOf("name" to "Code Review", "icon" to "🔍", "isFinal" to false))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("Code Review", created["name"].asText())
        assertEquals("🔍", created["icon"].asText())
        val statusId = created["statusId"].asText()

        // 改名 + 置终态
        val updated = json.readTree(
            authed.patch().uri("/api/statuses/$statusId")
                .body(mapOf("name" to "Review", "isFinal" to true))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("Review", updated["name"].asText())
        assertTrue(updated["isFinal"].asBoolean())

        // 删除未被 item 引用的状态
        authed.delete().uri("/api/statuses/$statusId")
            .exchange().expectStatus().isNoContent()
    }

    @Test
    fun `重名状态 409,未知项目 404`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")

        authed.post().uri("/api/projects/CHE/statuses")
            .body(mapOf("name" to "To Do"))
            .exchange().expectStatus().isEqualTo(409)

        authed.post().uri("/api/projects/NOPE/statuses")
            .body(mapOf("name" to "X"))
            .exchange().expectStatus().isNotFound()
    }

    @Test
    fun `删除仍被 item 引用的状态 409`() {
        val authed = bootstrapAndAuth()
        val project = createProject(authed, "CHE")
        createItem(authed, "CHE", "x") // 默认挂 To Do

        val toDo = project["statuses"][0]["statusId"].asText()
        authed.delete().uri("/api/statuses/$toDo")
            .exchange().expectStatus().isEqualTo(409)
    }

    @Test
    fun `迁移表空即自由迁移——配置后非法 409 合法通过——引用未知状态 400`() {
        val authed = bootstrapAndAuth()
        val project = createProject(authed, "CHE")
        val toDo = project["statuses"][0]["statusId"].asText()
        val inProgress = project["statuses"][1]["statusId"].asText()
        val done = project["statuses"][2]["statusId"].asText()
        val item = createItem(authed, "CHE", "x") // To Do

        // 空:To Do → Done 直跳自由通过
        authed.patch().uri("/api/items/${item["itemId"].asText()}")
            .body(mapOf("statusItemId" to done))
            .exchange().expectStatus().isOk()
        authed.patch().uri("/api/items/${item["itemId"].asText()}")
            .body(mapOf("statusItemId" to toDo))
            .exchange().expectStatus().isOk()

        // 配置:To Do→In Progress、In Progress→Done
        authed.put().uri("/api/projects/CHE/transitions")
            .body(
                mapOf(
                    "transitions" to listOf(
                        mapOf("from" to "To Do", "to" to "In Progress"),
                        mapOf("from" to "In Progress", "to" to "Done"),
                    ),
                ),
            )
            .exchange().expectStatus().isOk()

        val list = json.readTree(
            authed.get().uri("/api/projects/CHE/transitions").exchange()
                .expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(2, list.size())

        // 非法:To Do → Done(表里没有)
        authed.patch().uri("/api/items/${item["itemId"].asText()}")
            .body(mapOf("statusItemId" to done))
            .exchange().expectStatus().isEqualTo(409)

        // 合法:To Do → In Progress
        authed.patch().uri("/api/items/${item["itemId"].asText()}")
            .body(mapOf("statusItemId" to inProgress))
            .exchange().expectStatus().isOk()

        // 引用未知状态 400
        authed.put().uri("/api/projects/CHE/transitions")
            .body(mapOf("transitions" to listOf(mapOf("from" to "To Do", "to" to "幽灵状态"))))
            .exchange().expectStatus().isBadRequest()
    }

    @Test
    fun `新 item 默认落起点——多起点取 position 最小,无起点回退首列`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val statuses = json.readTree(
            authed.get().uri("/api/projects/CHE").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )["statuses"]
        // 默认:To Do 是起点
        assertEquals(true, statuses[0]["isStart"].asBoolean())

        // 加第二个起点(position 更小)→ 新 item 落它
        authed.post().uri("/api/projects/CHE/statuses")
            .body(mapOf("name" to "Backlog", "isStart" to true, "position" to -1))
            .exchange().expectStatus().isCreated()
        val item = createItem(authed, "CHE", "x")
        assertEquals("Backlog", item["status"].asText())

        // 全部取消起点 → 回退首列(position 最小 = Backlog)
        val all = json.readTree(authed.get().uri("/api/projects/CHE").exchange()
            .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!)["statuses"]
        all.forEach { st ->
            authed.patch().uri("/api/statuses/${st["statusId"].asText()}")
                .body(mapOf("isStart" to false)).exchange().expectStatus().isOk()
        }
        val item2 = createItem(authed, "CHE", "y")
        assertEquals("Backlog", item2["status"].asText()) // position -1 < 0
    }

    @Test
    fun `默认 workflow 仍为三列`() {
        val authed = bootstrapAndAuth()
        val project = createProject(authed, "CHE")
        val names = project["statuses"].map { it["name"].asText() }
        assertEquals(listOf("To Do", "In Progress", "Done"), names)
    }
}
