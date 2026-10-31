package yangzhou.api.business.item

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import java.util.UUID
import kotlin.test.assertEquals

/** YPJ-103 集成(spec 0082):item 自定义属性挂载链 + 搜索命中 + custom 约束。 */
class ItemAttributeApiTest : AbstractApiTest() {
    @Autowired
    private lateinit var encoder: PasswordEncoder

    private fun createCustomAttribute(
        authed: RestTestClient,
        name: String,
    ): String {
        val body =
            authed
                .post()
                .uri("/api/attributes")
                .body(mapOf("name" to name, "kind" to "custom"))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        return json.readTree(body)["attributeId"].asText()
    }

    private fun replaceAttributes(
        client: RestTestClient,
        itemId: UUID,
        values: List<Map<String, String>>,
    ) = client
        .put()
        .uri("/api/items/$itemId/attributes")
        .body(mapOf("values" to values))
        .exchange()

    @Test
    fun `挂载链——custom 词表挂值回读再替换`() {
        val a = bootstrapAndAuth()
        createProject(a, "ATT")
        createCustomAttribute(a, "模块")
        val item = createItem(a, "ATT", "订单改造")
        val itemId = item["itemId"].asText()

        // 挂一行 → 详情回带
        replaceAttributes(a, UUID.fromString(itemId), listOf(mapOf("attribute" to "模块", "value" to "支付")))
            .expectStatus()
            .isOk()
        var detail =
            json.readTree(
                a
                    .get()
                    .uri("/api/items/$itemId")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        assertEquals(1, detail["attributes"].size())
        assertEquals("模块", detail["attributes"][0]["attribute"].asText())
        assertEquals("支付", detail["attributes"][0]["value"].asText())

        // 列表也回带
        val list =
            json.readTree(
                a
                    .get()
                    .uri("/api/projects/ATT/items")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        assertEquals(1, list[0]["attributes"].size())

        // 替换成两行(含新词表)→ 回读
        createCustomAttribute(a, "交付物链接")
        replaceAttributes(
            a,
            UUID.fromString(itemId),
            listOf(
                mapOf("attribute" to "模块", "value" to "账务"),
                mapOf("attribute" to "交付物链接", "value" to "https://example.com/deliverable-42"),
            ),
        ).expectStatus().isOk()
        detail =
            json.readTree(
                a
                    .get()
                    .uri("/api/items/$itemId")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        assertEquals(2, detail["attributes"].size())
    }

    @Test
    fun `搜索命中属性值`() {
        val a = bootstrapAndAuth()
        createProject(a, "ATT")
        createCustomAttribute(a, "交付物链接")
        val item = createItem(a, "ATT", "毫无特征的标题")
        val itemId = item["itemId"].asText()
        replaceAttributes(a, UUID.fromString(itemId), listOf(mapOf("attribute" to "交付物链接", "value" to "https://example.com/needle-42")))
            .expectStatus()
            .isOk()

        val hits =
            json.readTree(
                a
                    .get()
                    .uri("/api/search?q=needle-42")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        assertEquals(1, hits.size())
        assertEquals("ATT", hits[0]["projectKey"].asText())
    }

    @Test
    fun `非custom词表拒挂与重复与删被引用词表409`() {
        val a = bootstrapAndAuth()
        createProject(a, "ATT")
        a
            .post()
            .uri("/api/attributes")
            .body(mapOf("name" to "技能", "kind" to "skill"))
            .exchange()
            .expectStatus()
            .isCreated()
        val customId = createCustomAttribute(a, "模块")
        val item = createItem(a, "ATT", "t")
        val itemId = UUID.fromString(item["itemId"].asText())

        // 非 custom → 400
        replaceAttributes(a, itemId, listOf(mapOf("attribute" to "技能", "value" to "x")))
            .expectStatus()
            .isBadRequest()
        // 词表不存在 → 404
        replaceAttributes(a, itemId, listOf(mapOf("attribute" to "没有", "value" to "x")))
            .expectStatus()
            .isNotFound()
        // 同词表重复 → 400
        replaceAttributes(
            a,
            itemId,
            listOf(
                mapOf("attribute" to "模块", "value" to "a"),
                mapOf("attribute" to "模块", "value" to "b"),
            ),
        ).expectStatus().isBadRequest()

        // 挂上后删词表 → 409;清掉引用后删 → 204
        replaceAttributes(a, itemId, listOf(mapOf("attribute" to "模块", "value" to "支付")))
            .expectStatus()
            .isOk()
        a
            .delete()
            .uri("/api/attributes/$customId")
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CONFLICT)
        replaceAttributes(a, itemId, emptyList()).expectStatus().isOk()
        a
            .delete()
            .uri("/api/attributes/$customId")
            .exchange()
            .expectStatus()
            .isNoContent()
    }
}
