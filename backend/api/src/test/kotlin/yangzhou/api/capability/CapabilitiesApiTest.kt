package yangzhou.api.capability

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CapabilitiesApiTest : AbstractApiTest() {

    @Test
    fun `能力自评 upsert 与未评级`() {
        val authed = bootstrapAndAuth()
        createSkillAttribute(authed, "Java")
        createSkillAttribute(authed, "React")

        setCapability(authed, "Java", 4)
        setCapability(authed, "React", null) // 有但未评级

        val list = json.readTree(
            authed.get().uri("/api/capabilities").exchange()
                .expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        val java = list.first { it["attribute"].asText() == "Java" }
        val react = list.first { it["attribute"].asText() == "React" }
        assertEquals(4, java["level"].asInt())
        assertEquals(true, react["level"].isNull)
    }

    @Test
    fun `level 越界 400,未知属性 404`() {
        val authed = bootstrapAndAuth()
        createSkillAttribute(authed, "Java")

        authed.put().uri("/api/capabilities")
            .body(mapOf("attribute" to "Java", "level" to 5))
            .exchange().expectStatus().isBadRequest()

        authed.put().uri("/api/capabilities")
            .body(mapOf("attribute" to "不存在的", "level" to 1))
            .exchange().expectStatus().isNotFound()
    }
}
