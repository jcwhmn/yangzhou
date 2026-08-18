package yangzhou.api.attribute

import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttributesApiTest : AbstractApiTest() {

    @Test
    fun `创建并列出词表属性`() {
        val authed = bootstrapAndAuth()

        createSkillAttribute(authed, "Java")
        createSkillAttribute(authed, "后端", leveled = false)

        val list = authed.get().uri("/api/attributes")
            .exchange().expectStatus().isOk()
            .expectBody(String::class.java).returnResult().responseBody!!
        val attrs = json.readTree(list)
        assertEquals(2, attrs.size())
        assertTrue(attrs.any { it["name"].asText() == "Java" && it["kind"].asText() == "skill" && it["leveled"].asBoolean() })
        assertTrue(attrs.any { it["name"].asText() == "后端" && !it["leveled"].asBoolean() })
    }

    @Test
    fun `重名属性 409`() {
        val authed = bootstrapAndAuth()
        createSkillAttribute(authed, "Java")
        authed.post().uri("/api/attributes")
            .body(mapOf("name" to "Java", "kind" to "skill", "leveled" to true))
            .exchange().expectStatus().isEqualTo(409)
    }

    @Test
    fun `kind 非法 400`() {
        val authed = bootstrapAndAuth()
        authed.post().uri("/api/attributes")
            .body(mapOf("name" to "X", "kind" to "cert", "leveled" to true))
            .exchange().expectStatus().isBadRequest()
    }

    @Test
    fun `leveled 切换(label 升 skill)不丢数据`() {
        val authed: RestTestClient = bootstrapAndAuth()
        val attr = createSkillAttribute(authed, "前端", leveled = false)

        authed.patch().uri("/api/attributes/${attr["attributeId"].asText()}")
            .body(mapOf("leveled" to true))
            .exchange().expectStatus().isOk()
            .expectBody(String::class.java).let { }

        val list = json.readTree(
            authed.get().uri("/api/attributes").exchange()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(true, list[0]["leveled"].asBoolean())
    }
}
