package yangzhou.api.activity

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 活动日志:每种变更路径自动留痕,一条变更一行。 */
class ActivityApiTest : AbstractApiTest() {

    private fun activityKinds(itemId: String): List<String> {
        val body = json.readTree(
            bootstrapAndAuth().get().uri("/api/items/$itemId/activity")
                .exchange().expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        return body.map { it["kind"].asText() }
    }

    private fun seed(): Pair<org.springframework.test.web.servlet.client.RestTestClient, String> {
        val authed = bootstrapAndAuth()
        createSkillAttribute(authed, "Java")
        createProject(authed, "CHE")
        val item = createItem(authed, "CHE", "初始标题", listOf(mapOf("attribute" to "Java", "minLevel" to 3)))
        return authed to item["itemId"].asText()
    }

    @Test
    fun `创建即留痕 created`() {
        val (_, itemId) = seed()
        assertEquals(listOf("created"), activityKinds(itemId))
    }

    @Test
    fun `标题与描述变更各留一行`() {
        val (authed, itemId) = seed()
        authed.patch().uri("/api/items/$itemId")
            .body(mapOf("title" to "新标题", "description" to "新描述"))
            .exchange().expectStatus().isOk()

        val kinds = activityKinds(itemId)
        assertTrue("title_changed" in kinds && "description_changed" in kinds)
    }

    @Test
    fun `状态变更留痕并记录新旧状态名`() {
        val (authed, itemId) = seed()
        val project = json.readTree(
            authed.get().uri("/api/projects/CHE").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        val dev = project["statuses"].first { it["name"].asText() == "Development" }["statusId"].asText()
        authed.patch().uri("/api/items/$itemId")
            .body(mapOf("statusItemId" to dev))
            .exchange().expectStatus().isOk()

        val body = json.readTree(
            authed.get().uri("/api/items/$itemId/activity")
                .exchange().expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        val sc = body.first { it["kind"].asText() == "status_changed" }
        assertEquals("To Do", sc["oldValue"].asText())
        assertEquals("Development", sc["newValue"].asText())
    }

    @Test
    fun `指派与取消留痕 assigned 和 unassigned`() {
        val (authed, itemId) = seed()
        val xiaoli = json.readTree(
            authed.post().uri("/api/members").body(mapOf("displayName" to "小李"))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )["memberId"].asText()

        authed.put().uri("/api/items/$itemId/assignee")
            .body(mapOf("assigneeItemId" to xiaoli))
            .exchange().expectStatus().isOk()
        authed.put().uri("/api/items/$itemId/assignee")
            .body(mapOf("assigneeItemId" to null))
            .exchange().expectStatus().isOk()

        val kinds = activityKinds(itemId)
        assertTrue("assigned" in kinds && "unassigned" in kinds)
    }

    @Test
    fun `需求整表替换留痕 requirement_changed 带摘要`() {
        val (authed, itemId) = seed()
        authed.put().uri("/api/items/$itemId/requirements")
            .body(mapOf("requirements" to listOf(
                mapOf("attribute" to "Java", "minLevel" to 4),
            )))
            .exchange().expectStatus().isOk()

        val body = json.readTree(
            authed.get().uri("/api/items/$itemId/activity")
                .exchange().expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        val rc = body.first { it["kind"].asText() == "requirement_changed" }
        assertEquals("Java>=4", rc["newValue"].asText())
    }

    @Test
    fun `无变更路径不产生多余日志`() {
        val (authed, itemId) = seed()
        // 不做任何变更,直接读
        assertEquals(listOf("created"), activityKinds(itemId))
    }
}
