package yangzhou.api.standup

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-132:V9-S4 站会黑盒(昨日推进/今日名下/阻塞中/按人切换)。 */
class StandupApiTest : AbstractApiTest() {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `站会三组——昨日推进_今日名下_阻塞中——按人切换`() {
        val authed = bootstrapAndAuth()
        authed.post().uri("/api/members")
            .body(mapOf("displayName" to "小李"))
            .exchange().expectStatus().isCreated()
        createProject(authed, "CHE")
        val x = createItem(authed, "CHE", "x")["itemId"].asText()   // assignee = 我
        val y = createItem(authed, "CHE", "y")["itemId"].asText()   // → 小李
        authed.put().uri("/api/items/$x/assignee")
            .body(mapOf("assigneeItemId" to meId(authed)))
            .exchange().expectStatus().isOk()
        authed.put().uri("/api/items/$y/assignee")
            .body(mapOf("assigneeItemId" to memberIdOf(authed, "小李")))
            .exchange().expectStatus().isOk()

        // 我推进 x(留痕后回拨到昨日)
        val qa = json.readTree(
            authed.get().uri("/api/projects/CHE").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )["statuses"][3]["statusId"].asText()
        authed.patch().uri("/api/items/$x")
            .body(mapOf("statusItemId" to qa))
            .exchange().expectStatus().isOk()
        jdbc.update("update item_activity set created_at = now() - interval '25 hours' where kind = 'status_changed'")

        // z 依赖 x(未终态)→ z 被阻塞;指给小李
        val z = createItem(authed, "CHE", "z")["itemId"].asText()
        authed.post().uri("/api/items/$z/dependencies")
            .body(mapOf("dependsOnItemId" to x))
            .exchange().expectStatus().isCreated()
        authed.put().uri("/api/items/$z/assignee")
            .body(mapOf("assigneeItemId" to memberIdOf(authed, "小李")))
            .exchange().expectStatus().isOk()

        val mine = json.readTree(
            authed.get().uri("/api/standup").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("me", mine["displayName"].asText())
        assertTrue(mine["doneYesterday"].any { it["itemId"].asText() == x })
        assertTrue(mine["today"].any { it["itemId"].asText() == x })

        val li = memberIdOf(authed, "小李")
        val hers = json.readTree(
            authed.get().uri("/api/standup?member=$li").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertTrue(hers["today"].any { it["itemId"].asText() == y })
        assertTrue(hers["blocked"].any { it["itemId"].asText() == z })
        assertTrue(hers["doneYesterday"].size() == 0)
    }

    private fun meId(authed: org.springframework.test.web.servlet.client.RestTestClient): String =
        json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { !it["virtual"].asBoolean() }["memberId"].asText()

    private fun memberIdOf(authed: org.springframework.test.web.servlet.client.RestTestClient, name: String): String =
        json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { it["displayName"].asText() == name }["memberId"].asText()
}
