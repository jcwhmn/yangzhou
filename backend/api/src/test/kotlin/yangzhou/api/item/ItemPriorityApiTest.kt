package yangzhou.api.item

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-140:V10-S2 priority 黑盒(设置/清除/非法/看板)。 */
class ItemPriorityApiTest : AbstractApiTest() {

    @Test
    fun `priority 设置_清除_非法`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val itemId = createItem(authed, "CHE", "x")["itemId"].asText()

        authed.patch().uri("/api/items/$itemId")
            .body(mapOf("priority" to "P0"))
            .exchange().expectStatus().isOk()
        assertEquals("P0", detail(authed, itemId)["priority"].asText())

        // 不传 = 不变
        authed.patch().uri("/api/items/$itemId")
            .body(mapOf("title" to "改名"))
            .exchange().expectStatus().isOk()
        assertEquals("P0", detail(authed, itemId)["priority"].asText())

        // 空串 = 清除
        authed.patch().uri("/api/items/$itemId")
            .body(mapOf("priority" to ""))
            .exchange().expectStatus().isOk()
        assertTrue(detail(authed, itemId)["priority"].isNull)

        // 非法 → 400
        authed.patch().uri("/api/items/$itemId")
            .body(mapOf("priority" to "P9"))
            .exchange().expectStatus().isBadRequest()
    }

    private fun detail(authed: org.springframework.test.web.servlet.client.RestTestClient, itemId: String) =
        json.readTree(
            authed.get().uri("/api/items/$itemId").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
}
