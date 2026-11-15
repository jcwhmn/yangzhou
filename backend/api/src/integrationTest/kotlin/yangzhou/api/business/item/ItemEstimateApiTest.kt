package yangzhou.api.business.item

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-186(spec 0128):item 预估黑盒——三态、required 强制、非法输入。 */
class ItemEstimateApiTest : AbstractApiTest() {
    @Test
    fun `预估 PATCH 三态与非法`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val itemId = createItem(authed, "CHE", "x")["itemId"].asText()

        // 设置(成对)
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("estimateValue" to "5", "estimateUnit" to "day"))
            .exchange()
            .expectStatus()
            .isOk()
        assertEquals(5.0, detail(authed, itemId)["estimateValue"].asDouble())
        assertEquals("day", detail(authed, itemId)["estimateUnit"].asText())

        // 不传 = 不变
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("title" to "改名"))
            .exchange()
            .expectStatus()
            .isOk()
        assertEquals(5.0, detail(authed, itemId)["estimateValue"].asDouble())

        // 空对 = 清除(单测已盖非法分支;集成只留关键路径:设置/不变/清除读回)
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("estimateValue" to "", "estimateUnit" to ""))
            .exchange()
            .expectStatus()
            .isOk()
        assertTrue(detail(authed, itemId)["estimateValue"].isNull)
        assertTrue(detail(authed, itemId)["estimateUnit"].isNull)
    }

    @Test
    fun `required项目 立票与清除强制`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")

        // 开 required(spec 0126 落库开关,本票接写入侧)
        authed
            .patch()
            .uri("/api/projects/CHE/settings")
            .body(mapOf("estimateRequired" to true))
            .exchange()
            .expectStatus()
            .isOk()

        // 立票无预估 → 400;带预估 → 201
        authed
            .post()
            .uri("/api/projects/CHE/items")
            .body(mapOf("title" to "缺预估"))
            .exchange()
            .expectStatus()
            .isBadRequest()
        val created = json.readTree(
            authed
                .post()
                .uri("/api/projects/CHE/items")
                .body(mapOf("title" to "带预估", "estimateValue" to "2", "estimateUnit" to "hour"))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!,
        )
        assertEquals(2.0, created["estimateValue"].asDouble())
        assertEquals("hour", created["estimateUnit"].asText())

        // 显式清除 → 400(required)
        val itemId = created["itemId"].asText()
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("estimateValue" to "", "estimateUnit" to ""))
            .exchange()
            .expectStatus()
            .isBadRequest()
    }

    private fun detail(
        authed: org.springframework.test.web.servlet.client.RestTestClient,
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
}
