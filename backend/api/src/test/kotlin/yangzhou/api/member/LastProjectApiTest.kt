package yangzhou.api.member

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-147:V11-S1 登录后进入上次项目黑盒。 */
class LastProjectApiTest : AbstractApiTest() {

    @Test
    fun `设置 lastProjectKey——读回——清空`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val meId = json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { !it["virtual"].asBoolean() }["memberId"].asText()

        // 设置
        val putRes = authed.put().uri("/api/members/$meId/last-project")
            .body(mapOf("key" to "CHE"))
            .exchange()
        val after = json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { it["memberId"].asText() == meId }
        assertEquals("CHE", after["lastProjectKey"].asText())

        // 空串清除
        authed.put().uri("/api/members/$meId/last-project")
            .body(mapOf("key" to ""))
            .exchange().expectStatus().isOk()
        assertTrue(json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { it["memberId"].asText() == meId }["lastProjectKey"].isNull)
    }

    @Test
    fun `me 别名路由——设置与清空(V11-S4 前端对齐)`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")

        authed.put().uri("/api/members/me/last-project")
            .body(mapOf("key" to "CHE"))
            .exchange().expectStatus().isOk()
        val after = json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { !it["virtual"].asBoolean() }
        assertEquals("CHE", after["lastProjectKey"].asText())

        authed.put().uri("/api/members/me/last-project")
            .body(mapOf("key" to ""))
            .exchange().expectStatus().isOk()
        assertTrue(json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { !it["virtual"].asBoolean() }["lastProjectKey"].isNull)
    }
}
